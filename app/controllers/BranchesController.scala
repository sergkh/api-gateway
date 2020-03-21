package controllers

//scalastyle:off public.methods.have.type

import akka.actor.ActorSystem
import com.mohiva.play.silhouette.api.Silhouette
import events.EventsStream
import forms.BranchForm
import javax.inject.{Inject, Singleton}
import models.AppEvent._
import models.{AppException, ErrorCodes, JwtEnv}
import play.api.libs.json.Json
import security.{WithAnyPermission, WithBranchPermission}
import services.BranchesService
import utils.RichRequest._

import scala.concurrent.ExecutionContext

@Singleton
class BranchesController @Inject()(
                                   silh: Silhouette[JwtEnv],
                                   eventBus: EventsStream,
                                   branches: BranchesService
                                  )(implicit exec: ExecutionContext, system: ActorSystem)
 extends BaseController {
  implicit val branchesService = branches

  val editPerm = WithAnyPermission("branches:edit")
  val readPerm = WithBranchPermission("branches:read")

  def create = silh.SecuredAction(editPerm).async { request =>
    val create = request.asForm(BranchForm.createBranch)
    val user = request.identity

    branches.isAuthorized(create.parentOrRoot, user) flatMap {
      case true =>
        branches.create(create, user) map { createdBranch =>
          log.info(s"Branch created: $createdBranch")
          eventBus.publish(BranchCreated(request.identity.id, createdBranch, request))
          Ok(Json.toJson(createdBranch))
        }
      case false =>
        log.info(s"User $user not allowed to access parent branch $create")
        throw AppException(ErrorCodes.ACCESS_DENIED, s"User cannot access parent branch")
    }
  }

  def update(branchId: String) = silh.SecuredAction(editPerm).async(parse.json) { request =>
    val update = request.asForm(BranchForm.createBranch)
    val user = request.identity

    branches.isAuthorized(update.parentOrRoot, user) flatMap {
      case true =>
        for {
          result <- branches.update(branchId, update, user)
          _ <- eventBus.publish(BranchUpdated(request.identity.id, result._1, result._2, request))
        } yield {
          log.info(s"Branch $branchId updated")
          Ok(Json.toJson(result))
        }
      case false =>
        log.info(s"User $user not allowed to access parent branch $update")
        throw AppException(ErrorCodes.ACCESS_DENIED, s"User cannot access parent branch")
    }
  }

  def get(branchId: String) = silh.SecuredAction(readPerm(branchId)).async { request =>
    branches.get(branchId) map {
      case Some(branch) =>
        log.info(s"Reading branch $branchId by ${request.identity}")
        Ok(Json.toJson(branch))
      case None =>
        log.info(s"Branch $branchId is not found by ${request.identity}")
        throw AppException(ErrorCodes.ENTITY_NOT_FOUND, s"Branch is not found $branchId")
    }
  }

  def list(parent: String) = silh.SecuredAction(readPerm(parent)).async { request =>

    log.info(s"Listing branches for $parent by ${request.identity}")

    branches.list(parent).map { list =>
      Ok(Json.obj(
        "items" -> list
      ))
    }
  }

  def remove(branchId: String) = silh.SecuredAction(editPerm).async { request =>
    for {
      deletedBranch <- branches.remove(branchId)
      _ <- eventBus.publish(BranchRemoved(request.identity.id, deletedBranch.id, request))
    } yield {
      log.info(s"Branch ${deletedBranch.id} was removed by ${request.identity}")
      NoContent
    }
  }
}
