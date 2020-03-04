package services

import java.util.concurrent.ConcurrentHashMap

import akka.actor.ActorSystem
import com.google.inject.{Inject, Singleton}
import events.{EventsStream, ServiceDiscovered, ServiceLost, ServicesListUpdate}
import models.Service
import play.api.libs.json._
import play.api.libs.ws.WSClient
import play.api.{Configuration, Environment, Mode}
import services.RoutingService.ServiceDescriptor
import utils.Logging
import utils.ProxyRoutesParser._

import scala.collection.JavaConverters._
import scala.concurrent.Future
import scala.util.{Failure, Success}
import utils.RichJson._

/**
  * @author faiaz
  * @author Yaroslav Derman <yaroslav.derman@gmail.com>
  */
@Singleton
class RoutingService @Inject()(ws: WSClient,
                               config: Configuration,
                               eventBus: EventsStream,
                               system: ActorSystem,
                               playEnv: Environment,
                               docService: ApiTemplateService) extends Logging {

  implicit val cl = getClass.getClassLoader
  implicit val executor = system.dispatcher

  private val cfg = config.get[Configuration]("swagger")

  private val serviceDescriptors = new ConcurrentHashMap[Service, ServiceDescriptor]()
  private val baseSwagger = Json.parse("public/swagger.json")
                                      .as[JsObject] //SwaggerSpecGenerator(cfg.get[Boolean]("swaggerV3"), "forms", "models").generate().get
  private val localDescriptor: ServiceDescriptor = ServiceDescriptor.fromSwagger(Service("api"), baseSwagger)

  private val info = (baseSwagger \ "info").as[JsObject] ++ Json.obj(
    "title" -> cfg.get[String]("appName"),
    "version" -> utils.BuildInfo.version
  )
  private val path = cfg.get[String]("path")
  private val host = cfg.get[String]("host")
  private val schema = cfg.get[Seq[String]]("schema")
  private val other = baseSwagger.without("paths", "definitions", "tags", "basePath", "schemes", "host", "info")
  private val isProd = playEnv.mode == Mode.Prod
  private val authorizationUrl = (baseSwagger \ "securityDefinitions" \ "oauth" \ "authorizationUrl").as[String]
  private val prodAuthorizationUrl = if (isProd) "/api" + authorizationUrl else authorizationUrl

  private val emptySeq = Seq[(String, JsValue)]()
  private val emptyJsArray: JsArray = JsArray()

  def listServices: List[Service] = serviceDescriptors.keys().asScala.toList

  def getSwaggerJson(transformer: JsObject => JsObject): JsObject = {
    log.debug("Getting Swagger Json")

    val services = serviceDescriptors.values().asScala

    val fullPaths = JsObject(
      localDescriptor.paths ++ services.map { descr: ServiceDescriptor => descr.paths }.foldLeft(emptySeq)(_ ++ _)
    )

    val paths = transformer(fullPaths)

    val definitions = JsObject(localDescriptor.definitions ++ services.map { descr: ServiceDescriptor => descr.definitions }.foldLeft(emptySeq)(_ ++ _))
    val roles = JsObject(localDescriptor.roles ++ services.map { descr: ServiceDescriptor => descr.roles }.foldLeft(emptySeq)(_ ++ _))

    Json.obj(
      "info" -> info,
      "basePath" -> path,
      "host" -> host,
      "schemes" -> schema,
      "paths" -> paths,
      "definitions" -> definitions
    ) ++ other.deepMerge(Json.obj("securityDefinitions" -> Json.obj("oauth" -> Json.obj("scopes" -> roles, "authorizationUrl" -> prodAuthorizationUrl))))
  }

  def matchService(path: String): Option[Service] = {
    log.debug(s"Getting service for url: $path")
    serviceDescriptors.entrySet().asScala.find { entry => entry.getValue.matches(path) } map (_.getKey)
  }

  def getContentType(service: Service, path: String, method: String): Option[List[String]] = {
    val serviceDescriptor = Option(serviceDescriptors.get(service))

    val operations = serviceDescriptor.flatMap(_.paths.find { candidate =>
      toRegex(parse(candidate._1, '{')).pattern.matcher(path).matches()
    }).map(_._2).getOrElse(Json.obj())

    (operations \ method.toLowerCase \ "consumes").asOpt[List[String]] orElse serviceDescriptor.map(_.contentTypes)
  }

  def serviceLost(service: Service) {
    log.debug(s"Service disappeared: $service")
    serviceDescriptors.remove(service)
  }

  def serviceDiscovered(service: Service) {
    log.debug(s"New service discovered: $service")
    serviceDescriptors.putIfAbsent(service, ServiceDescriptor.empty(service))

    getServiceDescriptor(service) onComplete {
      case Success(descr) =>
        serviceDescriptors.put(service, descr)
      case Failure(ex) =>
        log.debug(s"Failed to get description for service $service: ${ex.getMessage}")
    }
  }

  private def getServiceDescriptor(service: Service): Future[ServiceDescriptor] = {
    ws.url(service.swaggerUrl).get().map(wsResp => {
      ServiceDescriptor.fromSwagger(service, wsResp.json)
    })
  }

  eventBus.subscribe[ServiceDiscovered](discovered => serviceDiscovered(discovered.service))
  eventBus.subscribe[ServiceLost](lost => serviceLost(lost.service))
  eventBus.subscribe[ServicesListUpdate](list => list.services.foreach(serviceDiscovered))
}

object RoutingService {

  case class ServiceDescriptor(service: Service,
                               paths: Seq[(String, JsValue)],
                               definitions: Seq[(String, JsValue)],
                               roles: Seq[(String, JsValue)],
                               contentTypes: List[String]) {

    val routes = paths map { case (path, _) => toRegex(parse(path, '{')).pattern }

    def matches(path: String): Boolean = routes.exists(_.matcher(path).matches())
  }

  object ServiceDescriptor {

    def empty(service: Service): ServiceDescriptor = ServiceDescriptor(service, Nil, Nil, Nil, Nil)

    def fromSwagger(service: Service, swaggerJson: JsValue): ServiceDescriptor = {
      val paths = (swaggerJson \ "paths").asOpt[JsObject].map(_.fields).getOrElse(Nil)
      val definitions = (swaggerJson \ "definitions").asOpt[JsObject].map(_.fields).getOrElse(Nil)
      val roles = (swaggerJson \ "securityDefinitions" \ "oauth" \ "scopes").asOpt[JsObject].map(_.fields).getOrElse(Nil)
      val contentTypes = (swaggerJson \ "consumes").asOpt[List[String]].getOrElse(List("application/json"))

      val prefix = service.pattern

      val pathsPrefixed = paths map { case (key, js) =>
        (prefix, key) match {
          case (p, k) if p == k => (p, js)
          case (p, k) if p == "/" => (k, js)
          case (p, k) if k == "/" => (p, js)
          case (p, k) => (p + k, js)
        }
      }

      ServiceDescriptor(service, pathsPrefixed, definitions, roles, contentTypes)
    }
  }
}