package controllers

//scalastyle:off public.methods.have.type

import java.time.{LocalDateTime, ZoneOffset}
import java.{util => ju}

import com.mohiva.play.silhouette.api.{LoginInfo, Silhouette}
import com.mohiva.play.silhouette.api.util.PasswordHasherRegistry
import com.mohiva.play.silhouette.impl.providers.CredentialsProvider
import events.EventsStream
import forms.OAuthForm
import javax.inject.{Inject, Singleton}
import models.AppEvent.Login
import models.ErrorCodes._
import models.{AppException, JwtEnv}
import play.api.libs.json.Json
import security.{KeysManager, WithUser}
import services.{AuthCodesService, ClientAppsService, TokensService, UserService}
import utils.FutureUtils._
import utils.RichRequest._
import utils.RichJson._
import utils.JwtExtension._

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import models.RefreshToken
import play.api.Configuration

import scala.concurrent.duration.FiniteDuration
import forms.OAuthForm.AccessTokenByRefreshToken
import models.User
import forms.OAuthForm.AccessTokenByPassword
import com.mohiva.play.silhouette.api.util.PasswordInfo
import forms.OAuthForm.AccessTokenByAuthorizationCode
import com.mohiva.play.silhouette.api.util.Credentials
import com.mohiva.play.silhouette.impl.exceptions.IdentityNotFoundException
import models.ErrorCodes
import com.mohiva.play.silhouette.impl.exceptions.InvalidPasswordException
import com.mohiva.play.silhouette.api.exceptions.ProviderException
import TokenController._

