package controllers

//scalastyle:off public.methods.have.type

import javax.inject.Inject
import _root_.services.UserService
import _root_.services.social.{CustomSocialProfile, CustomSocialProfileBuilder}
import com.mohiva.play.silhouette.api._
import com.mohiva.play.silhouette.api.exceptions.ProviderException
import com.mohiva.play.silhouette.api.repositories.AuthInfoRepository
import com.mohiva.play.silhouette.impl.providers._
import models.{AppException, ErrorCodes, JwtEnv, User}
import play.api.i18n.I18nSupport
import play.api.libs.json.Json
import ErrorCodes._

import scala.concurrent.{ExecutionContext, Future}

/**
  * The social auth controller.
  *
  * @param userService The user service implementation.
  * @param authInfoRepository The auth info service implementation.
  * @param socialProviderRegistry The social provider registry.
  */

class SocialAuthController @Inject()(
                                      silh: Silhouette[JwtEnv],
                                      userService: UserService,
                                      authInfoRepository: AuthInfoRepository,
                                      socialProviderRegistry: SocialProviderRegistry)(implicit ec: ExecutionContext)
  extends BaseController with I18nSupport {

  /**
    * Authenticates a user against a social provider.
    *
    * @param provider The ID of the provider to authenticate against.
    * @return The result to display.
    */
  def authenticate(provider: String) = silh.UserAwareAction.async { implicit request =>
    (socialProviderRegistry.get[SocialProvider](provider) match {
      case Some(p: SocialProvider with CustomSocialProfileBuilder) =>
        p.authenticate() flatMap {
          case Left(result) => Future.successful(result)
          case Right(authInfo) => for {
              profile <- p.retrieveProfile(authInfo)
              user <- initUser(request.identity, profile, authInfo)
              _ <- authInfoRepository.save(profile.loginInfo, authInfo)
              authenticator <- silh.env.authenticatorService.create(profile.loginInfo)
              value <- silh.env.authenticatorService.init(authenticator)
              result <- Future.successful(Ok(Json.obj("token" -> value)))
            } yield {
              silh.env.eventBus.publish(LoginEvent(user, request))
              result
            }
        }
      case _ => Future.failed(new ProviderException(s"Cannot authenticate with unexpected social provider $provider"))
    }) recover {
      case ex: ProviderException =>
        log.warn("Oauth provider exception:" + ex.getMessage, ex.getCause)
        throw AppException(ErrorCodes.AUTHORIZATION_FAILED, "Invalid credentials")
    }
  }

  private def initUser(userOpt: Option[User], profile: CustomSocialProfile, authInfo: AuthInfo): Future[User] = {
    userOpt match {
      case Some(user) => userService.updateUserBySocialProfile(user, profile, authInfo)
      case None => userService.save(profile, authInfo)
    }
  }
}
