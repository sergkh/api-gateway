package controllers

//scalastyle:off public.methods.have.type

import akka.actor.ActorSystem
import com.google.inject.Inject
import com.mohiva.play.silhouette.api.Silhouette
import events.EventsStream
import forms.UserPermissionForm._
import models.AppEvent.{RoleCreated, RoleDeleted, RoleUpdated}
import models.{AppException, ErrorCodes, JwtEnv, RolePermissions}
import play.api.libs.json.Json
import security.WithPermission
import services.{UserService, UsersRolePermissionsService}
import utils.RichJson._
import utils.RichRequest._
import ErrorCodes._
import scala.concurrent.ExecutionContext.Implicits.global
import services.formats.MongoFormats._

class UserRolePermissionController @Inject()(usersPermissionsService: UsersRolePermissionsService,
                                             usersService: UserService,
                                             eventBus: EventsStream,
                                             silh: Silhouette[JwtEnv])
                                            (implicit system: ActorSystem) extends BaseController {


  val editPerm = WithPermission("permissions:edit")
  val readPerm = WithPermission("permissions:read")

  def add = silh.SecuredAction(editPerm).async(parse.json) { implicit request =>
    val role = request.asForm(createForm)

    usersPermissionsService.get(role.role.toString) flatMap {
      case Some(job) =>
        log.info(s"Permissions for role ${role.role} already exist")
        throw AppException(ErrorCodes.ALREADY_EXISTS, s"Role ${role.role} already exist")

      case None =>
        for {
          userPerm <- usersPermissionsService.save(role)
          _ <- eventBus.publish(RoleCreated(role))
        } yield {
          log.info(s"Created permissions obj $userPerm by ${request.identity.id}")
          Ok(Json.toJson(userPerm))
        }
    }
  }

  def get(role: String) = silh.SecuredAction(readPerm).async { implicit request =>
    usersPermissionsService.get(role).map {
      case Some(rolePerm) =>
        log.info(s"Retrieve permissions for $role requested by ${request.identity.id}")
        Ok(Json.toJson(rolePerm))

      case None =>
        log.info(s"Permissions for $role not found")
        throw AppException(ErrorCodes.ENTITY_NOT_FOUND, s"Permissions for $role not found")
    }
  }

  def update(role: String) = silh.SecuredAction(editPerm).async(parse.json) { request =>
    val permissions = request.asForm(updateForm)
    val rolePerms = RolePermissions(role, permissions)

    for {
      _ <- usersPermissionsService.update(rolePerms)
      _ <- eventBus.publish(RoleUpdated(rolePerms))
      _ <- usersService.clearUserCaches()
    } yield {
      log.info(s"Permission for $role was updated by ${request.identity.id}")
      NoContent.withHeaders("Content-Type" -> "application/json")
    }
  }

  def remove(role: String) = silh.SecuredAction(editPerm).async { request =>
    log.info(s"Removing role $role by ${request.identity.id}")

    usersPermissionsService.remove(role).flatMap {
      case Some(r) =>
        eventBus.publish(RoleDeleted(r)) map { _ =>
          NoContent.withHeaders("Content-Type" -> "application/json")
        }
      case None =>
        throw AppException(ErrorCodes.ENTITY_NOT_FOUND, s"Permissions for $role not found")
    }
  }

  def listRoles = silh.SecuredAction(readPerm).async { request =>
    log.info(s"Obtained list of all roles by ${request.identity.id}")
    usersPermissionsService.getAvailableRoles.map(lst => Ok(Json.obj("items" -> lst)))
  }
}
