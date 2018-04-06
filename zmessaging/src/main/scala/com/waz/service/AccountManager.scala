/*
 * Wire
 * Copyright (C) 2016 Wire Swiss GmbH
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.waz.service

import java.io._
import java.util.Locale
import java.util.zip.{ZipException, ZipOutputStream}

import com.waz.ZLog.ImplicitTag._
import com.waz.ZLog._
import com.waz.api.ZmsVersion
import com.waz.api.impl.ErrorResponse
import com.waz.api.impl.ErrorResponse.internalError
import com.waz.content.UserPreferences.{ClientRegVersion, SelfClient}
import com.waz.db.ZMessagingDB
import com.waz.model.AccountData.Password
import com.waz.model._
import com.waz.model.otr.{Client, ClientId}
import com.waz.service.AccountManager.ClientRegistrationState.{LimitReached, PasswordMissing, Registered, Unregistered}
import com.waz.service.otr.OtrService.sessionId
import com.waz.service.tracking.LoggedOutEvent
import com.waz.sync.client.InvitationClient.ConfirmedTeamInvitation
import com.waz.sync.client.{InvitationClient, OtrClient}
import com.waz.threading.{CancellableFuture, SerialDispatchQueue}
import com.waz.utils.IoUtils.withResource
import com.waz.utils._
import com.waz.utils.events.Signal
import com.waz.utils.wrappers.Context
import com.waz.znet.Response.Status
import com.waz.znet.ZNetClient._
import org.json.JSONObject
import org.threeten.bp.Instant

import scala.collection.immutable.ListMap
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.Right

class AccountManager(val userId:   UserId,
                     val teamId:   Option[TeamId],
                     val global:   GlobalModule,
                     val accounts: AccountsService) {
  import AccountManager._

  implicit val dispatcher = new SerialDispatchQueue()
  implicit val accountContext: AccountContext = new AccountContext(userId, accounts)

  verbose(s"Creating for: $userId")

  val storage   = global.factory.baseStorage(userId)
  val db        = storage.db
  val userPrefs = storage.userPrefs

  val account     = global.accountsStorage.signal(userId)
  val clientState = userPrefs(SelfClient).signal
  val clientId    = clientState.map(_.clientId)

  val context        = global.context
  val contextWrapper = Context.wrap(context)

  val cryptoBox         = global.factory.cryptobox(userId, storage)
  val auth              = global.factory.auth(userId)
  val netClient         = global.factory.client(auth)
  val otrClient         = new OtrClient(netClient)
  val credentialsClient = global.factory.credentialsClient(netClient)

  val timeouts       = global.timeouts
  val network        = global.network
  val lifecycle      = global.lifecycle
  val reporting      = global.reporting
  val tracking       = global.trackingService
  val clientsStorage = storage.otrClientsStorage

  val invitationClient = new InvitationClient(netClient)
  val invitedToTeam = Signal(ListMap.empty[TeamInvitation, Option[Either[ErrorResponse, ConfirmedTeamInvitation]]])

  val zmessaging: Future[ZMessaging] = {
    for {
      cId     <- clientId.collect { case Some(id) => id }.head
      Some(_) <- checkCryptoBox()
    } yield {
      verbose(s"Creating new ZMessaging instance for $userId, $cId, $teamId, service: $this")
      global.factory.zmessaging(teamId, cId, this, storage, cryptoBox)
    }
  }

  val firstLogin: Signal[Boolean] = db.dbHelper.wasCreated

  private val otrClients =
    storage.otrClientsStorage.signal(userId)
      .map(_.clients.values.toSet)
      .orElse(Signal.const(Set.empty[Client]))

  // listen to client changes, logout and delete cryptobox if current client is removed
  private val otrCurrentClient = clientId.flatMap {
    case Some(cId) => otrClients.map(_.find(_.id == cId))
    case _ => Signal const Option.empty[Client]
  }
  private var hasClient = false
  otrCurrentClient.map(_.isDefined) { exists =>
    if (hasClient && !exists) {
      info(s"client has been removed on backend, logging out")
      global.trackingService.loggedOut(LoggedOutEvent.RemovedClient, userId)
      logoutAndResetClient()
    }
    hasClient = exists
  }

  def fingerprintSignal(uId: UserId, cId: ClientId): Signal[Option[Array[Byte]]] =
    for {
      selfClientId <- clientId
      fingerprint  <-
        if (userId == uId && selfClientId.contains(cId))
          Signal.future(cryptoBox(Future successful _.getLocalFingerprint))
        else
          cryptoBox.sessions.remoteFingerprint(sessionId(uId, cId))
    } yield fingerprint

  def getOrRegisterClient(password: Option[Password] = None): ErrorOr[ClientRegistrationState] = {
    verbose(s"registerClient: pw: $password")

    //TODO - even if we call this, we'll do a slow sync once we get a client. For now, that's necessary, but maybe we can avoid the
    //unnecessary extra fetch?
    def getSelfClients: ErrorOr[Unit] = {
      for {
        resp <- otrClient.loadClients().future
        _    <- resp.fold(_ => Future.successful({}), cs => clientsStorage.updateClients(Map(userId -> cs), replace = true))
      } yield resp.fold(err => Left(err), _ => Right({}))
    }

    Serialized.future("register-client", this) {
      clientState.head.flatMap {
        case st@Registered(_) =>
          verbose("Client already registered, returning")
          Future.successful(Right(st))
        case _ =>
          for {
            resp1 <- getSelfClients
            resp2 <- resp1.fold(e => Future.successful(Left(e)), _ => registerNewClient(password))
            _ <- resp2.fold(_ => Future.successful({}), userPrefs(SelfClient) := _)
          } yield resp2
      }
    }
  }

  //Note: this method should only be externally called from tests and debug preferences. User `registerClient` for all normal flows.
  def registerNewClient(password: Option[Password] = None): ErrorOr[ClientRegistrationState] = {
    for {
      pw       <- password.fold2(account.map(_.password).head, p => Future.successful(Some(p)))
      client   <- cryptoBox.createClient()
      resp <- client match {
        case None => Future.successful(Left(internalError("CryptoBox missing")))
        case Some((c, lastKey, keys)) =>
          otrClient.postClient(userId, c, lastKey, keys, pw).future.flatMap {
            case Right(cl) =>
              for {
                _    <- userPrefs(ClientRegVersion) := ZmsVersion.ZMS_MAJOR_VERSION
                _    <- clientsStorage.updateClients(Map(userId -> Seq(c.copy(id = cl.id).updated(cl))))
              } yield Right(Registered(cl.id))
            case Left(ErrorResponse(Status.Forbidden, _, "missing-auth"))     => Future.successful(Right(PasswordMissing))
            case Left(ErrorResponse(Status.Forbidden, _, "too-many-clients")) => Future.successful(Right(LimitReached))
            case Left(error)                                                  => Future.successful(Left(error))
          }
      }
    } yield resp
  }

  def deleteClient(id: ClientId, password: Password): ErrorOr[Unit] =
    clientsStorage.get(userId).flatMap {
      case Some(cs) if cs.clients.contains(id) =>
        otrClient.deleteClient(id, password).future.flatMap {
          case Right(_) => for {
            _ <- clientsStorage.update(userId, { uc => uc.copy(clients = uc.clients - id) })
          } yield Right(())
          case res => Future.successful(res)
        }
      case _ => Future.successful(Left(internalError("Client does not belong to current user or was already deleted")))
    }

  def inviteToTeam(emailAddress: EmailAddress, name: Option[String], locale: Option[Locale] = None): ErrorOrResponse[ConfirmedTeamInvitation] =
    teamId match {
      case Some(tid) =>
        val invitation = TeamInvitation(tid, emailAddress, name.getOrElse(" "), locale)
        invitationClient.postTeamInvitation(invitation).map {
          returning(_) { r =>
            if (r.isRight) invitedToTeam.mutate {
              _ ++ ListMap(invitation -> Some(r))
            }
          }
        }
      case None => CancellableFuture.successful(Left(internalError("Not a team account")))
    }

  def getSelf: ErrorOr[UserInfo] = {
    auth.currentToken().flatMap {
      case Right(token) => accounts.loginClient.getSelfUserInfo(token)
      case Left(err) => Future.successful(Left(err))
    }
  }

  // The current solution writes the database file(s) directly to the newly created zip file.
  // This way we save memory, but it means that it takes longer before we can release the lock.
  // If this becomes the problem, we might consider first copying the database file(s) to the
  // external storage directory, release the lock, and then safely proceed with zipping them.
  def exportDatabase: Future[Either[DatabaseBackupError, File]] = {

    def handle: Future[String] = for {
      zms  <- zmessaging
      user <- zms.users.selfUser.head
    } yield user.handle.fold("")(_.string)

    def zipDatabase(zipFile: File): Option[DatabaseBackupError] = try {
      withResource(new ZipOutputStream(new FileOutputStream(zipFile))) { zip =>
        val dbFile = context.getDatabasePath(userId.str)
        withResource(
          new BufferedInputStream(new FileInputStream(dbFile))
        ) {
          IoUtils.writeZipEntry(_, zip, s"${userId.str}")
        }

        val walFile = new File(dbFile.getAbsolutePath + "-wal")
        verbose(s"WAL file: ${walFile.getAbsolutePath} exists: ${walFile.exists}, length: ${if (walFile.exists) walFile.length else 0}")

        if (walFile.exists)
          withResource(
            new BufferedInputStream(new FileInputStream(walFile))
          ) {
            IoUtils.writeZipEntry(_, zip, s"${userId.str}-wal")
          }

        withResource(
          new ByteArrayInputStream(BackupMetadataEncoder(BackupMetadata(userId)).toString.getBytes("utf8"))
        ) {
          IoUtils.writeZipEntry(_, zip, "export.json")
        }

        verbose(s"database export finished: ${zipFile.getAbsolutePath} . Data contains: ${zipFile.length} bytes")
        None
      }
    } catch {
      case ex: ZipException => Some(ZipError(ex.getMessage))
      case ex: IOException  => Some(IOError(ex.getMessage))
      case ex: Throwable    => Some(OtherError(ex.getMessage))
    }

    for {
      h    <- handle
      file = returning(new File(context.getExternalCacheDir, s"Wire-$h-Backup_${Instant.now().toString}.Android_wbu")) { _.deleteOnExit() }
      res  <- db(_ => zipDatabase(file))
    } yield res.fold[Either[DatabaseBackupError, File]](Right(file))(Left(_))

  }

  private def checkCryptoBox() =
    cryptoBox.cryptoBox.flatMap {
      case Some(cb) => Future.successful(Some(cb))
      case None => logoutAndResetClient().map(_ => None)
    }

  private def logoutAndResetClient() =
    for {
      _ <- accounts.logout(userId)
      _ <- cryptoBox.deleteCryptoBox()
      _ <- userPrefs(SelfClient) := Unregistered
    } yield ()

  def setEmail(email: EmailAddress): ErrorOr[Unit] = {
    verbose(s"setEmail: $email")
    credentialsClient.updateEmail(email).future
  }

  def setPassword(password: Password): ErrorOr[Unit] = {
    verbose(s"setPassword: $password")
    credentialsClient.updatePassword(password, None).future.flatMap {
      case Left(err) => Future.successful(Left(err))
      case Right(_) => global.accountsStorage.update(userId, _.copy(password = Some(password))).map(_ => Right({}))
    }
  }

  //TODO should only have one request at a time?
  def checkEmailActivation(email: EmailAddress): ErrorOrResponse[Unit] = {
    verbose(s"checkEmailActivation $email")
    CancellableFuture.lift(getSelf).flatMap {
      case Right(user) if !user.email.contains(email) => CancellableFuture.delay(3.seconds).flatMap(_ => checkEmailActivation(email))
      case Right(_)                                   => CancellableFuture.successful(Right({}))
      case Left(err)                                  => CancellableFuture.successful(Left(err))
    }
  }
}

object AccountManager {

  sealed trait ClientRegistrationState {
    val clientId: Option[ClientId] = None
  }

  object ClientRegistrationState {
    case object Unregistered    extends ClientRegistrationState
    case object PasswordMissing extends ClientRegistrationState
    case object LimitReached    extends ClientRegistrationState
    case class  Registered(cId: ClientId) extends ClientRegistrationState {
      override val clientId = Some(cId)
    }
  }

  val ActivationThrottling = new ExponentialBackoff(2.seconds, 15.seconds)

  case class BackupMetadata(userId: UserId,
                            version: String = ZMessagingDB.DbVersion.toString,
                            clientId: Option[ClientId] = None,
                            creationTime: Instant = Instant.now(),
                            platform: String = "Android")

  lazy val BackupMetadataEncoder: JsonEncoder[BackupMetadata] = new JsonEncoder[BackupMetadata] {
    override def apply(data: BackupMetadata): JSONObject = JsonEncoder { o =>
      o.put("user_id",        data.userId.str)
      o.put("version",        data.version)

      data.clientId.foreach { id => o.put("client_id", id.str) }
      o.put("creation_time",  JsonEncoder.encodeISOInstant(data.creationTime))
      o.put("platform",       data.platform)
    }
  }

  lazy val BackupMetadataDecoder: JsonDecoder[BackupMetadata] = new JsonDecoder[BackupMetadata] {
    import JsonDecoder._
    override def apply(implicit js: JSONObject): BackupMetadata = {
      BackupMetadata(
        decodeUserId('user_id),
        'version,
        decodeOptClientId('client_id),
        JsonDecoder.decodeISOInstant('creation_time),
        'platform
      )
    }
  }

  sealed trait DatabaseBackupError { def msg: String }

  case class ZipError  (override val msg: String) extends DatabaseBackupError
  case class IOError   (override val msg: String) extends DatabaseBackupError
  case class OtherError(override val msg: String) extends DatabaseBackupError

}
