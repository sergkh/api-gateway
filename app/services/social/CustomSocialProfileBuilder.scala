package services.social

import com.mohiva.play.silhouette.impl.providers.SocialProfileBuilder

/**
  * Created by faiaz on 05.05.17.
  */
trait CustomSocialProfileBuilder {
  self: SocialProfileBuilder =>

  /**
    * The type of the profile a profile builder is responsible for.
    */
  type Profile = CustomSocialProfile
}