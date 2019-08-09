package controllers

//scalastyle:off public.methods.have.type

import java.util.regex.Pattern

import akka.actor.ActorSystem
import javax.inject.{Inject, Singleton}
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.util.{ByteString, Timeout}
import com.impactua.bouncer.commons.models.ResponseCode
import com.impactua.bouncer.commons.models.exceptions.AppException
import com.impactua.bouncer.commons.security.ConfirmationProvider
import com.mohiva.play.silhouette.api.Silhouette
import com.mohiva.play.silhouette.api.actions.UserAwareRequest
import models.AppEvent.OtpGeneration
import models.{ConfirmationCode, JwtEnv, Service, User}
import play.api.Configuration
import play.api.cache.SyncCacheApi
import play.api.libs.json.Json
import play.api.libs.streams.Accumulator
import play.api.mvc.BodyParser
import security.{ConfirmationCodeService, WithAnyPermission}
import services.{ProxyService, RoutingService, StreamedProxyRequest}
import utils.ProxyRoutesParser
import utils.ProxyRoutesParser.toRegex
import utils.Responses._

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import com.impactua.bouncer.commons.utils.RichJson._
import events.EventsStream

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
                                confirmationService: ConfirmationCodeService,
                                confirmationValidator: ConfirmationProvider,
                                cache: SyncCacheApi
                               )(implicit exec: ExecutionContext, system: ActorSystem, mat: Materializer) extends BaseController {

  implicit val timeout: Timeout = Timeout(1 minute)

  val otpLength = conf.getOptional[Int]("confirmation.otp.length").getOrElse(ConfirmationCodeService.DEFAULT_OTP_LEN)

  private val specialPermission = "confirmation:not_required"

  private val routes4confirmation: List[(String, Pattern)] = conf.getOptional[String]("confirmation.urls")
                                                         .map(_.split(",").toSeq).getOrElse(Nil)
                                                         .map(_.split(" ").toList).collect {
    case List(method, path) => method -> toRegex(ProxyRoutesParser.parse(path), withSlash = false).pattern
  }.toList

  if (routes4confirmation.nonEmpty) {
    log.info(s"Confirmation required for \n${routes4confirmation.mkString("\n")}")
  }

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

    val requireConfirmation = routes4confirmation.exists {
      case (m, p) => m == request.method && p.matcher(request.path).matches()
    }

    if (requireConfirmation) {
      confirmationCheck(request)
    }

    val path = request.path

    val service = router.matchService(path) match {
      case Some(service: Service) => service
      case None => throw AppException(ResponseCode.ENTITY_NOT_FOUND, s"Unknown URL: $path")
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
        throw AppException(ResponseCode.INVALID_REQUEST,
          s"Request validation failed: invalid content type ${request.contentType}. These are valid: ${validContentTypes.mkString(", ")}")
      }
    }
  }

  private def confirmationCheck(request: UserAwareRequest[JwtEnv, Source[ByteString, _]]): Unit = {
    val confirmed = confirmationValidator.verifyConfirmed(request)

    if (!confirmed) {
      request.identity match {
        case Some(user) if user.hasPermission(specialPermission) =>
        case Some(user) if user.phone.isEmpty && user.email.isEmpty =>
          throw AppException(ResponseCode.IDENTIFIER_REQUIRED, "User identifier required")
        case Some(user)=>
          storeRequestForConfirmation(request, user)
          throw AppException(ResponseCode.CONFIRMATION_REQUIRED, "Otp confirmation required using POST /users/confirm")
        case None =>
          throw AppException(ResponseCode.ACCESS_DENIED, "User required for confirmation processing")
      }
    }
  }

  private def storeRequestForConfirmation(request: UserAwareRequest[JwtEnv, Source[ByteString, _]], user: User): Unit = {
    request.body.runFold(ByteString.empty)(_ ++ _).map { bytes =>
      val (otp, code) = ConfirmationCode.generatePair(user.identifier, request, otpLength, Some(bytes))
      confirmationService.create(code)
      eventStream.publish(OtpGeneration(Some(user.uuidStr), user.email, user.phone, otp, request))
    }
  }

  private def replaceMe(rootPath: String, userOpt: Option[User]) = {
    rootPath match {
      case meRegex(path, _, other) if userOpt.nonEmpty =>
        path + "/" + userOpt.map(_.uuidStr).get + other

      case meRegex(_, _, _) if userOpt.isEmpty =>
        throw AppException(ResponseCode.ACCESS_DENIED, "User not authenticated")

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