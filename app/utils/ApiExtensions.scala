package utils

import models.FormValidationException
import play.api.data.Form
import play.api.libs.json.{JsValue, _}
import play.api.mvc.{Request, RequestHeader}

import scala.language.dynamics
import play.mvc.Http.HeaderNames
import scala.util.Try
import java.{util => ju}
import models.User
import com.mohiva.play.silhouette.impl.authenticators.JWTAuthenticator

/**
  * Some useful json methods.
  */
object RichJson {
  implicit class RichJsonObject(val js: JsObject) extends AnyVal {
    def only(fields: String*): JsObject = JsObject(js.fields.filter(f => fields.contains(f._1)))
    def without(fields: String*): JsObject = JsObject(js.fields.filterNot(f => fields.contains(f._1)))
    def withoutFields(fields: String*): JsObject = filterFields(n => !fields.contains(n))
    def filterNull: JsObject = JsObject(js.fields.filter(_._2 != JsNull))

    def rename(from: String, to: String): JsObject = JsObject(
      (Seq(to -> (js \ from).get) ++ js.fields.filterNot(kv => from.equals(kv._1))).toMap
    )

    def filterFields(f: String => Boolean): JsObject = JsObject(js.fields.filter(p => f(p._1)))

    def withFields(fields: (String, JsValue)*): JsObject = JsObject(js.fields ++ fields)

    def hasField(field: String): Boolean = js.fields.exists(p => field.equals(p._1))

    // smarter version of ++
    def &(other: JsObject): JsObject = if (js.fields.isEmpty) {
      other
    } else {
      if (other.fields.isEmpty) js else js ++ other
    }
  }

}

/**
  * Util object containing predefined common server responses and response builders.
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
      case _ => throw new RuntimeException("Unsupported request header type for form extracting")
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

    def basicAuth: Option[(String, String)] = 
      r.headers.get(HeaderNames.AUTHORIZATION).map(_.split(" ")).collect {
        case Array("Bearer", data) => Try {
          val Array(user, pass) = new String(ju.Base64.getDecoder.decode(data)).split(":")
          user -> pass
        }.toOption
      }.flatten
  }  
}

object JwtExtension {
  import RichJson._
  implicit class RichJWTAuthenticator(val auth: JWTAuthenticator) extends AnyVal {
    def withUserInfo(u: User, scope: Option[String] = None, audience: Option[String] = None): JWTAuthenticator = auth.copy(
      customClaims = Some(Json.obj(
        "id" -> u.id,
        "roles" -> u.roles,
        "name" -> u.fullName,
        "email" -> u.email,
        "permissions" ->  scope.fold(u.permissions)(_.split(" ").toList),
        "aud" -> audience
      ).filterNull)
    )
  }

}