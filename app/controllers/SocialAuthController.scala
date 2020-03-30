package controllers

//scalastyle:off public.methods.have.type

import services.{AuthCodesService, ClientAppsService, TokensService, UserService}
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
import play.api.mvc.RequestHeader
import forms.OAuthForm.ResponseType
import play.api.mvc.Result
import java.net.URLEncoder
import java.time.LocalDateTime
import java.time.ZoneOffset

import utils.RandomStringGenerator
import models.AuthCode
import play.api.Configuration

import scala.concurrent.duration.FiniteDuration
import events.EventsStream
import models.AppEvent.Login


/**
  * The social auth controller.
  *
  * @param userService The user service implementation.
  * @param authInfoRepository The auth info service implementation.
  * @param socialProviderRegistry The social provider registry.
  */
class SocialAuthController @Inject()(
                                      silh: Silhouette[JwtEnv],
                                      conf: Configuration,
                                      userService: UserService,
                                      oauth: ClientAppsService,
                                      tokens: TokensService,
                                      authCodes: AuthCodesService,
                                      authInfoRepository: AuthInfoRepository,
                                      eventBus: EventsStream,
                                      socialProviderRegistry: SocialProviderRegistry)(implicit ec: ExecutionContext)
  extends BaseController with I18nSupport {

  val AuthCodeTTL = conf.get[FiniteDuration]("oauth.authCodeTTL")
  val ImplicitFlowEnabled = conf.get[Boolean]("oauth.implicitFlow")

  /**
    * Authenticates a user using a social provider.
    *
    * @param provider The ID of the provider to authenticate against.
    * @return The result to display.
    */
  def authenticate(provider: String) = silh.UserAwareAction.async { implicit request =>
    // store original requests between redirects
    val authReq = AuthorizeUsingProvider.fromFlash(request.flash).getOrElse(request.asForm(OAuthForm.authorize))

    if (!ImplicitFlowEnabled && authReq.implicitFlow) {
      error(authReq, "unauthorized_client", "Implicit flow in not allowed")
    } else {
      log.info(s"Starting auth using $provider, req: $authReq, query = ${request.queryString}")
      // TODO: validate scopes and audience

      socialProviderRegistry.get[SocialProvider](provider) match {
        case Some(p: SocialProvider with CommonSocialProfileBuilder) =>
          socialAuth(provider, authReq, p).recoverWith {
            // transform errors into redirects
            case AppException(ErrorCodes.ACCESS_DENIED, msg) => error(authReq, "access_denied", msg)
            case AppException(ErrorCodes.AUTHORIZATION_FAILED, msg) => error(authReq, "access_denied", msg)
            case appException: AppException => error(authReq, "server_error", appException.message)
          }
        case _ =>
          log.warn(s"Cannot authenticate with unknown social provider $provider")
          error(authReq, "invalid_request", s"Unsupported authentication provider $provider")
      }
    }
  }

  private def socialAuth(providerName: String,
                         authReq: AuthorizeUsingProvider,
                         p: SocialProvider with CommonSocialProfileBuilder)(implicit request: UserAwareRequest[JwtEnv, AnyContent]) = {
    p.authenticate() flatMap {
      case Left(result) =>
        log.info(s"Social auth redirect: $result")
        Future.successful(result.flashing(authReq.flash))
      case Right(authInfo) =>
        for {
          app           <- oauth.getApp(authReq.clientId)
          _             <- conditionalFail(!authReq.redirectUri.forall(uri => app.matchRedirect(uri.toString())), ErrorCodes.ACCESS_DENIED, "Invalid redirect URL")
          profile       <- p.retrieveProfile(authInfo)
          user          <- initUser(request.identity, profile, authInfo)
          _             <- conditionalFail(!user.hasAllPermission(authReq.scopesList :_*), ErrorCodes.ACCESS_DENIED, "Invalid scopes")
          result        <- authReq.responseType match {
            case ResponseType.Code =>
              respondWithCode(profile, user, authInfo, authReq)
            case ResponseType.Token =>
              respondWithAccessToken(profile, user, authInfo, authReq)
          }
        } yield result
    } recover {
      case ex: ProviderException =>
        log.warn(s"Oauth provider $providerName exception: ${ex.getMessage}", ex.getCause)
        throw AppException(ErrorCodes.ACCESS_DENIED, "Invalid credentials")
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

    for {
      _                 <- authInfoRepository.save(profile.loginInfo, authInfo)
      authenticator     <- silh.env.authenticatorService.create(profile.loginInfo)
      userAuthenticator  = authenticator.withUserInfo(user, authReq.scope, authReq.audience)
      token             <- silh.env.authenticatorService.init(userAuthenticator)
      _                 <- eventBus.publish(Login(user.id, token, request, authenticator.id, authenticator.expirationDateTime.getMillis))
    } yield {
        log.info(s"User $user access token created")

        val expireIn = ((authenticator.expirationDateTime.toInstant.getMillis / 1000) -
            LocalDateTime.now().toInstant(ZoneOffset.UTC).getEpochSecond)

        authReq.redirectUri.map { uri =>
          val query = ("token_type" -> "Bearer") +: ("access_token" -> token) +:  ("expires_in" -> expireIn.toString) +: uri.query()
          val queryWithState = authReq.state.map(s => ("state" -> s) +: query).getOrElse(query)
          val fullUri = uri.withQuery(queryWithState)

          Redirect(fullUri.toString())
        }  getOrElse {

          val json = Json.obj(
            "token_type" -> "Bearer",
            "access_token" -> token,
            "expires_in" -> expireIn,
            "scope" -> authReq.scope,
            "state" -> authReq.state
          ).filterNull

          Ok(json)
        }
    }
  }

  private def respondWithCode(profile: CommonSocialProfile, user: User, authInfo: AuthInfo, authReq: AuthorizeUsingProvider)
                                    (implicit request: RequestHeader): Future[Result] = {
    val code = AuthCode(
      user.id,
      authReq.scope,
      expirationTime = LocalDateTime.now().plusNanos(AuthCodeTTL.toNanos),
      clientId = authReq.clientId,
      id = RandomStringGenerator.generateSecret(64)
    )

    authCodes.store(code) map { _ =>
      log.info(s"User $user auth code created")

      authReq.redirectUri.map { uri =>
        val query = ("code" -> code.id) +: uri.query()
        val queryWithState = authReq.state.map(s => ("state" -> s) +: query).getOrElse(query)
        val fullUri = uri.withQuery(queryWithState)

        Redirect(fullUri.toString())
      }  getOrElse {

        val json = Json.obj(
          "code" -> code.id,
          "state" -> authReq.state,
          "scope" -> authReq.scope
        ).filterNull

        Ok(json)
      }
    }
  }

  /**
   * Allowed error codes (see: https://tools.ietf.org/html/rfc6749#section-4.2.2.1)
   * * invalid_request
   * * unauthorized_client
   * * access_denied
   * * unsupported_response_type
   * * invalid_scope
   * * server_error
   * * temporarily_unavailable
   */
  private def error(authReq: AuthorizeUsingProvider, code: String, msg: String): Future[Result] = {
    authReq.redirectUri.map { uri =>
      val query = ("error" -> code) +: ("description" -> msg) +: uri.query()
      val queryWithState = authReq.state.map(s => ("state" -> s) +: query).getOrElse(query)
      Future.successful(Redirect(uri.withQuery(queryWithState).toString))
    } getOrElse {
      Future.failed(AppException(code, msg))
    }
  }

}
