package controllers

//scalastyle:off public.methods.have.type

import akka.actor.ActorSystem
import com.google.inject.Inject
import com.mohiva.play.silhouette.api.Silhouette
import events.EventsStream
import forms.UserPermissionForm._
import models.AppEvent.{RoleCreated, RoleRemoved, RoleUpdated}
import models.{AppException, ErrorCodes, JwtEnv, RolePermissions}
import play.api.libs.json.Json
import security.WithPermission
import services.UsersRolesService
import utils.RichRequest._
import zio.Task

class UserRolePermissionController @Inject()(usersPermissionsService: UsersRolesService,
                                             eventBus: EventsStream,
                                             silh: Silhouette[JwtEnv])
                                            (implicit system: ActorSystem) extends BaseController {


  val editPerm = WithPermission("permissions:edit")
  val readPerm = WithPermission("permissions:read")

  def add = silh.SecuredAction(editPerm).async(parse.json) { implicit request =>
    val role = request.asForm(createForm)

    usersPermissionsService.get(role.role.toString) flatMap {
      case Some(_) =>
        log.info(s"Permissions for role ${role.role} already exist")
        Task.fail(AppException(ErrorCodes.ALREADY_EXISTS, s"Role ${role.role} already exist"))

      case None =>
        for {
          _       <- usersPermissionsService.save(role)
          _       <- eventBus.publish(RoleCreated(role, request.reqInfo))
        } yield {
          log.info(s"Added new role '$role' by ${request.identity.info}")
          Ok(Json.toJson(role))
        }
    }
  }

  def get(role: String) = silh.SecuredAction(readPerm).async { implicit request =>
    usersPermissionsService.get(role).flatMap {
      case Some(rolePerm) =>
        log.info(s"Retrieve permissions for $role requested by ${request.identity.id}")
        Task.succeed(Ok(Json.toJson(rolePerm)))
      case None =>
        log.info(s"Permissions for $role not found")
        Task.fail(AppException(ErrorCodes.ENTITY_NOT_FOUND, s"Permissions for $role not found"))
    }
  }

  def update(role: String) = silh.SecuredAction(editPerm).async(parse.json) { request =>
    val permissions = request.asForm(updateForm)
    val rolePerms = RolePermissions(role, permissions)

    for {
      _ <- usersPermissionsService.update(rolePerms)
      _ <- eventBus.publish(RoleUpdated(rolePerms, request.reqInfo))
    } yield {
      log.info(s"Permission for $role was updated by ${request.identity.id}")
      NoContent.withHeaders("Content-Type" -> "application/json")
    }
  }

  def remove(role: String) = silh.SecuredAction(editPerm).async { request =>
    log.info(s"Removing role $role by ${request.identity.id}")

    usersPermissionsService.remove(role).flatMap {
      case Some(r) =>
        eventBus.publish(RoleRemoved(r, request.reqInfo)) map { _ =>
          NoContent.withHeaders("Content-Type" -> "application/json")
        }
      case None =>
        Task.fail(throw AppException(ErrorCodes.ENTITY_NOT_FOUND, s"Permissions for $role not found"))
    }
  }

  def listRoles = silh.SecuredAction(readPerm).async { request =>
    log.info(s"Obtained list of all roles by ${request.identity.id}")
    usersPermissionsService.getAvailableRoles.map(lst => Ok(Json.obj("items" -> lst)))
  }
}