@Singleton
class TokenController @Inject()(silh: Silhouette[JwtEnv],
                                conf: Configuration,
                                oauth: ClientAppsService,
                                tokens: TokensService,
                                authCodes: AuthCodesService,
                                keyManager: KeysManager,
                                userService: UserService,
                                credentialsProvider: CredentialsProvider,
                                eventBus: EventsStream
                              )
                               (implicit exec: ExecutionContext) extends BaseController {

  val RefreshTokenTTL = conf.get[FiniteDuration]("oauth.refreshTokenTTL")

  def authCerts = Action { _ =>

    val keys = keyManager.authCertificates
    
    Ok(Json.obj(
      "keys" -> Json.arr(
        keys.map {
          case (kid, cert) => Json.obj("kid" -> kid, "x5c" -> Json.arr(
            ju.Base64.getEncoder.encodeToString(cert.getEncoded)
          ))
        }
      )
    ))
  }

  // OLD create token
  //   def createToken = oauth.Secured.async(parse.json) { implicit request =>
  //   val code = request.asForm(ClientAppForm.code)

  //   oauthService.createToken(code, silh.env.authenticatorService).map { tokenResp =>
  //     Ok(tokenResp)
  //   }
  // }

  /**
   * Requires client authorization, using basic auth.
   * Calling options:
   * Access token:
   *   grant_type=authorization_code&code=SplxlOBeZQQYbYS6WxSbIA&redirect_uri=https%3A%2F%2Fclient%2Eexample%2Ecom%2Fcb    
   * Refresh token: 
   *  grant_type=refresh_token&refresh_token=tGzv3JOkF0XG5Qx2TlKWIA&client_id=s6BhdRkqt3&client_secret=7Fjfp0ZBr1KtDRbnfVdmIw
   * Password:
   *   grant_type=password&username=johndoe&password=A3ddj3w
   * 
   * Since this client authentication method involves a password, the authorization server MUST protect any endpoint utilizing it against
   * brute force attacks.
   */
  def getAccessToken = Action.async { implicit request =>
    val grantType = request.asForm(OAuthForm.grantType)

    val (clientId, clientSecret) = request.basicAuth.getOrElse {
      throw new AppException(AUTHORIZATION_FAILED, "Client authorization failed")
    }
    
    log.info(s"Requesting access token for ${clientId}")

    val res = for {
      app             <- oauth.getApp(clientId)
      _               <- conditionalFail(app.secret != clientSecret, ACCESS_DENIED, "Wrong client secret")
      auth            <- grantType match {
        case "refresh_token" =>
          authorizeByRefreshToken(clientId, request.asForm(OAuthForm.getAccessTokenFromRefreshToken))
        case "password" =>
          authorizeByPassword(clientId, request.asForm(OAuthForm.getAccessTokenByPass))
        case "authorization_code" =>
          authorizeByAuthorizationCode(clientId, request.asForm(OAuthForm.getAccessTokenFromAuthCode))
      }
      authenticator   <- silh.env.authenticatorService.create(LoginInfo(CredentialsProvider.ID, auth.user.id))
      tokenWithUser   = authenticator.withUserInfo(auth.user, auth.scope)
      token           <- silh.env.authenticatorService.init(authenticator)
      _               <- eventBus.publish(Login(auth.user.id, token, request, authenticator.id, authenticator.expirationDateTime.getMillis))
    } yield {
      log.info(s"Succeed user authentication ${auth.user.id}")
              
      val expireIn = ((authenticator.expirationDateTime.toInstant.getMillis / 1000) -
                  LocalDateTime.now().toInstant(ZoneOffset.UTC).getEpochSecond())

      // TODO: handle redirect
      Ok(Json.obj(
        "token_type" -> "Bearer",
        "access_token" -> token,
        "expires_in" -> expireIn,
        "scope" -> auth.scope,
        "refresh_token" -> auth.refreshToken.map(_.id)
        // TODO: "id_token": "ID_token"
      ).filterNull)
    }

    res.recoverWith {
      case e: IdentityNotFoundException =>
        log.info(s"User is not found " + e.getMessage)
        Future.failed(AppException(ErrorCodes.AUTHORIZATION_FAILED, "Invalid credentials"))
      case e: InvalidPasswordException =>
        log.info(s"Wrong user password " + e.getMessage)
        Future.failed(AppException(ErrorCodes.AUTHORIZATION_FAILED, "Invalid credentials"))
      case e: ProviderException =>
        log.info(s"Invalid credentials " + e.getMessage)
        throw AppException(ErrorCodes.AUTHORIZATION_FAILED, "Invalid credentials")
    }
  }

  def listUserTokens(userId: String) = silh.SecuredAction(WithUser(userId)).async { implicit req =>
    tokens.list(userId).map { tokens =>
      log.info(s"Getting refresh tokens for user: ${userId}, by ${req.identity.id}, items: ${tokens.size}")

      Ok(Json.toJson(
        "items" -> tokens.map { t =>
          Json.obj(
            "userId" -> t.userId,
            "scope" -> t.scope,
            "expirationTime" -> t.expirationTime,
            "requestedTime" -> t.requestedTime,
            "clientId" -> t.clientId
          ).filterNull
        }
      ))
    }
  }

  private def authorizeByRefreshToken(clientId: String, req: AccessTokenByRefreshToken): Future[AuthResult] = for {
    refreshToken    <- tokens.get(req.refreshToken).orFail(AppException(AUTHORIZATION_FAILED, "Invalid refresh token"))
    _               <- if (refreshToken.expired) cleanExpiredTokenAndFail(refreshToken) else Future.unit
    _               <- conditionalFail(refreshToken.clientId != clientId, AUTHORIZATION_FAILED, "Wrong refresh token")
    loginInfo       = LoginInfo(CredentialsProvider.ID, refreshToken.userId)
    user            <- userService.retrieve(loginInfo).orFail(AppException(AUTHORIZATION_FAILED, "User authorization failed"))
  } yield {
    AuthResult(user, refreshToken.scope, None)
  }

  private def authorizeByPassword(clientId: String, req: AccessTokenByPassword): Future[AuthResult] = {
    for {
      loginInfo     <- credentialsProvider.authenticate(Credentials(req.username, req.password))
      user          <- userService.retrieve(loginInfo).orFail(AppException(AUTHORIZATION_FAILED, "User authorization failed"))
      _             <- conditionalFail(user.hasAllPermission(req.scopesList:_*), ACCESS_DENIED, "Wrong scopes")
      refreshToken  <- optIssueRefreshToken(user, req.scope, clientId)
    } yield {
      if (user.flags.contains(User.FLAG_2FACTOR)) { // TODO: support OTPs 
        throw new AppException(AUTHORIZATION_FAILED, "User authorization failed")
      }

      AuthResult(user, req.scope)
    }
  }

  private def authorizeByAuthorizationCode(clientId: String, req: AccessTokenByAuthorizationCode): Future[AuthResult] = for {
    authCode     <- authCodes.getAndRemove(req.authCode).map(_.filter(_.expired)).orFail(AppException(AUTHORIZATION_FAILED, "Invalid authorization code"))
    loginInfo    = LoginInfo(CredentialsProvider.ID, authCode.userId)
    user         <- userService.retrieve(loginInfo).orFail(AppException(AUTHORIZATION_FAILED, "User authorization failed"))
    refreshToken <- optIssueRefreshToken(user, authCode.scope, clientId)
  } yield {
    AuthResult(user, authCode.scope, refreshToken)
  }

  private def optIssueRefreshToken(user: User, scope: Option[String], clientId: String): Future[Option[RefreshToken]] = {
    if (scope.exists(_.split(" ").contains("offline_access"))) {
      tokens.store(RefreshToken(
        user.id, 
        scope, 
        LocalDateTime.now().plusSeconds(RefreshTokenTTL.toSeconds),
        clientId
      )).map(Some(_))
    } else {
      Future.successful(None)
    }
  }

  private def cleanExpiredTokenAndFail(t: RefreshToken): Future[Unit] = 
    tokens.delete(t.id).map { _ => throw AppException(AUTHORIZATION_FAILED, "Token expired") }

}

