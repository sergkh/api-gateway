package services

import java.util.concurrent.ConcurrentHashMap

import akka.actor.ActorSystem
import com.google.inject.{Inject, Singleton}
import com.iheart.playSwagger.SwaggerSpecGenerator
import events.{EventsStream, ServiceDiscovered, ServiceLost, ServicesListUpdate}
import models.Service
import play.api.libs.json._
import play.api.libs.ws.WSClient
import play.api.{Configuration, Environment, Mode}
import services.RoutingService.ServiceDescriptor
import utils.Logging
import utils.RichJson._
import utils.ProxyRoutesParser._

import scala.jdk.CollectionConverters._
import scala.concurrent.Future
import scala.util.{Failure, Success}
import models.Swagger


/**
  * @author faiaz
  * @author Yaroslav Derman <yaroslav.derman@gmail.com>
  */
@Singleton
class RoutingService @Inject()(ws: WSClient,
                               config: Configuration,
                               eventBus: EventsStream,
                               system: ActorSystem,
                               playEnv: Environment) extends Logging {

  implicit val cl = getClass.getClassLoader
  implicit val executor = system.dispatcher

  private val cfg = config.get[Configuration]("swagger")

  private val serviceDescriptors = new ConcurrentHashMap[Service, ServiceDescriptor]()

  private val swagger = Swagger(SwaggerSpecGenerator(true, "forms", "models").generate().get)

  def listServices: List[Service] = serviceDescriptors.keys().asScala.toList

  def swaggerJson: JsObject = {
    log.debug("Getting Swagger Json")

    val services = serviceDescriptors.values().asScala.map(_.swagger)

    services.foldLeft(swagger)(_ mergeIn _).toJson
  }

  def matchService(path: String): Option[Service] = {
    log.debug(s"Getting service for url: $path")
    serviceDescriptors.entrySet().asScala.find { entry => entry.getValue.matches(path) } map (_.getKey)
  }

  def serviceLost(service: Service): Unit = {
    log.debug(s"Service disappeared: $service")
    serviceDescriptors.remove(service)
  }

  def serviceDiscovered(service: Service): Unit = {
    log.debug(s"New service discovered: $service")
    serviceDescriptors.putIfAbsent(service, ServiceDescriptor.empty(service))

    getServiceDescriptor(service) onComplete {
      case Success(descr) =>
        log.info(s"Got new service descriptor: ${descr.routes}")
        serviceDescriptors.put(service, descr)
      case Failure(ex) =>
        log.debug(s"Failed to get description for service $service: ${ex.getMessage}")
    }
  }

  private def getServiceDescriptor(service: Service): Future[ServiceDescriptor] = {
    ws.url(service.swaggerUrl).get().map(wsResp => {
      ServiceDescriptor.fromSwagger(service, wsResp.json.as[JsObject])
    })
  }

  eventBus.subscribe[ServiceDiscovered](discovered => serviceDiscovered(discovered.service))
  eventBus.subscribe[ServiceLost](lost => serviceLost(lost.service))
  eventBus.subscribe[ServicesListUpdate](list => list.services.foreach(serviceDiscovered))
}

object RoutingService {

  case class ServiceDescriptor(service: Service, swagger: Swagger) {

    val routes = swagger.paths map { case (path, _) => toRegex(parse(path, '{')).pattern }

    def matches(path: String): Boolean = routes.exists(_.matcher(path).matches())
  }

  object ServiceDescriptor {

    def empty(service: Service): ServiceDescriptor = ServiceDescriptor(service, Swagger.empty)

    def fromSwagger(service: Service, swaggerJson: JsObject): ServiceDescriptor = {
      ServiceDescriptor(service, Swagger(swaggerJson).prefixPaths(service.prefix))
    }
  }
}