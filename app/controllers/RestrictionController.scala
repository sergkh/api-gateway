package controllers

//scalastyle:off public.methods.have.type

import javax.inject.{Inject, Singleton}

import com.impactua.bouncer.commons.utils.RichJson._
import com.impactua.bouncer.commons.utils.RichRequest._
import com.mohiva.play.silhouette.api.Silhouette
import forms.RestrictionForm
import models.{Restriction, JwtEnv}
import play.api.libs.json.{JsObject, Json}
import security.WithPermission
import services.RestrictionService

import scala.concurrent.ExecutionContext

@Singleton
class RestrictionController @Inject()(silh: Silhouette[JwtEnv],
                                      restrictions: RestrictionService)
                                     (implicit exec: ExecutionContext) extends BaseController {

  val readPerm = WithPermission("restrictions:read")
  val editPerm = WithPermission("restrictions:edit")

  def save = silh.SecuredAction(editPerm).async(parse.json) { request =>
    val user = request.identity
    val createRestriction = request.asForm(RestrictionForm.createRestriction)

    restrictions.save(createRestriction, user).map { restriction =>
      log.info(s"Restriction ${restriction.name} was added by $user")
      Ok(toJson(restriction))
    }
  }

  def update(name: String) = silh.SecuredAction(editPerm).async(parse.json) { request =>
    val user = request.identity
    val createRestriction = request.asForm(RestrictionForm.createRestriction).copy(name)

    restrictions.update(createRestriction, user).map { restriction =>
      log.info(s"Restriction ${restriction.name} was updated by $user")
      Ok(toJson(restriction))
    }
  }

  def get(name: String) = silh.SecuredAction(readPerm).async { request =>
    restrictions.get(name).map { restriction =>
      log.info(s"Reading restriction ${restriction.name} by ${request.identity}")
      Ok(toJson(restriction))
    }
  }

  def list = silh.SecuredAction(readPerm).async { request =>
    restrictions.list.map { list =>
      log.info(s"Listing restrictions by ${request.identity}")
      Ok(Json.obj(
        "items" -> list.map(toJson)
      ))
    }
  }

  def remove(name: String) = silh.SecuredAction(editPerm).async { request =>
    restrictions.remove(name).map { restriction =>
      log.info(s"Restriction ${restriction.name} was removed by ${request.identity}")
      NoContent
    }
  }

  private def toJson(restriction: Restriction) = Json.toJson(restriction).as[JsObject].rename("_id", "name")

}