package utils

import java.util.Base64

import com.mohiva.play.silhouette.api.actions.{SecuredRequest, UserAwareRequestHeader}
import models.{FormValidationException, JwtEnv}
import events.RequestInfo
import play.api.data.Form
import play.api.http.HeaderNames
import play.api.mvc.{Request, RequestHeader}

import scala.util.Try
import zio.Task

/**
 * Util object containing predefined common server responses and response builders.
 *
 * @author Sergey Khruschak
 * @author Yaroslav Derman
 */
object RichRequest {

  private val HTTP_IP_HEADERS = Seq(
    "X-Forwarded-For", "X-Real-IP", "Proxy-Client-IP", "HTTP_CLIENT_IP", "HTTP_X_FORWARDED_FOR", "WL-Proxy-Client-IP"
  )

  implicit class RichRequest(val r: RequestHeader) extends AnyVal {
    def asForm[T](form: Form[T]): T = r match {
      case req: Request[_] => form.bindFromRequest()(req).fold(
        error => throw FormValidationException(error),
        data => data
      )
      case _ => throw new Exception("Unsupported request header type for form extracting")
    }

    def asFormIO[T](form: Form[T]): Task[T] = r match {
      case req: Request[_] => form.bindFromRequest()(req).fold(
        error => Task.fail(FormValidationException(error)),
        data => Task.succeed(data)
      )
      case _ => Task.fail(new Exception("Unsupported request header type for form extracting"))
    }

    /**
     * Binds a form only from a query parameters, without checking a body.
     * Required if GET/DELETE has invalid Content-Type
     *
     * @param form form to bind
     * @tparam T form type
     * @return binded data or exception
     */
    def asQueryForm[T](form: Form[T]): T = r match {
      case req: Request[_] => form.bindFromRequest(req.queryString).fold(
        error => throw FormValidationException(error),
        data => data
      )
      case _ => throw new RuntimeException("Unsupported request header type for form extracting")
    }

    def clientIp: String = {
      val header = HTTP_IP_HEADERS.find(name => r.headers.get(name).exists(h => h.nonEmpty && !h.equalsIgnoreCase("unknown")))

      header match {
        case Some(name) =>
          val header = r.headers(name)
          if (header.contains(",")) header.split(",").head else header
        case None       => r.remoteAddress
      }
    }

    def clientAgent: String = r.headers.get("User-Agent").getOrElse("not set")

    def reqInfo: RequestInfo = r match {
      case userReq: UserAwareRequestHeader[JwtEnv] =>
        RequestInfo(clientIp, clientAgent, userReq.identity.map(_.id))
      case securedReq: SecuredRequest[JwtEnv, _] =>
        RequestInfo(clientIp, clientAgent, Some(securedReq.identity.id))
      case _ => RequestInfo(clientIp, clientAgent, None)
    }

    def basicAuth: Option[(String, String)] =
      r.headers.get(HeaderNames.AUTHORIZATION).map(_.split(" ")).collect {
        case Array("Basic", data) => Try {
          val Array(user, pass) = new String(Base64.getDecoder.decode(data)).split(":")
          user -> pass
        }.toOption
      }.flatten
  }
}
