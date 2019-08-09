package services.social

import com.mohiva.play.silhouette.api.LoginInfo
import com.mohiva.play.silhouette.impl.providers.{CommonSocialProfile, SocialProfile}

/**
  * Created by faiaz on 05.05.17.
  */
case class CustomSocialProfile(loginInfo: LoginInfo,
                               firstName: Option[String] = None,
                               lastName: Option[String] = None,
                               fullName: Option[String] = None,
                               email: Option[String] = None,
                               phone: Option[String] = None,
                               avatarURL: Option[String] = None) extends SocialProfile

object CustomSocialProfile {

  def apply(profile: CommonSocialProfile, phone: Option[String]): CustomSocialProfile = {
    CustomSocialProfile(
      loginInfo = profile.loginInfo,
      firstName = profile.firstName,
      lastName = profile.lastName,
      fullName = profile.fullName,
      email = profile.email,
      phone = phone,
      avatarURL = profile.avatarURL
    )
  }
}
