package kafka

import zio.kafka.serde._
import events._
import play.api.libs.json._
import models.User
import utils.RichJson._
import org.apache.kafka.common.header.Headers
import zio.RIO
import zio.IO
import models.Service

object JsonSerde {
  implicit val userWriter = new OWrites[User] {
    def writes(u: User): JsObject = Json.obj(
      "id" -> u.id,
      "email" -> u.email,
      "phone" -> u.phone,
      "roles" -> Option(u.roles).filterNot(_.isEmpty),
      "branch" -> u.branch,
      "firstName" -> u.firstName,
      "lastName" -> u.lastName
    ).filterNull
  }

  implicit val serviceWriter = new OWrites[Service] {
    def writes(s: Service): JsObject = Json.obj("name" -> s.name)
  }

  implicit val requestInfoJson = Json.writes[RequestInfo] 
  implicit val signupJson = Json.writes[Signup]
  implicit val loginJson = Json.writes[Login]
  implicit val logoutJson = Json.writes[Logout]
  implicit val otpGeneratedJson = Json.writes[OtpGenerated]
  implicit val passwordChangedJson = Json.writes[PasswordChanged]
  implicit val userBlockedJson = Json.writes[UserBlocked]
  implicit val userUnblockedJson = Json.writes[UserUnblocked]
  implicit val userUpdatedJson = Json.writes[UserUpdated]
  implicit val userRemovedJson = Json.writes[UserRemoved]
  implicit val passwordResetJson = Json.writes[PasswordReset]
  implicit val roleCreatedJson = Json.writes[RoleCreated]
  implicit val roleUpdatedJson = Json.writes[RoleUpdated]
  implicit val roleRemovedJson = Json.writes[RoleRemoved]
  implicit val clientCreatedJson = Json.writes[ClientCreated]
  implicit val clientUpdatedJson = Json.writes[ClientUpdated]
  implicit val clientRemovedJson = Json.writes[ClientRemoved]
  implicit val serviceDiscoveredJson   = Json.writes[ServiceDiscovered]
  implicit val serviceLostJson         = Json.writes[ServiceLost]
  implicit val servicesListUpdateJson  = Json.writes[ServicesListUpdate]

  implicit val branchCreatedJson = Json.writes[BranchCreated]
  implicit val branchUpdatedJson = Json.writes[BranchUpdated]
  implicit val branchRemovedJson = Json.writes[BranchRemoved] 

  implicit val userEventJson = new OWrites[Event] {
    def writes(o: Event): JsObject = {
      val mainJson = o match {
        case e: Signup => Json.toJsObject(e)
        case e: Login => Json.toJsObject(e)
        case e: Logout => Json.toJsObject(e)
        case e: OtpGenerated => Json.toJsObject(e)
        case e: PasswordChanged => Json.toJsObject(e)
        case e: UserBlocked => Json.toJsObject(e)
        case e: UserUnblocked => Json.toJsObject(e)
        case e: UserUpdated => Json.toJsObject(e)
        case e: UserRemoved => Json.toJsObject(e)
        case e: PasswordReset => Json.toJsObject(e)
        case e: RoleCreated => Json.toJsObject(e)
        case e: RoleUpdated => Json.toJsObject(e)
        case e: RoleRemoved => Json.toJsObject(e)
        case e: ServiceDiscovered => Json.toJsObject(e)
        case e: ServiceLost => Json.toJsObject(e)
        case e: ServicesListUpdate => Json.toJsObject(e)
        case e: ClientCreated => Json.toJsObject(e)
        case e: ClientUpdated => Json.toJsObject(e)
        case e: ClientRemoved => Json.toJsObject(e)
        case e: BranchCreated => Json.toJsObject(e)
        case e: BranchUpdated => Json.toJsObject(e)
        case e: BranchRemoved => Json.toJsObject(e)
      }

      mainJson ++ Json.obj(
        "id" -> o.id,
        "stamp" -> o.stamp,
        "type" -> o.`type`
      )
    }
  }

  val eventJsonSerializer: Serializer[Any, Event] = new Serializer[Any, Event] {
     def serialize(topic: String, headers: Headers, evt: Event): RIO[Any, Array[Byte]] = IO(
      Json.toBytes(Json.toJson(evt))
    )
  }


}
