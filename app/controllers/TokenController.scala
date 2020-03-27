package controllers

//scalastyle:off public.methods.have.type

import java.time.{LocalDateTime, ZoneOffset}
import java.{util => ju}

import com.mohiva.play.silhouette.api.{LoginInfo, Silhouette}
import com.mohiva.play.silhouette.impl.providers.CredentialsProvider
import events.EventsStream
import forms.OAuthForm
import javax.inject.{Inject, Singleton}
import models.AppEvent.Login
import models.ErrorCodes._
import models.{AppException, ErrorCodes, JwtEnv}
import play.api.libs.json.Json
import security.{KeysManager, WithUser}
import services.{ClientAppsService, TokensService, UserService}
import utils.FutureUtils._
import utils.RichRequest._

import scala.concurrent.ExecutionContext
import play.api.mvc.Headers
import play.api.http.HeaderNames
import play.api.mvc.RequestHeader
import scala.util.Try

@Singleton
class TokenController @Inject()(silh: Silhouette[JwtEnv],
                                oauth: ClientAppsService,
                                tokens: TokensService,
                                keyManager: KeysManager,
                                userService: UserService,
                                eventBus: EventsStream
                              )
                               (implicit exec: ExecutionContext) extends BaseController {

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

  def getAccessToken = Action.async { implicit request =>    
    val refresh = request.asForm(OAuthForm.getAccessTokenFromRefreshToken) // TODO: implement other forms
    
    val (clientId, clientSecret) = request.basicAuth.getOrElse {
      throw new AppException(AUTHORIZATION_FAILED, "Client authorization failed")
    }
    
    log.info(s"Requesting access token for ${clientId}")

    for {
      app             <- oauth.getApp(clientId)
      _               <- conditionalFail(app.secret != clientSecret, ACCESS_DENIED, "Wrong client secret")
      refreshToken    <- tokens.get(refresh.refreshToken).orFail(AppException(AUTHORIZATION_FAILED, "Invalid refresh token"))
      loginInfo       = LoginInfo(CredentialsProvider.ID, refreshToken.userId)
      user            <- userService.retrieve(loginInfo).orFail(AppException(AUTHORIZATION_FAILED, "User authorization failed"))
      authenticator   <- silh.env.authenticatorService.create(loginInfo)
      token           <- silh.env.authenticatorService.init(authenticator)
      _               <- eventBus.publish(Login(user.id, token, request, authenticator.id, authenticator.expirationDateTime.getMillis))
    } yield {
      log.info(s"Succeed user authentication ${loginInfo.providerKey}")
      
      val expireIn = ((authenticator.expirationDateTime.toInstant.getMillis / 1000) - 
                  LocalDateTime.now().toInstant(ZoneOffset.UTC).getEpochSecond())

      Ok(Json.obj(
        "token_type" -> "Bearer",
        "access_token" -> token,
        "expires_in" -> expireIn,
        "scope" -> refreshToken.scopes.mkString(" ")
        // TODO: "id_token": "ID_token"        
      ))
    }
  }

    // TODO: fixme
  def listUserTokens(userId: String) = silh.SecuredAction(WithUser(userId)).async { implicit req =>
    // val data = req.asForm(OAuthForm.getTokens)
    // val limit = data.limit.getOrElse(DEFAULT_LIMIT)
    // val offset = data.limit.getOrElse(DEFAULT_OFFSET)

    // oauthService.list(data.userId, data.appId, limit, offset).map { tokens =>
    //   log.info(s"Get oauth tokens for user: ${data.userId}, clientId: ${data.appId} by ${req.identity.id}")
    //   Ok(Json.toJson(tokens))
    // }

    ???
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

}

