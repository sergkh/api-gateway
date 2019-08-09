package services.auth

import com.mohiva.play.silhouette.api.{AuthInfo, LoginInfo}
import com.mohiva.play.silhouette.impl.providers.{OAuth1Info, OAuth2Info}
import models.auth.UserAuthInfo._
import models.auth.{SocialOAuth1, SocialOAuth2, UserAuthInfo}
import play.api.libs.json.{JsObject, Json}
import reactivemongo.play.json._

/**
  * Created by faiaz on 05.05.17.
  */
trait AuthInfoServiceHelper {

  def searchQuery(uuid: Long): JsObject = Json.obj("_id" -> uuid)

  def updateQuery(loginInfo: LoginInfo, auth: AuthInfo): JsObject = {
    val jsValue = auth match {
      case oauth1: OAuth1Info => Json.obj(UserAuthInfo.OAUTH1 -> Json.toJson(SocialOAuth1(loginInfo, oauth1)))
      case oauth2: OAuth2Info => Json.obj(UserAuthInfo.OAUTH2 -> Json.toJson(SocialOAuth2(loginInfo, oauth2)))
    }
    Json.obj("$push" -> jsValue)
  }

  def saveQuery(uuid: Long, loginInfo: LoginInfo, auth: AuthInfo): JsObject = {
    auth match {
      case oauth1: OAuth1Info => Json.toJson(UserAuthInfo(uuid, auth1Info = List(SocialOAuth1(loginInfo, oauth1)))).as[JsObject]
      case oauth2: OAuth2Info => Json.toJson(UserAuthInfo(uuid, auth2Info = List(SocialOAuth2(loginInfo, oauth2)))).as[JsObject]
    }
  }

  def findByProviderKeyQuery(socialId: String, key: String): JsObject = Json.obj(key -> Json.obj("$elemMatch" -> Json.obj("loginInfo.providerKey" -> socialId)))

  def removeOneQuery(loginInfo: LoginInfo, authType: String): JsObject = Json.obj("$pull" -> Json.obj(authType -> Json.obj("loginInfo" -> loginInfo)))
}
