package services.dao

import com.google.inject.Inject
import com.mohiva.play.silhouette.impl.providers.OAuth2Info
import services.{SocialAuthService, UserService}

/**
  * Created by faiaz on 15.05.17.
  */
class SocialOAuth2Dao @Inject()(userService: UserService, authService: SocialAuthService) extends BaseSocialAuthDao[OAuth2Info](userService, authService)