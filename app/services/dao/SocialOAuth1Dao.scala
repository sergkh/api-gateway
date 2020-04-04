package services.dao

import com.google.inject.Inject
import com.mohiva.play.silhouette.impl.providers.OAuth1Info
import services.{SocialAuthService, UserService}

/**
  * Created by faiaz on 15.05.17.
  */
class SocialOAuth1Dao @Inject()(userService: UserService, authService: SocialAuthService) extends BaseSocialAuthDao[OAuth1Info](userService, authService)
