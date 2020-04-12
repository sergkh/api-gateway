package utils

import zio._
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

import models.AppException

import scala.annotation.implicitNotFound
import scala.concurrent.{ExecutionContext, Future}

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
        case Array("Basic", data) => Try {
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
        "roles" -> Option(u.roles).filterNot(_.isEmpty),
        "name" -> u.fullName,
        "email" -> u.email,
        "permissions" -> Option(scope.fold(u.permissions.getOrElse(Nil))(_.split(" ").toList)).filterNot(_.isEmpty),
        "aud" -> Option(audience.toList).filterNot(_.isEmpty)
      ).filterNull)
    )

    def asPartialUser: Option[User] = auth.customClaims.flatMap { claims => Try{
        val json = claims.as[JsObject]
        User(
          id = (json \ "id").as[String],
          email = (json \ "email").asOpt[String],
          roles = (json \ "roles").asOpt[List[String]].getOrElse(Nil),
          permissions = (json \ "permissions").asOpt[List[String]]
        )
      }.toOption
    }
  }

}

object FutureUtils {
  @implicitNotFound("Provide an implicit instance of converter from custom error code into HTTP code")
  def appFail[T, E](code: String, message: String): Future[T] = Future.failed(AppException(code, message))

  def conditional[A](cond: Boolean, f: => Future[A]): Future[_] = if (cond) f else Future.unit

  def conditionalFail[A](cond: Boolean, code: String, message: String): Future[_] = if (cond) appFail(code, message) else Future.unit

  implicit class RichFuture[A](val f: Future[A]) extends AnyVal {
    /** Future sequence operator (monad sequence).
      * Executes both, returns result of the second Future */
    def >>[B](f2: => Future[B])(implicit ec: ExecutionContext): Future[B] = f.flatMap(_ => f2)

    /** Future sequence operator (monad sequence).
      * Executes both, returns result of the first Future */
    def <<[B](f2: => Future[B])(implicit ec: ExecutionContext): Future[A] = for { a <- f; _ <- f2 } yield a
  }

  implicit class RichOptFuture[A](val f: Future[Option[A]]) extends AnyVal {
    def orFail(t: Exception)(implicit ec: ExecutionContext): Future[A] = f.map(_.getOrElse(throw t))
  }

}

object TaskExt {

  def failIf[A](cond: Boolean, code: String, message: String): Task[_] = if (cond) Task.fail(AppException(code, message)) else Task.unit

  /**
    * Some extensions needed to make transition to ZIO easier
    */
  implicit class RichTaks[A](val t: Task[A]) extends AnyVal {
    implicit def toUnsafeFuture: Future[A] = zio.Runtime.default.unsafeRunToFuture(t)
  }

  implicit class RichOptTask[A](val t: Task[Option[A]]) extends AnyVal {
    def orFail(ex: Exception): Task[A] = t.flatMap(_.map(a => Task.succeed(a)).getOrElse(Task.fail(ex)))
  }


}