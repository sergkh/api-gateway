package models.auth

import com.mohiva.play.silhouette.api.LoginInfo
import com.mohiva.play.silhouette.impl.providers.{OAuth1Info, OAuth2Info}
import play.api.libs.functional.syntax._
import play.api.libs.json._

case class SocialOAuth1(loginInfo: LoginInfo, auth: OAuth1Info)
case class SocialOAuth2(loginInfo: LoginInfo, auth: OAuth2Info)

case class UserAuthInfo(uuid: String, auth1Info: List[SocialOAuth1] = Nil, auth2Info: List[SocialOAuth2] = Nil)

object UserAuthInfo {
  final val COLLECTION_NAME = "auth"
  final val OAUTH1 = "auth1Info"
  final val OAUTH2 = "auth2Info"
  
  implicit val loginFmt: OFormat[LoginInfo] = Json.format[LoginInfo]
  implicit val auth1fmt: OFormat[OAuth1Info] = Json.format[OAuth1Info]
  implicit val auth2fmt: OFormat[OAuth2Info] = Json.format[OAuth2Info]
  implicit val social1Fmt: OFormat[SocialOAuth1] = Json.format[SocialOAuth1]
  implicit val social2Fmt: OFormat[SocialOAuth2] = Json.format[SocialOAuth2]


  implicit val reads: Reads[UserAuthInfo] = (
      (JsPath \ "_id").read[String] and
      (JsPath \ OAUTH1).read[List[SocialOAuth1]] and
      (JsPath \ OAUTH2).read[List[SocialOAuth2]]
    )(UserAuthInfo.apply _)

  implicit val oWrites: OWrites[UserAuthInfo] = new OWrites[UserAuthInfo] {
    override def writes(o: UserAuthInfo): JsObject = {
      Json.obj(
        "_id" -> o.uuid,
        OAUTH1 -> o.auth1Info,
        OAUTH2 -> o.auth2Info
      )
    }
  }

}
