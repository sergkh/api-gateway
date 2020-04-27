package controllers

//scalastyle:off public.methods.have.type

import javax.inject.{Inject, Singleton}
import play.api.libs.ws.WSClient
import play.api.mvc.{BaseController => PlayBaseController, ControllerComponents}
import play.api.{Configuration, Environment}
import services.ServicesManager
import utils.Logging

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

@Singleton
class ApiSpecs @Inject()(val controllerComponents: ControllerComponents,
                         config: Configuration,
                         services: ServicesManager,
                         assets: Assets)(implicit ctx: ExecutionContext) extends PlayBaseController {

  val swaggerConf = config.get[Configuration]("swagger")
  val updateTime  = config.get[FiniteDuration]("etcd.fetchTime")

  val docsPath = config.get[String]("swagger.path").stripSuffix("/") + s"/docs/api.json"

  def specs = Action {
    Ok(services.swaggerJson).withHeaders("Cache-Control" -> "no-cache, max-age=0, must-revalidate, no-store");
  }

  def docsIndex = Action {
    Ok(views.html.swagger(docsPath, swaggerConf))
  }

  def docsResources(file: String) = assets.at("/public/lib/swagger-ui", file)
}