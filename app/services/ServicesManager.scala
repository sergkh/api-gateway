package services

import zio._
import java.util.concurrent.ConcurrentHashMap
import utils.TaskExt._
import akka.actor.ActorSystem
import com.google.inject.{Inject, Singleton}
import com.iheart.playSwagger.SwaggerSpecGenerator
import events.{EventsStream, ServiceDiscovered, ServiceLost, ServicesListUpdate}
import models.Service
import play.api.libs.json._
import play.api.libs.ws.WSClient
import play.api.{Configuration, Environment, Mode}
import services.ServicesManager.ServiceDescriptor
import utils.Logging
import utils.RichJson._
import utils.ProxyRoutesParser._

import scala.jdk.CollectionConverters._
import scala.concurrent.Future
import scala.util.{Failure, Success}
import models.Swagger

@Singleton
class ServicesManager @Inject()(ws: WSClient,
                               config: Configuration,
                               eventBus: EventsStream,
                               system: ActorSystem,
                               playEnv: Environment) extends Logging {

  implicit val cl = getClass.getClassLoader
  implicit val executor = system.dispatcher

  private val cfg = config.get[Configuration]("swagger")

  private val serviceDescriptors = new ConcurrentHashMap[Service, ServiceDescriptor]()

  private val swagger = Swagger(SwaggerSpecGenerator(true).generate().get)

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

  def serviceLost(service: Service): UIO[Unit] = UIO {
    log.debug(s"Service disappeared: $service")
    serviceDescriptors.remove(service)
  }

  def serviceDiscovered(service: Service): UIO[Unit] = {
    for {
      _      <- UIO(log.debug(s"New service discovered: $service"))
      _      <- UIO(serviceDescriptors.putIfAbsent(service, ServiceDescriptor.empty(service)))
      update <- getServiceDescriptor(service).either
    } yield {
      update match {
        case Right(d) =>
          log.info(s"Got new service descriptor: ${d.routes}")
          serviceDescriptors.put(service, d)
        case Left(ex) =>
          log.debug(s"Failed to get description for service $service: ${ex.getMessage}")
      }
    }
  }

  private def getServiceDescriptor(service: Service): Task[ServiceDescriptor] = {
    Task.fromFuture(_ => ws.url(service.swaggerUrl).get()).map(wsResp => {
      ServiceDescriptor.fromSwagger(service, wsResp.json.as[JsObject])
    })
  }

  eventBus.subscribe {
    case e: ServiceDiscovered => serviceDiscovered(e.service)
    case e: ServiceLost => serviceLost(e.service)
    case e: ServicesListUpdate => UIO.collectAllParN(2)(e.services.map(serviceDiscovered)) >>> UIO.unit
    case _ => UIO.unit
  }.unsafeRun
}

object ServicesManager {

  case class ServiceDescriptor(service: Service, swagger: Swagger) {
    val routes = swagger.paths map { case (path, _) => toRegex(parse(path, '{')).pattern }
    def matches(path: String): Boolean = routes.exists(_.matcher(path).matches())
  }

  object ServiceDescriptor {
    def empty(service: Service): ServiceDescriptor = ServiceDescriptor(service, Swagger.empty)

    def fromSwagger(service: Service, swaggerJson: JsObject): ServiceDescriptor = 
      ServiceDescriptor(service, Swagger(swaggerJson).prefixPaths(service.prefix))
  }
}