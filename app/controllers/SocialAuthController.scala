package controllers

//scalastyle:off public.methods.have.type

import services.{ ClientAppsService, TokensService, UserService}
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
import utils.RichJson._
import utils.JwtExtension._
import utils.FutureUtils._

import scala.concurrent.{ExecutionContext, Future}

import forms.OAuthForm.AuthorizeUsingProvider
import play.api.mvc.Flash
import play.api.mvc.RequestHeader
import forms.OAuthForm.ResponseType
import play.api.mvc.Result
import java.net.URLEncoder
import java.time.LocalDateTime
import java.time.ZoneOffset
import models.RefreshToken
import utils.RandomStringGenerator
import com.mohiva.play.silhouette.impl.authenticators.JWTAuthenticator

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

    val RefreshTokenTTL = 365 * 24

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
          _             <- conditionalFail(!authReq.redirectUri.forall(app.matchRedirect), ErrorCodes.ACCESS_DENIED, "Invalid redirect URL")
          profile       <- p.retrieveProfile(authInfo)
          user          <- initUser(request.identity, profile, authInfo)
          _             <- conditionalFail(user.hasAllPermission(authReq.scopesList.filterNot(_ == "offline_access") :_*), ErrorCodes.ACCESS_DENIED, "Invalid redirect URL")
          result        <- authReq.responseType match {
            case ResponseType.Code =>
              respondWithCode(profile, user, authInfo, authReq)
            case ResponseType.Token => 
              respondWithAccessToken(profile, user, authInfo, authReq)

          }
        } yield result
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

  private def respondWithAccessToken(profile: CommonSocialProfile, user: User, authInfo: AuthInfo, authReq: AuthorizeUsingProvider)
                                    (implicit request: RequestHeader): Future[Result] = {

    import URLEncoder._
    // TODO: add scopes and audience
    for {
      _                 <- authInfoRepository.save(profile.loginInfo, authInfo)
      authenticator     <- silh.env.authenticatorService.create(profile.loginInfo)
      userAuthenticator  = authenticator.withUserInfo(user, authReq.scope, authReq.audience)
      token             <- silh.env.authenticatorService.init(userAuthenticator)
      refreshTokenOpt   <- optRefreshToken(user, authReq)
    } yield {
        silh.env.eventBus.publish(LoginEvent(user, request))

        log.info(s"User $user access token created")

        val expireIn = ((authenticator.expirationDateTime.toInstant.getMillis / 1000) - 
            LocalDateTime.now().toInstant(ZoneOffset.UTC).getEpochSecond())

        authReq.redirectUri.map { uri =>
          val accessToken = s"access_token=${encode(token, "UTF-8")}"
          val state = authReq.state.map(s => s"&state=$s").getOrElse("")
          val scope = authReq.scope.map(s => "&scope=" + encode(s, "UTF-8")) 
          var refreshToken = refreshTokenOpt.map(t => s"&refresh_token=${t.id}").getOrElse("")  
          val fullUri = uri + s"?$accessToken&expires_in=${expireIn}$scope&token_type=Bearer$refreshToken$state"
          
          Redirect(fullUri)
        }  getOrElse {

          val json = Json.obj(
            "token_type" -> "Bearer",
            "refresh_token" -> refreshTokenOpt.map(_.id),
            "access_token" -> token,
            "expires_in" -> expireIn,
            "scope" -> authReq.scope
          ).filterNull

          Ok(json)
        }      
    }
  }

  private def optRefreshToken(user: User, authReq: AuthorizeUsingProvider): Future[Option[RefreshToken]] = 
    if (authReq.scope.exists(_.split(" ").contains("offline_access"))) newRefreshToken(user, authReq).map(Some(_))
    else Future.successful(None)

  private def newRefreshToken(user: User, authReq: AuthorizeUsingProvider): Future[RefreshToken] = {
    val refreshToken = RefreshToken(
      user.id, 
      authReq.scope, 
      expirationTime = LocalDateTime.now().plusHours(RefreshTokenTTL),
      clientId = authReq.clientId,
      id = RandomStringGenerator.generateSecret(64)
    )

    tokens.store(refreshToken)
  }

  private def respondWithCode(profile: CommonSocialProfile, user: User, authInfo: AuthInfo, authReq: AuthorizeUsingProvider)
                                    (implicit request: RequestHeader): Future[Result] = {
    // TODO: code response
    ???
  }  

}
