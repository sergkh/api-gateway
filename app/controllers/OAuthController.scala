package controllers

//scalastyle:off public.methods.have.type

import akka.actor.ActorSystem
import javax.inject.{Inject, Singleton}
import com.mohiva.play.silhouette.api.Silhouette
import com.mohiva.play.silhouette.api.services.AuthenticatorService
import com.mohiva.play.silhouette.impl.authenticators.JWTAuthenticator
import events.EventsStream
import forms.{OAuthForm, ThirdPartyAppForm}
import models.AppEvent._
import models._
import play.api.Configuration
import play.api.libs.json.{JsObject, Json}
import security._
import services.{OAuthService, UserService}
import utils.Settings._
import utils.RichRequest._
import utils.RichJson._
import ErrorCodes._
import scala.concurrent.ExecutionContext.Implicits._
import scala.language.implicitConversions

/**
  * @author Sergey Khruschak  <sergey.khruschak@gmail.com>
  * @author Yaroslav Derman   <yaroslav.derman@gmail.com>.
  */
@Singleton
class OAuthController @Inject()(silh: Silhouette[JwtEnv],
                                userService: UserService,
                                oauthService: OAuthService,
                                eventBus: EventsStream,
                                oauth: Oauth,
                                conf: Configuration)(implicit system: ActorSystem) extends BaseController {

  implicit def toService(authService: AuthenticatorService[JWTAuthenticator]): CustomJWTAuthenticatorService =
    authService.asInstanceOf[CustomJWTAuthenticatorService]

  implicit val format = oauthService.format

  val readPerm = WithAnyPermission("users:read")
  val editPerm = WithAnyPermission("users:edit")
  val oauthCreatePerm = WithPermission("oauth_token:create")

  // Called to login to account 
  def authorize = silh.SecuredAction(NotOauth).async { implicit request =>
    val authReq = request.asForm(ThirdPartyAppForm.authorize)

    oauthService.authorize(authReq, request.identity, silh.env.authenticatorService).map { tokenResp =>
      Ok(tokenResp)
    }
  }

  def adminAuthorize(user: String) = silh.SecuredAction(NotOauth && oauthCreatePerm).async(parse.json) { implicit request =>
    val authReq = request.asForm(ThirdPartyAppForm.authorize)

    userService.getByAnyId(user).flatMap { user =>
      oauthService.authorize(authReq, user, silh.env.authenticatorService).map { tokenResp =>
        Ok(tokenResp)
      }
    }
  }

  def createToken = oauth.Secured.async(parse.json) { implicit request =>
    val code = request.asForm(ThirdPartyAppForm.code).code

    oauthService.createToken(code, silh.env.authenticatorService).map { tokenResp =>
      Ok(tokenResp)
    }
  }

  def adminCreateToken(user: String) = silh.SecuredAction(NotOauth && oauthCreatePerm).async(parse.json) { implicit request =>
    val code = request.asForm(ThirdPartyAppForm.code).code

    oauthService.createToken(code, silh.env.authenticatorService).map { tokenResp =>
      Ok(tokenResp)
    }
  }

  def removeToken(token: String) = silh.SecuredAction(NotOauth).async { req =>
    silh.env.authenticatorService.retrieveByValue(token).flatMap {
      case Some(authenticator) =>
        oauthService.remove(authenticator.id)
        val user = req.identity

        for {
          _ <- eventBus.publish(OauthTokenRemoved(user.uuidStr, authenticator.id, token, req))
          _ <- eventBus.publish(Logout(user, token, req, authenticator.id))
        } yield {
          log.info(s"Remove oauth token $token by ${req.identity.id}")
          NoContent
        }
      case None =>
        log.error("Oauth token " + token + " not found")
        throw AppException(ErrorCodes.ENTITY_NOT_FOUND, "Can't found oauth token")
    }
  }

  def getAuthenticators = silh.SecuredAction(readPerm && NotOauth).async { implicit req =>
    val data = req.asForm(OAuthForm.getTokens)
    val limit = data.limit.getOrElse(DEFAULT_LIMIT)
    val offset = data.limit.getOrElse(DEFAULT_OFFSET)

    oauthService.list(data.userId, data.accountId, data.appId, limit, offset).map { tokens =>
      log.info(s"Get oauth tokens for user: ${data.userId}, accountId: ${data.accountId}, clientId: ${data.appId} by ${req.identity.id}")
      Ok(Json.toJson(tokens))
    }
  }

  def createApp = silh.SecuredAction(editPerm && NotOauth).async(parse.json) { req =>
    val data = req.asForm(ThirdPartyAppForm.create)

    val app = ThirdpartyApplication(req.identity.id, data.name, data.description, data.logo, data.url, data.contacts, data.redirectUrlPattern)

    for {
      thirdApp <- oauthService.createApp(app)
      _        <- eventBus.publish(ApplicationCreated(req.identity.uuidStr, thirdApp, req))
    } yield {
      log.info(s"Create third party application $thirdApp")
      Ok(Json.obj("clientSecret" -> thirdApp.secret, "clientId" -> thirdApp.id))
    }
  }

  def updateApp(userId: String, id: String) = silh.SecuredAction(NotOauth && (editPerm || WithUser(userId))).async(parse.json) { req =>
    val data = req.asForm(ThirdPartyAppForm.update)
    for {
      user <- userService.getRequestedUser(userId, req.identity)
      app <- oauthService.getApp4user(id, user.id)
      newApp = app.toNonEmptyApplication(data.enabled, data.name, data.description, data.logo, data.url, data.contacts, data.redirectUrlPattern)
      _ <- oauthService.updateApp(id, newApp)
      _ <- eventBus.publish(ApplicationUpdated(req.identity.uuidStr, app, req))
    } yield {
      log.info("Updating thirdparty application " + id + ", for user: " + user.id)
      Ok(toJson(app))
    }
  }

  def getApp(userId: String, id: String) = silh.SecuredAction(NotOauth && (readPerm || WithUser(userId))).async { req =>
    userService.getRequestedUser(userId, req.identity).flatMap { user =>
      oauthService.getApp4user(id, user.id).map { app =>
        log.info("Getting thirdparty application info " + id)
        Ok(toJson(app))
      }
    }
  }

  def getAppById(id: String) = Action.async { request =>
      oauthService.getApp(id).map { app =>
        log.info("Getting thirdparty application info " + id)
        Ok(toJson(app).without("secret"))
      }
  }

  private def toJson(app: ThirdpartyApplication) = Json.toJson(app).as[JsObject].rename("_id", "id")

  def getApps(userId: String) = silh.SecuredAction(NotOauth && (readPerm || WithUser(userId))).async { req =>
    userService.getRequestedUser(userId, req.identity).flatMap { user =>
      val data = req.asForm(ThirdPartyAppForm.filter)

      val fApps = oauthService.getApps(user.id, data.limit, data.offset)
      val fCount = oauthService.countApp(Some(user.id))

      for {
        apps <- fApps
        count <- fCount
      } yield {
        log.info("Getting thirdparty application list for user: " + user.id + ", count: " + count)
        Ok(Json.obj("items" -> apps.map(toJson), "count" -> count))
      }
    }
  }

  def removeApp(userId: String, id: String) = silh.SecuredAction(NotOauth && (editPerm || WithUser(userId))).async { implicit req =>
    userService.getRequestedUser(userId, req.identity).flatMap { user =>

      oauthService.removeApp(id, user, silh.env.authenticatorService)

      eventBus.publish(ApplicationRemoved(user.uuidStr, id, req)) map { _ =>
        log.info("Disable thirdparty application " + id + ", for user: " + user.id)
        NoContent
      }
    }
  }

}
