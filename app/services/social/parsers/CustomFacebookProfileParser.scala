package services.social.parsers

import com.mohiva.play.silhouette.api.LoginInfo
import com.mohiva.play.silhouette.impl.providers.oauth2.FacebookProvider
import com.mohiva.play.silhouette.impl.providers.{OAuth2Info, SocialProfileParser}
import play.api.libs.json.JsValue
import services.social.CustomSocialProfile

import scala.concurrent.Future

/**
  * Created by faiaz on 05.05.17.
  */
class CustomFacebookProfileParser extends SocialProfileParser[JsValue, CustomSocialProfile, OAuth2Info] {
  override def parse(content: JsValue, authInfo: OAuth2Info): Future[CustomSocialProfile] = Future.successful {
    val userID = (content \ "id").as[String]
    val firstName = (content \ "first_name").asOpt[String]
    val lastName = (content \ "last_name").asOpt[String]
    val fullName = (content \ "name").asOpt[String]
    val avatarURL = (content \ "picture" \ "data" \ "url").asOpt[String]
    val email = (content \ "email").asOpt[String]
    val phone = (content \ "phone").asOpt[String]

    CustomSocialProfile(
      loginInfo = LoginInfo(FacebookProvider.ID, userID),
      firstName = firstName,
      lastName = lastName,
      fullName = fullName,
      avatarURL = avatarURL,
      email = email,
      phone = phone)
  }
}
