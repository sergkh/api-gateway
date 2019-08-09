package services.kafka

import java.util

import com.impactua.bouncer.commons.utils.Logging
import com.impactua.bouncer.commons.utils.RichJson._
import com.impactua.bouncer.commons.utils.RichRequest._
import com.impactua.bouncer.commons.utils.{JsonHelper, Logging}
import com.impactua.kafka.serialization.KafkaWriter
import events._
import models.AppEvent._
import models._
import org.apache.kafka.common.serialization.Deserializer
import org.slf4j.LoggerFactory
import play.api.libs.json.{JsObject, JsValue, Json, OWrites}
import play.api.mvc.RequestHeader
import utils.Settings._

import scala.compat.Platform
import scala.language.implicitConversions
import scala.util.Try

class EventReader extends Deserializer[Option[AppEvent]] with Logging {

  override def configure(configs: util.Map[String, _], isKey: Boolean): Unit = {}
  override def close(): Unit = {}

  override def deserialize(topic: String, data: Array[Byte]): Option[AppEvent] = {
    Try(Json.parse(data)).recover {
      case ex =>
        log.error(s"Problem deserializing data ${new String(data)}", ex)
      throw ex
    }.toOption.flatMap(fromJson)
  }

  def fromJson(json: JsValue): Option[AppEvent] = {
    try {

      (json \ "operation").as[String] match {
        case "user_update" =>
          val user = (json \ "user").as[User]
          Some(new UserUpdate(user, null, null) with ExternalEvent)
        case "user_delete" =>
          val user = (json \ "user").as[User]
          Some(new UserDelete(user, None, null, null) with ExternalEvent)
        case "pass_changing" =>
          val user = (json \ "user").as[User]
          Some(new PasswordChange(user, null, null) with ExternalEvent)
        case "pass_ttl_changing" =>
          val user = (json \ "user").as[User]
          Some(new PasswordTTLChange(user, null, null) with ExternalEvent)
        case "login_confirmation" =>
          val user = (json \ "user").as[User]
          Some(new LoginConfirmation(user, null, null) with ExternalEvent)
        case "without_pass_confirmation" =>
          val user = (json \ "user").as[User]
          Some(new WithoutPassConfirmation(user, null, null) with ExternalEvent)
        case _ =>
          log.debug("Got unsupported event:" + json)
          None
      }
    } catch {
      case ex: Throwable =>
        log.error("Error occurred on event deserialization " + json, ex)
        None
    }
  }
}

class EventWriter[T <: BaseAppEvent] extends KafkaWriter[T] {

  val log = LoggerFactory.getLogger("event_writer")

  implicit val serviceWriter = new OWrites[Service] {
    override def writes(o: Service): JsObject = Json.obj("name" -> o.name, "pattern" -> o.pattern, "basePath" -> o.basePath)
  }

  implicit val userWriter = User.oWriter

  private implicit def userOpt(user: User): Option[User] = Option(user)

  private implicit def stringOpt(str: String): Option[String] = Option(str)

  override def key(topic: String, event: T): String = event.key

  override def serialize(topic: String, data: T): Array[Byte] = {
    val json = asJson(data)
    val jsonStr = json.toString()
    if (log.isTraceEnabled) log.trace(s"KAFKA [${jsonStr.length} bytes] >>>  $jsonStr")
    Json.toBytes(json)
  }

  def asJson(o: BaseAppEvent): JsValue = {
    asEventJson(o)
  }

  private def asEventJson = userProfileEvent orElse authEvent orElse confirmationEvent orElse appEvent orElse
    oauthEvent orElse referralEvent orElse inviteEvent orElse branchEvent

  private def referralEvent: PartialFunction[AppEvent, JsObject] = {
    case e: ReferralRegistrationEvent =>
      asBaseJson(e.user.uuidStr, e.name, user = e.user) ++ Json.obj("inviter" -> e.inviter)
  }

  private def inviteEvent: PartialFunction[AppEvent, JsObject] = {
    case e: UserInvitation =>
      asBaseJson(e.user.uuidStr, e.name, user = e.user) ++ Json.obj("url" -> e.url, "email" -> e.email)
  }

  private def userProfileEvent: PartialFunction[AppEvent, JsObject] = {
    case changePassEv: PasswordChange =>
      asBaseJson(changePassEv.user.uuidStr, changePassEv.name, Some(changePassEv.user.passHash), req = Some(changePassEv.request), user = changePassEv.user)

    case passResetEv: PasswordReset =>
      asBaseJson(passResetEv.user.uuidStr, passResetEv.name, req = Some(passResetEv.request), user = passResetEv.user) ++ Json.obj("code" -> passResetEv.code)

    case updEvent: UserUpdate =>
      asBaseJson(updEvent.user.uuidStr, updEvent.name, req = Some(updEvent.request), user = updEvent.user)

    case passTTLEvent: PasswordTTLChange =>
      asBaseJson(passTTLEvent.user.uuidStr, passTTLEvent.name, req = Some(passTTLEvent.request), user = passTTLEvent.user)

    case blockedEvent@UserBlocked(user, request, _) =>
      asBaseJson(user.uuidStr, blockedEvent.name, req = Some(request), user = user)

    case unblockedEvent@UserUnblocked(user, request, _) =>
      asBaseJson(user.uuidStr, unblockedEvent.name, req = Some(request), user = user)

    case noPassConfEvent: WithoutPassConfirmation =>
      asBaseJson(noPassConfEvent.user.uuidStr, noPassConfEvent.name, req = Some(noPassConfEvent.request), user = noPassConfEvent.user)

    case userDelete: UserDelete => asBaseJson(
      userDelete.user.uuidStr, userDelete.name, req = Some(userDelete.request), user = userDelete.user
    ) ++ userDelete.comment.map(c => Json.obj("comment" -> c)).getOrElse(JsObject(Nil))
  }

