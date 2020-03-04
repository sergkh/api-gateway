package utils

import javax.inject.Inject
import models.ErrorCodes._
import models.{AppException, ErrorCodes, FormValidationException}
import play.api.http.DefaultHttpErrorHandler
import play.api.http.Status
import play.api.libs.json._
import play.api.mvc.Results._
import play.api.mvc.{RequestHeader, Result, Results}
import play.api.routing.Router
import play.api.{Configuration, Environment, Logger, OptionalSourceMapper}

import scala.compat.Platform
import scala.concurrent.Future

/**
  * Created by sergeykhruschak on 4/18/16.
  */
class ErrorHandler @Inject()(env: Environment,
                  config: Configuration,
                  sourceMapper: OptionalSourceMapper,
                  router: javax.inject.Provider[Router])
  extends DefaultHttpErrorHandler(env, config, sourceMapper, router) {

  val log = Logger("application.error.handler")

  override def onServerError(request: RequestHeader, exception: Throwable): Future[Result] = {
    val timestamp = Platform.currentTime

    exception match {
      case ex: FormValidationException[_] =>
        processFormValidationException(request, timestamp, ex)

      case ae: AppException =>
        processAppException(request, timestamp, ae)

      case jsEx@JsResultException(errors) =>

        log.warn(s"Bad request on ${request.method} ${request.path}, at $timestamp", jsEx)

        val errorInvalidRequest = generateClientResp(ErrorCodes.INVALID_REQUEST, timestamp)

        val pathErrors = Json.toJson(errors.map {
          case (path, list) =>
            val errors = list.map { error =>
              Json.obj("message" -> error.message, "args" -> error.args.map(_.toString))
            }
            path.toJsonString -> errors

        }.toMap).as[JsObject]

        Future.successful(
          BadRequest(errorInvalidRequest ++ pathErrors)
        )

      case ex: Throwable =>
        log.warn(s"Internal error occurred on ${request.method} ${request.path}, at $timestamp", ex)
        val errorInvalidRequest = generateClientResp(ErrorCodes.INTERNAL_SERVER_ERROR, timestamp, Some("Internal error occurred, sorry"))
        Future.successful(InternalServerError(errorInvalidRequest))
    }
  }

  override def onClientError(request: RequestHeader, statusCode: Int, message: String): Future[Result] = {
    val timestamp = Platform.currentTime

    log.warn(s"Client error request on ${request.method} ${request.path}, at $timestamp msg: $message")

    statusCode match {
      case Status.BAD_REQUEST =>
        Future.successful(new Results.Status(Status.BAD_REQUEST)(generateClientResp(ErrorCodes.INVALID_REQUEST, timestamp, Some(message))))
      case Status.FORBIDDEN =>
        Future.successful(new Results.Status(Status.FORBIDDEN)(generateClientResp(ErrorCodes.ACCESS_DENIED, timestamp, Some(message))))
      case Status.NOT_FOUND =>
        Future.successful(new Results.Status(Status.NOT_FOUND)(generateClientResp(ErrorCodes.ENTITY_NOT_FOUND, timestamp, Some(message))))
      case clientError if statusCode >= 400 && statusCode < 500 =>
        Future.successful(new Results.Status(clientError)(generateClientResp(ErrorCodes.INTERNAL_SERVER_ERROR, timestamp, Some(message))))
      case _ =>
        Future.successful(
          InternalServerError(generateClientResp(ErrorCodes.INTERNAL_SERVER_ERROR, timestamp, Some("Internal error occurred, sorry")))
        )
    }
  }

  @inline
  private def generateClientResp[T](code: T, timestamp: Long, message: Option[String] = None): JsObject = {
    JsonHelper.toNonemptyJson(
      "error" -> code.toString,
      "message" -> message,
      "timestamp" -> timestamp
    )
  }

  @inline
  private def processAppException(request: RequestHeader, timestamp: Long, ae: AppException): Future[Result] = {
    if (log.isDebugEnabled) {
      log.debug(s"App Exception occurred on ${request.method} ${request.path}, at $timestamp, msg: ${ae.getMessage}", ae)
    } else {
      log.warn(s"App Exception occurred on ${request.method} ${request.path}, at $timestamp, msg: ${ae.getMessage}")
    }

    Future.successful(
      new Results.Status(toHttp(ae.code))(generateClientResp(ae.code.toString, timestamp, Some(ae.message)))
    )
  }

  @inline
  private def processFormValidationException(request: RequestHeader, timestamp: Long, ex: FormValidationException[_]): Future[Result] = {
    val errorInvalidRequest = generateClientResp(ErrorCodes.INVALID_REQUEST.toString, timestamp, Some("Validation exception"))

    val json = Json.obj( "fields" -> Json.toJson(ex.form.errors.groupBy(_.key).mapValues { errors =>
      errors.map(e => Json.obj("message" -> e.message, "args" -> e.args.map(_.toString)))
    })) ++ errorInvalidRequest

    log.info(s"Validation exception on ${request.method} ${request.path}, at $timestamp, msg: $json")

    Future.successful(BadRequest(json))
  }


  def toHttp(code: String): Int = code match {
    case IDENTIFIER_REQUIRED | NON_EMPTY_SET => Status.PRECONDITION_FAILED

    case CONFIRMATION_REQUIRED => 428 // Precondition required

    case ALREADY_EXISTS | DUPLICATE_REQUEST | CONCURRENT_MODIFICATION =>
      Status.CONFLICT

    case INVALID_REQUEST | INVALID_IDENTIFIER | INVALID_TOKEN_CLAIMS =>
      Status.BAD_REQUEST

    case ENTITY_NOT_FOUND | APPLICATION_NOT_FOUND | CONFIRM_CODE_NOT_FOUND =>
      Status.NOT_FOUND

    case BLOCKED_USER | ACCESS_DENIED | EXPIRED_PASSWORD | AUTHORIZATION_FAILED =>
      Status.FORBIDDEN
    case SERVICE_UNAVAILABLE =>
      Status.SERVICE_UNAVAILABLE
    case _ =>
      Status.INTERNAL_SERVER_ERROR
  }
}
