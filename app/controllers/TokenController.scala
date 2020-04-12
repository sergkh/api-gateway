package controllers

//scalastyle:off public.methods.have.type

import java.time.{LocalDateTime, ZoneOffset}
import java.{util => ju}

import com.mohiva.play.silhouette.api.exceptions.ProviderException
import com.mohiva.play.silhouette.api.util.Credentials
import com.mohiva.play.silhouette.api.{LoginInfo, Silhouette}
import com.mohiva.play.silhouette.impl.exceptions.{IdentityNotFoundException, InvalidPasswordException}
import com.mohiva.play.silhouette.impl.providers.CredentialsProvider
import controllers.TokenController._
import events.EventsStream
import forms.OAuthForm
import forms.OAuthForm.{AccessTokenByAuthorizationCode, AccessTokenByPassword, AccessTokenByRefreshToken}
import javax.inject.{Inject, Singleton}
import models.AppEvent.Login
import models.ErrorCodes._
import models._
import play.api.Configuration
import play.api.libs.json.Json
import security.{KeysManager, WithUser}
import services.{AuthCodesService, ClientAppsService, TokensService, UserService}
import utils.JwtExtension._
import utils.RichJson._
import utils.RichRequest._
import utils.TaskExt._
import zio._

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.FiniteDuration

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

  /**
   * Requires client authorization, using basic auth.
   * Since this client authentication method involves a password, the authorization server MUST protect any endpoint utilizing it against
   * brute force attacks.
   */
  def getAccessToken = Action.async { implicit request =>
    val grantType = request.asForm(OAuthForm.grantType)

    val (clientId, clientSecret) = request.basicAuth.getOrElse {
      log.warn("No client authentication provided")
      throw new AppException(AUTHORIZATION_FAILED, "Client authorization required")
    }
    
    log.info(s"Requesting access token for ${clientId}")

    val res = for {
      app             <- oauth.getApp(clientId)
      _               <- failIf(app.secret != clientSecret, ACCESS_DENIED, "Wrong client secret")
      auth            <- grantType match {
        case "refresh_token" =>
          authorizeByRefreshToken(clientId, request.asForm(OAuthForm.getAccessTokenFromRefreshToken))
        case "password" =>
          authorizeByPassword(clientId, request.asForm(OAuthForm.getAccessTokenByPass))
        case "authorization_code" =>
          authorizeByAuthorizationCode(clientId, request.asForm(OAuthForm.getAccessTokenFromAuthCode))
      }
      authenticator   <- Task.fromFuture(ec => silh.env.authenticatorService.create(LoginInfo(CredentialsProvider.ID, auth.user.id)))
      tokenWithUser   = authenticator.withUserInfo(auth.user, auth.scope)
      token           <- Task.fromFuture(ec => silh.env.authenticatorService.init(authenticator))
      _               <- eventBus.publish(Login(auth.user.id, token, request, authenticator.id, authenticator.expirationDateTime.getMillis))
    } yield {
      log.info(s"User ${auth.user.id} authenticated by $clientId ${auth.refreshToken.map(_ => " with refresh token").getOrElse("")} using $grantType with scopes: ${auth.scope}")
              
      val expireIn = ((authenticator.expirationDateTime.toInstant.getMillis / 1000) -
                  LocalDateTime.now().toInstant(ZoneOffset.UTC).getEpochSecond())

      // TODO: handle redirect
      Ok(Json.obj(
        "token_type" -> "Bearer",
        "access_token" -> token,
        "expires_in" -> expireIn,
        "scope" -> auth.scope,
        "refresh_token" -> auth.refreshToken
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

  private def authorizeByRefreshToken(clientId: String, req: AccessTokenByRefreshToken): Task[AuthResult] = for {
    refreshToken    <- tokens.get(req.refreshToken).orFail(AppException(AUTHORIZATION_FAILED, "Invalid refresh token"))
    _               <- if (refreshToken.expired) cleanExpiredTokenAndFail(refreshToken) else Task.unit
    _               <- failIf(refreshToken.clientId != clientId, AUTHORIZATION_FAILED, "Wrong refresh token")
    user            <- userService.getActiveUser(refreshToken.userId).orFail(AppException(AUTHORIZATION_FAILED, "User authorization failed"))
  } yield {
    AuthResult(user, refreshToken.scope, None)
  }

  private def authorizeByPassword(clientId: String, req: AccessTokenByPassword): Task[AuthResult] = {
    for {
      loginInfo     <- Task.fromFuture(ec => credentialsProvider.authenticate(Credentials(req.username, req.password)))
      user          <- Task.fromFuture(ec => userService.retrieve(loginInfo)).orFail(AppException(AUTHORIZATION_FAILED, "User authorization failed"))
      _             <- failIf(!user.hasAllPermission(req.scopesList:_*), ACCESS_DENIED, "Wrong scopes")
      refreshToken  <- optIssueRefreshToken(user, req.scopesList, clientId)
      _             <- failIf(user.flags.contains(User.FLAG_2FACTOR), AUTHORIZATION_FAILED, "User authorization failed") // TODO: support OTPs 
    } yield AuthResult(user, req.scope, refreshToken)
  }

  private def authorizeByAuthorizationCode(clientId: String, req: AccessTokenByAuthorizationCode): Task[AuthResult] = for {
    authCode     <- authCodes.getAndRemove(req.authCode).orFail(AppException(AUTHORIZATION_FAILED, "Invalid authorization code"))
    user         <- userService.getActiveUser(authCode.userId).orFail(AppException(AUTHORIZATION_FAILED, "User authorization failed"))
    refreshToken <- optIssueRefreshToken(user, authCode.scopesList, clientId)
  } yield {
    AuthResult(user, authCode.scope, refreshToken)
  }

  private def optIssueRefreshToken(user: User, scopes: List[String], clientId: String): Task[Option[String]] = {
    if (scopes.contains("offline_access")) {
      tokens.create(
        user.id, 
        Option(scopes.mkString(" ")).filter(_.nonEmpty),
        LocalDateTime.now().plusSeconds(RefreshTokenTTL.toSeconds),
        clientId
      ).map(Some(_))
    } else {
      Task.none
    }
  }

  private def cleanExpiredTokenAndFail(t: RefreshToken): Task[Unit] = 
    tokens.delete(t.id).flatMap(_ => Task.fail(AppException(AUTHORIZATION_FAILED, "Token expired")))

}

object TokenController {
  case class AuthResult(user: User, scope: Option[String] = None, refreshToken: Option[String] = None)
}