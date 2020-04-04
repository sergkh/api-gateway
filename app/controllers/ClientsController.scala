package controllers

//scalastyle:off public.methods.have.type

import akka.actor.ActorSystem
import com.mohiva.play.silhouette.api.Silhouette
import events.EventsStream
import forms.{ClientAppForm, CommonForm}
import javax.inject.{Inject, Singleton}
import models.AppEvent._
import models._
import play.api.Configuration
import play.api.libs.json.{JsObject, Json}
import security._
import services.{ClientAppsService, UserService}
import utils.RichJson._
import utils.RichRequest._
import zio._

import scala.concurrent.ExecutionContext.Implicits._
import scala.language.implicitConversions

/**
  * @author Sergey Khruschak  <sergey.khruschak@gmail.com>
  * @author Yaroslav Derman   <yaroslav.derman@gmail.com>.
  */
@Singleton
class ClientsController @Inject()(silh: Silhouette[JwtEnv],
                                userService: UserService,
                                oauthService: ClientAppsService,
                                eventBus: EventsStream,
                                conf: Configuration)(implicit system: ActorSystem) extends BaseController {

  val readPerm = WithPermission("users:read")
  val editPerm = WithPermission("users:edit")
  val oauthCreatePerm = WithPermission("oauth_token:create")

  def createApp = silh.SecuredAction.async(parse.json) { req =>
    val data = req.asForm(ClientAppForm.create)

    val app = ClientApp(req.identity.id, data.name, data.description, data.logo, data.url, data.contacts, data.redirectUrlPattern)

    for {
      thirdApp <- oauthService.createApp(app)
      _        <- Task.fromFuture(_ => eventBus.publish(ApplicationCreated(req.identity.id, thirdApp, req)))
    } yield {
      log.info(s"Create third party application $thirdApp")
      Ok(Json.obj("clientSecret" -> thirdApp.secret, "clientId" -> thirdApp.id))
    }
  }

  def updateApp(userId: String, id: String) = silh.SecuredAction(editPerm || WithUserAndPerm(userId, "user:edit")).async(parse.json) { req =>
    val data = req.asForm(ClientAppForm.update)
    for {
      user <- userService.getRequestedUser(userId, req.identity)
      app  <- oauthService.getApp4user(id, user.id)
      newApp = app.update(data.name, data.description, data.logo, data.url, data.contacts, data.redirectUrlPattern)
      _ <- oauthService.updateApp(id, newApp)
      _ <- Task.fromFuture(ec => eventBus.publish(ApplicationUpdated(req.identity.id, app, req)))
    } yield {
      log.info("Updating client application " + id + ", for user: " + user.id)
      Ok(Json.toJson(app))
    }
  }

  def getAppByOwner(userId: String, id: String) = silh.SecuredAction(WithUser(userId)).async { req =>
    userService.getRequestedUser(userId, req.identity).flatMap { user =>
      oauthService.getApp4user(id, user.id).map { app =>
        log.info("Getting client application info " + id)
        Ok(Json.toJson(app))
      }
    }
  }

  def getPublicAppInformation(id: String) = Action.async { _ =>
    oauthService.getApp(id).map { app =>
      log.info("Getting client application info " + id)
      Ok(Json.toJson(app).as[JsObject].without("secret"))
    }
  }

  def listApplications(userId: String) = silh.SecuredAction(readPerm || WithUser(userId)).async { req =>
    userService.getRequestedUser(userId, req.identity).flatMap { user =>
      val page = req.asForm(CommonForm.paginated)

      for {
        apps  <- oauthService.getApps(user.id, page.limit, page.offset)
        count <- oauthService.countApp(Some(user.id))
      } yield {
        log.info("Getting client application list for user: " + user.id + ", count: " + count)
        Ok(Json.obj("items" -> apps, "count" -> count))
      }
    }
  }

  def removeApplication(userId: String, id: String) = silh.SecuredAction(editPerm || WithUserAndPerm(userId, "user:edit")).async { implicit req =>
    for {
      user <- userService.getRequestedUser(userId, req.identity)
      _    <- oauthService.removeApp(id, user)
      _    <- Task.fromFuture(ec => eventBus.publish(ApplicationRemoved(user.id, id, req)))
    } yield {
      log.info("Removed client application " + id + ", for user: " + user.id)
      NoContent
    }
  }
}
