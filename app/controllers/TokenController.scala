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

@Singleton
class TokenController @Inject()(silh: Silhouette[JwtEnv],
                                conf: Configuration,
                                oauth: ClientAppsService,
                                tokens: TokensService,
                                authCodes: AuthCodesService,
                                keyManager: KeysManager,
                                userService: UserService,
                                passwordHashers: PasswordHasherRegistry,
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

    for {
      app             <- oauth.getApp(clientId)
      _               <- conditionalFail(app.secret != clientSecret, ACCESS_DENIED, "Wrong client secret")
      userAndScope    <- grantType match {
        case "refresh_token" =>
          authorizeByRefreshToken(request.asForm(OAuthForm.getAccessTokenFromRefreshToken))
        case "password" =>
          authorizeByPassword(request.asForm(OAuthForm.getAccessTokenByPass))
        case "authorization_code" =>
          authorizeByAuthorizationCode(request.asForm(OAuthForm.getAccessTokenFromAuthCode))
      }
      (user, scope)   = userAndScope
      authenticator   <- silh.env.authenticatorService.create(LoginInfo(CredentialsProvider.ID, user.id))
      tokenWithUser   = authenticator.withUserInfo(user, scope)
      token           <- silh.env.authenticatorService.init(authenticator)
      _               <- eventBus.publish(Login(user.id, token, request, authenticator.id, authenticator.expirationDateTime.getMillis))
    } yield {
      log.info(s"Succeed user authentication ${user.id}")
              
      val expireIn = ((authenticator.expirationDateTime.toInstant.getMillis / 1000) -
                  LocalDateTime.now().toInstant(ZoneOffset.UTC).getEpochSecond())

      // TODO: handle redirect
      Ok(Json.obj(
        "token_type" -> "Bearer",
        "access_token" -> token,
        "expires_in" -> expireIn,
        "scope" -> scope
        // TODO: "id_token": "ID_token"
      ).filterNull)
    }
  }

  private def authorizeByRefreshToken(req: AccessTokenByRefreshToken): Future[(User, Option[String])] = for {
    refreshToken    <- tokens.get(req.refreshToken).orFail(AppException(AUTHORIZATION_FAILED, "Invalid refresh token"))
    _               <- if (refreshToken.expired) cleanExpiredTokenAndFail(refreshToken) else Future.unit
    loginInfo       = LoginInfo(CredentialsProvider.ID, refreshToken.userId)
    user            <- userService.retrieve(loginInfo).orFail(AppException(AUTHORIZATION_FAILED, "User authorization failed"))
  } yield {
    user -> refreshToken.scope
  }

  private def authorizeByPassword(req: AccessTokenByPassword): Future[(User, Option[String])] = {
    val loginInfo  = LoginInfo(CredentialsProvider.ID, req.username)
      
    userService.retrieve(loginInfo).orFail(AppException(AUTHORIZATION_FAILED, "User authorization failed")).map { user =>
      // TODO: rehash old format passwords
      if (user.flags.contains(User.FLAG_2FACTOR) || // TODO: support OTPs 
         !user.password.exists(p => passwordHashers.find(p).exists(_.matches(p, req.password)))) {
        throw new AppException(AUTHORIZATION_FAILED, "User authorization failed")
      }

      user -> None
    }
  }

  private def authorizeByAuthorizationCode(req: AccessTokenByAuthorizationCode): Future[(User, Option[String])] = for {
    authCode    <- authCodes.getAndRemove(req.authCode).map(_.filter(_.expired)).orFail(AppException(AUTHORIZATION_FAILED, "Invalid authorization code"))
    loginInfo       = LoginInfo(CredentialsProvider.ID, authCode.userId)
    user            <- userService.retrieve(loginInfo).orFail(AppException(AUTHORIZATION_FAILED, "User authorization failed"))
  } yield {
    user -> authCode.scope
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

/*
  final val IVALID_LOGIN_INFO_PROVIDER = "oauth.application"


  private val ETERNAL_TOKEN_TTL = config.get[FiniteDuration]("oauth.ttl")

  // TODO: remove from here
  def createToken(code: String, authenticatorService: CustomJWTAuthenticatorService)(implicit req: Request[_]): Future[JsObject] = {
    authenticatorService.retrieveByValue(code).flatMap {
      case Some(jwtAuthenticator) => jwtAuthenticator.customClaims match {
        case Some(json) =>
          val claims = try { json.as[TokenClaims] } catch {
            case _: Exception =>
              authenticatorService.discard(jwtAuthenticator, Results.Forbidden)
              log.info("Invalid token claims for token: " + json)
              throw AppException(ErrorCodes.INVALID_TOKEN_CLAIMS, "Invalid external data for token ")
          }

          val ttl = ETERNAL_TOKEN_TTL
          val expire = authenticatorService.clock.now + ttl
          val Array(appId, secret) = new String(Base64.getDecoder.decode(req.headers(Http.HeaderNames.AUTHORIZATION).replaceFirst("Basic ", ""))).split(":")

          getApp(appId).flatMap { app =>
            app.checkSecret(secret)

            val loginInfo = LoginInfo(CredentialsProvider.ID, app.ownerId)

            authenticatorService.renew(jwtAuthenticator.copy(
              expirationDateTime = expire,
              loginInfo = loginInfo
            )).flatMap { oauthToken =>
                eventBus.publish(Login(claims.userId.toString, oauthToken, req, jwtAuthenticator.id, jwtAuthenticator.expirationDateTime.getMillis))
                eventBus.publish(OauthTokenCreated(claims.userId.toString, jwtAuthenticator.id, oauthToken, req))

                log.info("Creating permanent oauth token: '" + oauthToken + "' for user: " + jwtAuthenticator.loginInfo.providerKey +
                  ", exp date: " + expire + ", permissions: " + claims.permissions.mkString(",") +
                  ", from: " + code + ", for: " + claims.clientId)

                Json.obj("accessToken" -> oauthToken, "expiresIn" -> ttl.toSeconds)
            }
          }

        case None =>
          log.info("Oauth token doesn't have required claims")
          throw AppException(ErrorCodes.ENTITY_NOT_FOUND, "Temporary token claims not found")
      }

      case None =>
        log.info(s"Temporary oauth token $code doesn't found")
        throw AppException(ErrorCodes.ENTITY_NOT_FOUND, "Temporary token not found")
    }
  }



  private def oauthAuthenticator(
                                  authenticatorService: JWTAuthenticatorService,
                                  loginInfo: LoginInfo,
                                  tokenClaims: TokenClaims,
                                  ttl: FiniteDuration)(implicit request: Request[_]) = {
    authenticatorService.create(loginInfo).map { jwt =>
      jwt.copy(
        idleTimeout = None,
        expirationDateTime = jwt.lastUsedDateTime + ttl,
        customClaims = Some(Json.toJson(tokenClaims).as[JsObject])
      )
    }
  }

    // TODO: remove from here
  def authorize(authReq: OAuthAuthorizeRequest, user: User, authService: JWTAuthenticatorService)
               (implicit request: Request[_]): Future[JsObject] = {

    getApp(authReq.clientId).flatMap { app =>

      val tokenType = if (authReq.responseType == OAuthAuthorizeRequest.Type.TOKEN) {
        TokenClaims.Type.OAUTH_ETERNAL
      } else {
        TokenClaims.Type.OAUTH_TEMPORARY
      }

      val tokenClaims = TokenClaims(user, app.id, authReq.permissions.filter(user.hasPermission))

      val (ttl, loginInfo) = authReq.responseType match {
        case OAuthAuthorizeRequest.Type.CODE =>
          (DEFAULT_TEMP_TOKEN_TTL, LoginInfo(CredentialsProvider.ID, IVALID_LOGIN_INFO_PROVIDER))

        case OAuthAuthorizeRequest.Type.TOKEN =>
          (ETERNAL_TOKEN_TTL, LoginInfo(CredentialsProvider.ID, user.id))

        case unknown: Any =>
          throw AppException(ErrorCodes.INVALID_REQUEST, "Unknown response type: " + unknown)
      }

      oauthAuthenticator(authService, loginInfo, tokenClaims, ttl).flatMap { authenticator =>

        authService.init(authenticator).flatMap { oauthToken =>
          if (tokenType == TokenClaims.Type.OAUTH_ETERNAL) {
            eventBus.publish(Login(user.id, oauthToken, request, authenticator.id, authenticator.expirationDateTime.getMillis))
            eventBus.publish(OauthTokenCreated(user.id, authenticator.id, oauthToken, request))
            Json.obj("accessToken" -> oauthToken, "expiresIn" -> ttl.toSeconds)
          } else {
            Json.obj("code" -> oauthToken)
          }
        }
      }
    }
  }
  */

  private def cleanExpiredTokenAndFail(t: RefreshToken): Future[Unit] = 
    tokens.delete(t.id).map { _ => throw AppException(AUTHORIZATION_FAILED, "Token expired") }

}

