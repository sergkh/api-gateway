package controllers

//scalastyle:off public.methods.have.type

import _root_.services.UserService
import com.mohiva.play.silhouette.api._
import com.mohiva.play.silhouette.api.actions.UserAwareRequest
import com.mohiva.play.silhouette.api.exceptions.ProviderException
import com.mohiva.play.silhouette.api.repositories.AuthInfoRepository
import com.mohiva.play.silhouette.impl.providers._
import forms.OAuthForm
import javax.inject.Inject
import models.{AppException, ErrorCodes, JwtEnv, User}
import play.api.i18n.I18nSupport
import play.api.libs.json.Json
import play.api.mvc.AnyContent
import utils.RichRequest._

import scala.concurrent.{ExecutionContext, Future}
import _root_.services.ClientAppsService
import _root_.services.TokensService
import forms.OAuthForm.AuthorizeUsingProvider
import play.api.mvc.Flash

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
                                      oauth: ClientAppsService,
                                      tokens: TokensService,
                                      authInfoRepository: AuthInfoRepository,
                                      socialProviderRegistry: SocialProviderRegistry)(implicit ec: ExecutionContext)
  extends BaseController with I18nSupport {

  // TODO: old verison
  // def authorize = silh.SecuredAction(NotOauth).async { implicit request =>
  //   val authReq = request.asForm(ClientAppForm.authorize)

  //   oauthService.authorize(authReq, request.identity, silh.env.authenticatorService).map { tokenResp =>
  //     Ok(tokenResp)
  //   }
  // }

  /**
    * Authenticates a user using a social provider.
    *
    * @param provider The ID of the provider to authenticate against.
    * @return The result to display.
    */
  def authenticate(provider: String) = silh.UserAwareAction.async { implicit request =>
    // store original requests between redirects
    val authReq = AuthorizeUsingProvider.fromFlash(request.flash).getOrElse(request.asForm(OAuthForm.authorize))

    // TODO: Add response_type Use code for server side flows and token for application side flows

    log.info(s"Starting auth using $provider, req: $authReq, query = ${request.queryString}")

    socialProviderRegistry.get[SocialProvider](provider) match {
      case Some(p: SocialProvider with CommonSocialProfileBuilder) =>
        socialAuth(provider, authReq, p)        
      case _ => 
        log.warn(s"Cannot authenticate with unexpected social provider $provider")
        Future.failed(throw AppException(ErrorCodes.AUTHORIZATION_FAILED, s"Unsupported authentication provider $provider") )
    }    
  }

  private def socialAuth(provierName: String, 
                         authReq: AuthorizeUsingProvider,                       
                         p: SocialProvider with CommonSocialProfileBuilder)(implicit request: UserAwareRequest[JwtEnv, AnyContent]) = {
    p.authenticate() flatMap {
      case Left(result) => 
        log.info(s"Social auth redirect: $result")
        Future.successful(result.flashing(authReq.flash))
      case Right(authInfo) => 

        for {
          app           <- oauth.getApp(authReq.clientId)
          profile       <- p.retrieveProfile(authInfo)
          user          <- initUser(request.identity, profile, authInfo)
          _             <- authInfoRepository.save(profile.loginInfo, authInfo)
          authenticator <- silh.env.authenticatorService.create(profile.loginInfo)
          value         <- silh.env.authenticatorService.init(authenticator)
        } yield {
          silh.env.eventBus.publish(LoginEvent(user, request))

          log.info(s"User $user authenticated though $provierName")
        
          Ok(
            Json.obj("token" -> value)
          )
        }
    } recover {
      case ex: ProviderException =>
        log.warn("Oauth provider exception:" + ex.getMessage, ex.getCause)
        throw AppException(ErrorCodes.AUTHORIZATION_FAILED, "Invalid credentials")
    }
  }

  private def initUser(userOpt: Option[User], profile: CommonSocialProfile, authInfo: AuthInfo): Future[User] = {
    userOpt match {
      case Some(user) => 
        // User is logged in and adding a social profile, update his info
        userService.updateUserBySocialProfile(user, profile, authInfo)
      case None =>
        // User is new or not logged in. Find/create a user with profile info
        userService.findOrCreateSocialUser(profile, authInfo)
    }
  }
}
