package controllers

//scalastyle:off public.methods.have.type

import akka.actor.ActorSystem
import javax.inject.{Inject, Singleton}
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.util.{ByteString, Timeout}
import com.mohiva.play.silhouette.api.Silhouette
import com.mohiva.play.silhouette.api.actions.UserAwareRequest
import models.{AppException, ErrorCodes, JwtEnv, Service, User}
import play.api.Configuration
import play.api.cache.SyncCacheApi
import play.api.libs.json.Json
import play.api.libs.streams.Accumulator
import play.api.mvc.BodyParser
import security.{ConfirmationCodeService, WithAnyPermission}
import services.{ProxyService, RoutingService, StreamedProxyRequest}
import ErrorCodes._
import utils.Responses._

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import events.EventsStream
import utils.RichJson._

/**
  * Proxies any request to underlying service.
  *
  * @author Sergey Khruschak
  * @author Yaroslav Derman
  */
@Singleton
class ProxyController @Inject()(silh: Silhouette[JwtEnv],
                                streamedProxy: ProxyService,
                                router: RoutingService,
                                conf: Configuration,
                                eventStream: EventsStream,
                                cache: SyncCacheApi
                               )(implicit exec: ExecutionContext, system: ActorSystem, mat: Materializer) extends BaseController {

  implicit val timeout: Timeout = Timeout(1 minute)

  val otpLength = conf.getOptional[Int]("confirmation.otp.length").getOrElse(ConfirmationCodeService.DEFAULT_OTP_LEN)

  private val specialPermission = "confirmation:not_required"

  val meRegex = """(.*)(\/me\b)(\/?.*)""".r

  implicit val servicesFormat = Json.format[Service]

  private def sourceParser = BodyParser { _ =>
    Accumulator.source[ByteString].map(Right.apply)
  }

  def listServices = silh.SecuredAction(WithAnyPermission("swagger:read")) { request =>

  val services = router.listServices
    Ok(
      Json.obj(
        "items" -> services.map(s => Json.toJsObject(s).withoutFields("secret")),
        "count" -> services.size
      )
    )
  }

  def pass(path: String) = silh.UserAwareAction.async(sourceParser) { request =>

    val path = request.path

    val service = router.matchService(path) match {
      case Some(service: Service) => service
      case None => throw AppException(ErrorCodes.ENTITY_NOT_FOUND, s"Unknown URL: $path")
    }

    validateContentType(request, service)

    val optUser = getUser(request)

    val url = service.makeUrl(replaceMe(path, optUser)).get

    streamedProxy.passToUrl(url, service.secret, StreamedProxyRequest(
      request, optUser, Option(request.body)
    ))
  }

  private def validateContentType(request: UserAwareRequest[JwtEnv, Source[ByteString, _]], service: Service): Unit = {
    if (request.method == "POST" || request.method == "PUT") {
      val validContentTypes = router.getContentType(service, request.path, request.method).getOrElse(Nil)
      val contentTypeValidation = request.contentType.exists { enctype => validContentTypes.contains(enctype) }

      if (!contentTypeValidation) {
        throw AppException(ErrorCodes.INVALID_REQUEST,
          s"Request validation failed: invalid content type ${request.contentType}. These are valid: ${validContentTypes.mkString(", ")}"
        )
      }
    }
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

  private def getUser(request: UserAwareRequest[JwtEnv, Source[ByteString, _]]): Option[User] = request.authenticator match {
    case Some(a) if a.isOauth =>
      val oauthPermissions = cache.getOrElseUpdate[List[String]]("login:" + a.id, 20.minute)(a.oauthPermissions)
      request.identity.map(_.copy(permissions = oauthPermissions))
    case _ => request.identity
  }

}