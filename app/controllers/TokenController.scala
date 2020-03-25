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
import security.KeysManager
import services.{OAuthService, TokensService, UserService}
import utils.FutureUtils._
import utils.RichRequest._

import scala.concurrent.ExecutionContext
import play.api.mvc.Headers
import play.api.http.HeaderNames
import play.api.mvc.RequestHeader
import scala.util.Try

@Singleton
class TokenController @Inject()(silh: Silhouette[JwtEnv], 
                                oauth: OAuthService,
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

  def getAccessToken = Action.async { implicit request =>
    val refresh = request.asForm(OAuthForm.getAccessTokenFromRefreshToken) // TODO: implement other forms
    
    val (clientId, clientSecret) = clientAuthorization(request).getOrElse {
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

  def clientAuthorization(request: RequestHeader): Option[(String, String)] = 
    request.headers.get(HeaderNames.AUTHORIZATION).map(_.split(" ")).collect {
      case Array("Bearer", data) => Try {
        val Array(user, pass) = new String(ju.Base64.getDecoder.decode(data)).split(":")
        user -> pass
      }.toOption
    }.flatten
}

