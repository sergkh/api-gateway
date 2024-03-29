package controllers

//scalastyle:off public.methods.have.type

import com.mohiva.play.silhouette.api.Silhouette
import events._
import forms.{ClientAppForm, CommonForm}
import javax.inject.{Inject, Singleton}
import models._
import play.api.libs.json.{JsObject, Json}
import security._
import services.{ClientAppsService, UserService}
import utils.RichJson._
import utils.RichRequest._


import scala.concurrent.ExecutionContext.Implicits._
import scala.language.implicitConversions
import zio.Task

/**
  * @author Sergey Khruschak  <sergey.khruschak@gmail.com>
  * @author Yaroslav Derman   <yaroslav.derman@gmail.com>.
  */
@Singleton
class ClientsController @Inject()(silh: Silhouette[JwtEnv],
                                userService: UserService,
                                clients: ClientAppsService,
                                eventBus: EventsStream) extends BaseController {

  val readPerm = WithPermission("users:read")
  val editPerm = WithPermission("users:edit")
  val oauthCreatePerm = WithPermission("oauth_token:create")

  def createApp = silh.SecuredAction.async(parse.json) { req =>
    val data = req.asForm(ClientAppForm.create)

    val app = ClientApp(req.identity.id, data.name, data.description, data.logo, data.url, data.redirectUrlPattern)

    for {
      thirdApp <- clients.createApp(app)
      _        <- eventBus.publish(ClientCreated(thirdApp, req.reqInfo))
    } yield {
      log.info(s"Created client ${thirdApp.id}: ${thirdApp.name}")
      Ok(Json.obj("clientSecret" -> thirdApp.secret, "clientId" -> thirdApp.id))
    }
  }

  def updateApp(userId: String, id: String) = silh.SecuredAction(editPerm || WithUser(userId)).async(parse.json) { req =>
    val data = req.asForm(ClientAppForm.update)
    for {
      user <- userService.getRequestedUser(userId, req.identity)
      app  <- clients.getApp4user(id, user.id)
      newApp = app.update(data.name, data.description, data.logo, data.url, data.redirectUrlPattern)
      _ <- clients.updateApp(id, newApp)
      _ <- eventBus.publish(ClientUpdated(app, req.reqInfo))
    } yield {
      log.info(s"Updated client $id for user: ${user.id} with data: $data")
      Ok(Json.toJson(app))
    }
  }

  def getAppByOwner(userId: String, id: String) = silh.SecuredAction(WithUser(userId)).async { req =>
    userService.getRequestedUser(userId, req.identity).flatMap { user =>
      clients.getApp4user(id, user.id).map { app =>
        log.info(s"Got client info $id by ${req.identity.info}")
        Ok(Json.toJson(app))
      }
    }
  }

  def getPublicAppInformation(id: String) = Action.async { _ =>
    clients.getApp(id).map { app =>
      log.info(s"Got public client info $id")
      Ok(Json.toJson(app).as[JsObject].without("secret"))
    }
  }

  def listApplications(userId: String) = silh.SecuredAction(readPerm || WithUser(userId)).async { req =>
    userService.getRequestedUser(userId, req.identity).flatMap { user =>
      val page = req.asForm(CommonForm.paginated)

      for {
        apps  <- clients.getApps(user.id, page.limit, page.offset)
        count <- clients.countApp(Some(user.id))
      } yield {
        log.info(s"Got clients list for user: ${user.id}, count: $count by ${req.identity.info}")
        Ok(Json.obj("items" -> apps, "count" -> count))
      }
    }
  }

  def removeApplication(userId: String, id: String) = silh.SecuredAction(editPerm || WithUserAndPerm(userId, "user:edit")).async { implicit req =>
    for {
      user       <- userService.getRequestedUser(userId, req.identity)
      clientOpt  <- clients.removeApp(id, user)
      status     <- clientOpt match {
        case Some(client) => 
          log.info(s"Removed client $id for user: ${user.id} by ${req.identity.info}")
          eventBus.publish(ClientRemoved(client, req.reqInfo)).map(_ => NoContent)
        case None =>
          log.info(s"Failed to remove client $id for user: ${user.id} by ${req.identity.info}. Client not found")
          Task.succeed(NotFound)
      }
    } yield status
  }
}
