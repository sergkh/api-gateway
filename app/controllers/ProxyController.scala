package controllers

//scalastyle:off public.methods.have.type

import akka.actor.ActorSystem
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.util.{ByteString, Timeout}
import com.mohiva.play.silhouette.api.Silhouette
import com.mohiva.play.silhouette.api.actions.UserAwareRequest
import events.EventsStream
import javax.inject.{Inject, Singleton}
import models._
import play.api.Configuration
import play.api.cache.SyncCacheApi
import play.api.libs.json.Json
import play.api.libs.streams.Accumulator
import play.api.mvc.BodyParser
import security.WithPermission
import services.{ProxyService, ServicesManager, StreamedProxyRequest}
import utils.RichJson._

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

/**
  * Proxies any request to underlying service.
  *
  * @author Sergey Khruschak
  * @author Yaroslav Derman
  */
@Singleton
class ProxyController @Inject()(silh: Silhouette[JwtEnv],
                                streamedProxy: ProxyService,
                                services: ServicesManager,
                                conf: Configuration,
                                eventStream: EventsStream,
                                cache: SyncCacheApi
                               )(implicit exec: ExecutionContext) extends BaseController {

  implicit val timeout: Timeout = Timeout(1 minute)

  val meRegex = """(.*)(\/me\b)(\/?.*)""".r

  implicit val servicesFormat = Json.format[Service]

  private def sourceParser = BodyParser { _ =>
    Accumulator.source[ByteString].map(Right.apply)
  }

  def listServices = silh.SecuredAction(WithPermission("swagger:read")) { _ =>

    val list = services.listServices
    Ok(
      Json.obj(
        "items" -> list.map(s => Json.toJsObject(s).withoutFields("secret")),
        "count" -> list.size
      )
    )
  }

  def pass(path: String) = silh.UserAwareAction.async(sourceParser) { request =>

    val path = request.path

    val service = services.matchService(path) match {
      case Some(service: Service) => service
      case None => throw AppException(ErrorCodes.ENTITY_NOT_FOUND, s"Unknown URL: $path")
    }

    val url = service.makeUrl(replaceMe(path, request.identity)).get

    streamedProxy.passToUrl(url, service.secret, StreamedProxyRequest(request, Option(request.body)))
  }

  private def replaceMe(rootPath: String, userOpt: Option[User]) = {
    rootPath match {
      case meRegex(path, _, other) if userOpt.nonEmpty =>
        path + "/" + userOpt.map(_.id).get + other

      case meRegex(_, _, _) if userOpt.isEmpty =>
        throw AppException(ErrorCodes.ACCESS_DENIED, "User not authenticated")

      case url: Any => url
    }
  }

}