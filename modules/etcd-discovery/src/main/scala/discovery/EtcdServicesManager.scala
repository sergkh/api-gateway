package discovery

import akka.actor.ActorSystem
import com.google.inject.{Inject, Singleton}
import org.slf4j.LoggerFactory
import events.{AppEvent, ServiceDiscovered, ServiceLost, ServicesListUpdate}
import models.Service
import play.api.Configuration
import play.api.http.Status
import play.api.libs.json._
import play.api.libs.ws.WSClient

import scala.concurrent.duration._

@Singleton
class EtcdServicesManager @Inject()(config: Configuration, ws: WSClient, system: ActorSystem) {

  val log = LoggerFactory.getLogger(classOf[EtcdServicesManager])

  implicit val ec = system.dispatcher

  val bus = system.eventStream

  private var lastServices = Set[Service]()

  private val etcdEnabled = config.getOptional[Boolean]("etcd.enabled").getOrElse(true)
  private val etcd = config.get[String]("etcd.url")
  private val etcdFetchTime = config.get[FiniteDuration]("etcd.fetchTime")
  private val skipList = config.get[Seq[String]]("etcd.skip")

  private val manualServices: Set[Service] = config.getOptional[Seq[Configuration]]("services").getOrElse(Nil).map { c =>
    Service(
      c.get[String]("name"),
      c.getOptional[String]("prefix").getOrElse(""),
      (c.getOptional[String]("secret") orElse config.getOptional[String]("play.crypto.secret")).get,
      c.get[String]("address"),
      c.getOptional[String]("swagger").getOrElse(c.get[String]("address").stripSuffix("/") + "/docs/api.json")
    )
  }.toSet

  log.info(s"Manual services: $manualServices")

  def fetchEtcdConfig() {
    ws.url(s"$etcd/v2/keys/services").get.map {
      case response if response.status == Status.OK =>
        val services = parseEtcdConfig(response.json)
        log.info(s"Parsed ETCD services: $services")
        generateEvents(services.toSet, lastServices ++ manualServices).foreach { e =>
          bus.publish(e)
        }

        lastServices = services.toSet
      case error =>
        log.warn(s"Error getting services from the etcd $etcd: ${error.statusText} - ${error.body}")
        updateManualServices()
    } recover {
      case ex: Exception =>
        log.warn(s"ETCD fetching error ${ex.getMessage}")
        updateManualServices()
    }
  }

  def updateManualServices(): Unit = {
    // Do not send events if lastServices non empty and lastServices != manualServices, cause it means
    // that we temporarily lost connection to etcd, and it will remove services from it
    if (manualServices.nonEmpty && (lastServices.isEmpty || lastServices == manualServices)) {
      generateEvents(manualServices, lastServices).foreach { e =>
        bus.publish(e)
      }
    }
  }

  def parseEtcdConfig(json: JsValue): Seq[Service] = {
    val http = "http://"

     (json \ "node" \ "nodes").as[Seq[JsValue]] flatMap { el: JsValue =>

       val descr = Json.parse((el \ "value").as[String])
       val name = (descr \ "name").as[String]
       val env = (descr \ "Env").asOpt[Seq[String]].getOrElse(Seq[String]())

       val envSecretOpt = env.find(_.startsWith("PLAY_SECRET=")).map(_.substring("PLAY_SECRET=".length)) orElse
                          env.find(_.startsWith("SECRET=")).map(_.substring("SECRET=".length))

       val secret = ((descr \ "secret").asOpt[String] orElse envSecretOpt).getOrElse(config.get[String]("play.http.secret.key"))
       val prefix = (descr \ "prefix").asOpt[String].getOrElse("/")
       val basePath = http + (descr \ "address").asOpt[String].getOrElse("") + (descr \ "base_path").asOpt[String].getOrElse("").stripSuffix("/")
       val skip = descr \ "etcd_ignore" match {
         case JsDefined(_) => true
         case _ => skipList.contains(name)
       }

       val swaggerUrl = (descr \ "swagger").asOpt[String].map {
         case partPath: String if partPath.startsWith("/") => basePath + partPath
         case fullPath: String => fullPath
       }.getOrElse(s"$basePath/docs/api.json")

       if (basePath.length > http.length && !skip) {
         Some(Service(name = name, pattern = prefix, secret = secret, basePath = basePath, swaggerUrl = swaggerUrl))
       } else {
         log.debug(s"Skipping service: $name")
         None
       }
     }
 }

  def generateEvents(services: Set[Service], oldServices: Set[Service]): Seq[AppEvent] = {
    log.debug(s"Services: $services, old services: $oldServices")
    val events = Seq[AppEvent](ServicesListUpdate(services.toSeq))

    val additions = (services -- oldServices).map(s => ServiceDiscovered(s))
    val deletions = (oldServices -- services).map(s => ServiceLost(s))

    events ++ additions ++ deletions
  }


  if (etcdEnabled || manualServices.nonEmpty) {
    system.scheduler.schedule(0 second, etcdFetchTime, () => {
      log.info("Updating services configuration")
      if (etcdEnabled) {
        fetchEtcdConfig()
      } else {
        updateManualServices()
      }
    })
  } else {
    log.info("ETCD services discovery is disabled")
  }
}
