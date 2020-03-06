package controllers.swagger

//scalastyle:off public.methods.have.type

import controllers.Assets
import javax.inject.{Inject, Singleton}
import play.api.libs.ws.WSClient
import play.api.mvc.{BaseController, ControllerComponents}
import play.api.{Configuration, Environment}
import services.RoutingService
import utils.Logging

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

@Singleton
class ApiSpecs @Inject()(val controllerComponents: ControllerComponents,
                         env: Environment,
                         ws: WSClient,
                         config: Configuration,
                         router: RoutingService,
                         assets: Assets)(implicit ctx: ExecutionContext) extends BaseController with Logging {

  val swaggerConf = config.get[Configuration]("swagger")
  val updateTime = config.get[FiniteDuration]("etcd.fetchTime")

  val docsPath = config.get[String]("swagger.path").stripSuffix("/") + s"/docs/api.json"

  def specs = Action {
    Ok(
      router.getSwaggerJson(identity)
    ).withHeaders("Cache-Control" -> "no-cache, max-age=0, must-revalidate, no-store");
  }

  def docsIndex = Action {
    Ok(views.html.swagger(docsPath, swaggerConf))
  }

  def docsResources(file: String) = assets.at("/public/lib/swagger-ui", file)
}