  private def authEvent: PartialFunction[AppEvent, JsObject] = {
    case loginEvent: Login =>
      asBaseJson(loginEvent.userId, loginEvent.name, Some(loginEvent.token), req = Some(loginEvent.request)) ++ Json.obj("sessionId" -> loginEvent.sessionId)

    case logoutEv: Logout =>
      asBaseJson(logoutEv.user.uuidStr, logoutEv.name, Some(logoutEv.token), req = Some(logoutEv.request))

    case signUpEv: Signup =>
      asBaseJson(signUpEv.user.uuidStr, signUpEv.name, req = Some(signUpEv.request), user = signUpEv.user)
  }

  private def confirmationEvent: PartialFunction[AppEvent, JsObject] = {
    case otpEv@OtpGeneration(userId, email, phone, code, request) =>
      asBaseJson(userId, otpEv.name, req = Some(request), user = Some(User(email = email, phone = phone, passHash = null))) ++ Json.obj("code" -> code)

    case loginConfirmationEv: LoginConfirmation =>
      asBaseJson(loginConfirmationEv.user.uuidStr, loginConfirmationEv.name, req = Some(loginConfirmationEv.request), user = loginConfirmationEv.user)
  }

  private def appEvent: PartialFunction[AppEvent, JsObject] = {
    case appE: ApplicationCreated =>
      asBaseJson(appE.userId, appE.name, req = Some(appE.request)) ++ Json.obj(TAG_APPLICATION_ID -> appE.app.id)

    case appE: ApplicationUpdated =>
      asBaseJson(appE.userId, appE.name, req = Some(appE.request)) ++ Json.obj(TAG_APPLICATION_ID -> appE.app.id)

    case appE: ApplicationRemoved =>
      asBaseJson(appE.userId, appE.name, req = Some(appE.request)) ++ Json.obj(TAG_APPLICATION_ID -> appE.appId)
  }

  private def oauthEvent: PartialFunction[AppEvent, JsObject] = {
    case oauthE: OauthTokenCreated =>
      asBaseJson(oauthE.userId, oauthE.name, req = Some(oauthE.request)) ++ Json.obj(TAG_OAUTH_ID -> oauthE.oauthId)

    case oauthE: OauthTokenUpdated =>
      asBaseJson(oauthE.userId, oauthE.name, req = Some(oauthE.request)) ++ Json.obj(TAG_OAUTH_ID -> oauthE.oauthId)

    case oauthE: OauthTokenRemoved =>
      asBaseJson(oauthE.userId, oauthE.name, req = Some(oauthE.request)) ++ Json.obj(TAG_OAUTH_ID -> oauthE.oauthId)
  }

  /* No need to write Discovery events
  private def etcdEvent: PartialFunction[AppEvent, JsObject] = {
    case discovered: ServiceDiscovered =>
      Json.obj(
        "timestamp" -> Platform.currentTime,
        "operation" -> discovered.name
      ) ++ Json.obj("service" -> discovered.service)

    case lost: ServiceLost =>
      Json.obj(
        "timestamp" -> Platform.currentTime,
        "operation" -> lost.name
      ) ++ Json.obj("service" -> lost.service)

    case update: ServicesListUpdate =>
      Json.obj(
        "timestamp" -> Platform.currentTime,
        "operation" -> update.name
      ) ++ Json.obj("services" -> update.services)
  }
  */

  private def branchEvent: PartialFunction[AppEvent, JsObject] = {
    case branchE: BranchCreated =>
      asBaseJson(branchE.userId, branchE.name, req = Some(branchE.request)) ++ Json.obj("branchId" -> branchE.branch.id)

    case branchE: BranchUpdated =>
      asBaseJson(branchE.userId, branchE.name, req = Some(branchE.request)) ++ Json.obj("branchId" -> branchE.branch.id)

    case branchE: BranchRemoved =>
      asBaseJson(branchE.userId, branchE.name, req = Some(branchE.request)) ++ Json.obj("branchId" -> branchE.branchId)
  }

  def asBaseJson(userId: Option[String],
                 operation: String,
                 value: Option[String] = None,
                 req: Option[RequestHeader] = None,
                 user: Option[User] = None
                ): JsObject = {

    Json.obj(
      "userId" -> userId,
      "timestamp" -> Platform.currentTime,
      "operation" -> operation,
      "value" -> value,
      "ip" -> req.map(_.clientIp),
      "userAgent" -> req.map(_.clientAgent),
      "user" -> user.map(Json.toJson(_))
    ).filterNull
  }


}

