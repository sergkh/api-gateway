package controllers.swagger

//scalastyle:off public.methods.have.type

import javax.inject.{Inject, Singleton}
import com.impactua.bouncer.commons.models.exceptions.AppException
import com.impactua.bouncer.commons.utils.Logging
import controllers.Assets
import models.ApiTemplate
import play.api.cache.{NamedCache, SyncCacheApi}
import play.api.libs.json.{JsObject, JsValue}
import play.api.libs.ws.WSClient
import play.api.mvc.{BaseController, ControllerComponents}
import play.api.{Configuration, Environment}
import services.{ApiTemplateService, RoutingService}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

@Singleton
class ApiSpecs @Inject()(val controllerComponents: ControllerComponents,
                         env: Environment,
                         ws: WSClient,
                         config: Configuration,
                         router: RoutingService,
                         apiTemplateService: ApiTemplateService)(implicit ctx: ExecutionContext) extends BaseController with Logging {

  val swaggerConf = config.get[Configuration]("swagger")
  val updateTime = config.get[FiniteDuration]("etcd.fetchTime")

  def specs(name: String) = Action.async {
    apiTemplateService.retrieve(name).map { template =>

      log.info(s"Getting API specs: $name, template: $template")

      Ok(
        router.getSwaggerJson(filterSwaggerPaths(template))
      ).withHeaders("Cache-Control" -> "no-cache, max-age=0, must-revalidate, no-store");
    }
  }

  def docsIndex(name: String) = Action.async {
    log.info(s"Getting Docs index: $name")
    apiTemplateService.retrieve(name).map { _ =>
      Ok(views.html.swagger(apiUrl(name), swaggerConf))
    }
  }

  def docsResources(file: String) = Assets.at("/public/lib/swagger-ui", file)

  def filterSwaggerPaths(filter: ApiTemplate)(fullPaths: JsObject): JsObject = {
    val paths = fullPaths.fields

    val filteredByPath = paths.filter { case (path, _) => filter.paths.matches(path) }

    JsObject(filteredByPath.map {
      case (path, JsObject(requests)) =>
        val filteredRequests = requests.filter {
          case (_, opJson) => (opJson \ "tags").asOpt[List[String]].fold(true)(_.exists(tag => filter.tags.matches(tag)))
        }
        path -> JsObject(filteredRequests)
      case other: Any => other
    })
  }

  private def apiUrl(name: String) = {
    config.get[String]("swagger.path").stripSuffix("/") + s"/docs/$name.json"
  }
}