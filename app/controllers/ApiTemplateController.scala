package controllers
//scalastyle:off public.methods.have.type

import com.google.inject.Inject
import com.impactua.bouncer.commons.utils.RichJson._
import com.impactua.bouncer.commons.utils.RichRequest._
import com.mohiva.play.silhouette.api.Silhouette
import forms.{ApiTemplateForm, UserForm}
import models.{ApiTemplate, JwtEnv}
import play.api.libs.json.{JsObject, Json}
import security.WithPermission
import services.ApiTemplateService

import scala.concurrent.ExecutionContext

/**
  * @author Vasyl Zalizetskyi
  */
class ApiTemplateController @Inject()(apiService: ApiTemplateService, silh: Silhouette[JwtEnv])
                                     (implicit ctx: ExecutionContext)
  extends BaseController {

  val readPerm = WithPermission("swagger:edit")
  val editPerm = WithPermission("swagger:read")

  def retrieve(name: String) = silh.SecuredAction(readPerm).async { implicit request =>
    log.info(s"User ${request.identity.uuid} try to retrieve api template $name")
    apiService.retrieve(name).map {
      res => Ok(toJson(res))
    }
  }

  def list = silh.SecuredAction(readPerm).async { request =>
    log.info(s"User ${request.identity.uuid} try to retrieve api template list")
    val queryPrams = request.asForm(UserForm.queryUser)

    val fItems = apiService.list(None, queryPrams)
    val fCount = apiService.count(None)

    for {
      items <- fItems
      count <- fCount
    } yield {
      Ok(Json.obj(
        "items" -> items.map(toJson),
        "count" -> count
      ))
    }
  }

  def insert = silh.SecuredAction(editPerm).async(parse.json) { request =>
    log.info(s"User ${request.identity.uuid} try to insert new api template")
    val apiTemplate = request.asForm(ApiTemplateForm.newApi())
    apiService.save(apiTemplate).map { apiTemplate =>
      Ok(toJson(apiTemplate))
    }
  }

  def update(name: String) = silh.SecuredAction(editPerm).async(parse.json) { request =>
    log.info(s"User ${request.identity.uuid} try to update api template $name")
    val apiTemplate = request.asForm(ApiTemplateForm.newApi(Some(name)))

    apiService.update(name, apiTemplate).map { updatedTemplate =>
      Ok(toJson(updatedTemplate))
    }

  }

  def remove(name: String) = silh.SecuredAction(editPerm).async { request =>
    log.info(s"User ${request.identity.uuid} try to delete api template $name")
    apiService.remove(name).map { _ => NoContent }
  }

  private def toJson(apiTemplate: ApiTemplate) = Json.toJson(apiTemplate).as[JsObject].rename("_id", "name")

}