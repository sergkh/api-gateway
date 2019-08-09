package services.social.parsers

import com.mohiva.play.silhouette.api.LoginInfo
import com.mohiva.play.silhouette.impl.providers.oauth2.VKProvider
import com.mohiva.play.silhouette.impl.providers.{OAuth2Info, SocialProfileParser}
import play.api.libs.json.JsValue
import services.social.CustomSocialProfile

import scala.concurrent.Future

/**
  * Created by faiaz on 08.05.17.
  */
class CustomVKProfileParser extends SocialProfileParser[JsValue, CustomSocialProfile, OAuth2Info] {

  override def parse(content: JsValue, authInfo: OAuth2Info): Future[CustomSocialProfile] = Future.successful {
    val response = (content \ "response").apply(0)
    val userID = (response \ "uid").as[Long]
    val firstName = (response \ "first_name").asOpt[String]
    val lastName = (response \ "last_name").asOpt[String]
    val fullName = (response \ "name").asOpt[String]
    val avatarURL = (response \ "picture" \ "data" \ "url").asOpt[String]
    val phone = (response \ "phone").asOpt[String]
    val email = authInfo.params.flatMap(_.get("email"))

    CustomSocialProfile(
      loginInfo = LoginInfo(VKProvider.ID, userID.toString),
      firstName = firstName,
      lastName = lastName,
      fullName = fullName,
      avatarURL = avatarURL,
      email = email,
      phone = phone)
  }
}
