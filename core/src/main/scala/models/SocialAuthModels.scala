package models

import com.mohiva.play.silhouette.api.LoginInfo
import com.mohiva.play.silhouette.impl.providers.{OAuth1Info, OAuth2Info}
import org.mongodb.scala.bson.annotations.BsonProperty

case class SocialOAuth1(loginInfo: LoginInfo, auth: OAuth1Info)
case class SocialOAuth2(loginInfo: LoginInfo, auth: OAuth2Info)

case class UserAuthInfo(@BsonProperty("_id")  id: String, auth1Info: List[SocialOAuth1] = Nil, auth2Info: List[SocialOAuth2] = Nil)

object UserAuthInfo {
  final val COLLECTION_NAME = "auth"
  final val OAUTH1 = "auth1Info"
  final val OAUTH2 = "auth2Info"
}