object TokenController {
  case class AuthResult(user: User, scope: Option[String] = None, refreshToken: Option[RefreshToken] = None)
}

  // private def processUserLogin(optUser: Option[User], loginInfo: LoginInfo,
  //                              credentials: LoginCredentials)(implicit request: RequestHeader): Future[Result] = optUser match {
  //   case Some(user) if user.hasFlag(User.FLAG_BLOCKED) =>
  //     log.warn(s"User ${credentials.login} is blocked")
  //     throw AppException(ErrorCodes.BLOCKED_USER, s"User ${credentials.login} is blocked")

  //   case Some(user) if credentials.password.nonEmpty && user.flags.contains(User.FLAG_2FACTOR) => // 2-factor authentication, password always required
  //     val (secret, code) = ConfirmationCode.generatePair(credentials.login, ConfirmationCode.OP_LOGIN, otpLength, None)

  //     confirmationService.create(code)

  //     eventBus.publish(OtpGeneration(Some(user.id), user.email, user.phone, secret, request)) map { _ =>
  //       log.info("Generated login code for " + user.id)
  //       throw AppException(ErrorCodes.CONFIRMATION_REQUIRED, "Otp confirmation required using POST /users/confirm")
  //     }

  //   case Some(user) if credentials.password.isEmpty && !user.flags.contains(User.FLAG_2FACTOR) => // passwordless authentication
  //     val (secret, code) = ConfirmationCode.generatePair(credentials.login, ConfirmationCode.OP_LOGIN, otpLength, None)
  //     confirmationService.create(code)

  //     eventBus.publish(OtpGeneration(Some(user.id), user.email, user.phone, secret, request)) map { _ =>
  //       log.info("Generated passwordless login code for " + user.id)
  //       throw AppException(ErrorCodes.CONFIRMATION_REQUIRED, "Otp confirmation required using POST /users/confirm")
  //     }

  //   case Some(user) =>
  //     val userIdLoginInfo = LoginInfo(CredentialsProvider.ID, user.id)
  //     silh.env.authenticatorService.create(userIdLoginInfo) flatMap { authenticator =>
  //       silh.env.authenticatorService.init(authenticator).flatMap { token =>
  //         log.info(s"Succeed user authentication ${userIdLoginInfo.providerKey}")

  //         eventBus.publish(Login(user.id, token, request, authenticator.id, authenticator.expirationDateTime.getMillis)).flatMap { _ =>
  //           silh.env.authenticatorService.embed(token, Ok(JsonHelper.toNonemptyJson("token" -> token)))
  //         }
  //       }
  //     }

  //   case _ =>
  //     log.warn(s"User with login: ${credentials.login} is not found")
  //     throw AppException(ErrorCodes.ENTITY_NOT_FOUND, s"User with login: ${credentials.login} is not found")
  // }

