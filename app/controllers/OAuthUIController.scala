package controllers

//scalastyle:off public.methods.have.type

import javax.inject.{Inject, Singleton}

import com.mohiva.play.silhouette.api.Silhouette
import com.mohiva.play.silhouette.api.services.AuthenticatorService
import com.mohiva.play.silhouette.impl.authenticators.JWTAuthenticator
import models.JwtEnv
import play.api.i18n.I18nSupport
import play.api.{Configuration, Mode}
import security._
import services._

import scala.concurrent.ExecutionContext.Implicits._
import scala.concurrent.Future
import scala.language.implicitConversions

@Singleton
class OAuthUIController @Inject()(oauth: Oauth,
                                  oauthService: OAuthService,
                                  silh: Silhouette[JwtEnv],
                                  playEnv: play.api.Environment,
                                  assets: Assets,
                                  conf: Configuration) extends BaseController with I18nSupport {

  val basePath = conf.get[String]("app.basePath").stripSuffix("/")

  implicit def toService(authService: AuthenticatorService[JWTAuthenticator]): CustomJWTAuthenticatorService =
    authService.asInstanceOf[CustomJWTAuthenticatorService]

  def displayAuthorize(clientId: String, scopes: String) = silh.UserAwareAction.async { implicit request =>
    val permissions = scopes.split(' ')

    request.identity match {
      case Some(user) if user.hasAllPermission(permissions: _*) =>
        log.info(s"User $user accessed web")
        oauthService.getApp(clientId).map { app =>
          Ok(views.html.authorization(basePath, app, permissions))
        }
      case Some(user) =>
        val diff = permissions.toSet.diff(user.permissions.toSet)
        log.warn(s"User $user does not have required permissions: [${diff.mkString(",")}]")
        Future.successful(Forbidden(views.html.login(basePath)))
      case _ =>
        log.info(s"OAuth showing login")
        Future.successful(Unauthorized(views.html.login(basePath)))
    }
  }

  def oauthRedirect = assets.at("/public/lib/swagger-ui", "oauth2-redirect.html")

}
