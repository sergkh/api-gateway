package services.social.providers

import com.mohiva.play.silhouette.api.util.HTTPLayer
import com.mohiva.play.silhouette.impl.providers._
import com.mohiva.play.silhouette.impl.providers.oauth2.BaseFacebookProvider
import services.social.{CustomSocialProfile, CustomSocialProfileBuilder}
import services.social.parsers.CustomFacebookProfileParser

/**
  * Created by faiaz on 04.05.17.
  */
class CustomFacebookProvider(protected val httpLayer: HTTPLayer,
                             protected val stateHandler: SocialStateHandler,
                             val settings: OAuth2Settings)
  extends BaseFacebookProvider with CustomSocialProfileBuilder {

  override type Self = CustomFacebookProvider

  override type Profile = CustomSocialProfile

  override val profileParser = new CustomFacebookProfileParser

  override def withSettings(f: (Settings) => Settings) =
    new CustomFacebookProvider(httpLayer, stateHandler, f(settings))
}
