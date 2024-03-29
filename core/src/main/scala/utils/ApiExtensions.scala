package utils

import com.mohiva.play.silhouette.impl.authenticators.JWTAuthenticator
import models.{AppException, User}
import play.api.libs.json._
import zio._

import scala.annotation.implicitNotFound
import scala.concurrent.{ExecutionContext, Future}
import scala.language.dynamics
import scala.util.Try

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


object JwtExtension {
  import RichJson._
  implicit class RichJWTAuthenticator(val auth: JWTAuthenticator) extends AnyVal {
    def withUserInfo(u: User, 
                     scope: Option[String] = None, 
                     audience: Option[String] = None,
                     copyFields: List[String] = Nil,
                     ): JWTAuthenticator = auth.copy(
      customClaims = Some((Json.obj(
        "id" -> u.id,
        "scope" -> scope,
        "aud" -> Option(audience.toList).filterNot(_.isEmpty)
      ) ++ userTokenData(u, scope, copyFields)).filterNull)
    )

    def asPartialUser: Option[User] = auth.customClaims.flatMap { claims => Try{
        val json = claims.as[JsObject]
        User(
          id = (json \ "id").as[String],
          email = (json \ "email").asOpt[String],
          phone = (json \ "phone_number").asOpt[String],
          roles = (json \ "roles").asOpt[List[String]].getOrElse(Nil),
          flags = (json \ "flags").asOpt[List[String]].getOrElse(Nil),
          permissions = (json \ "scope").asOpt[String].map(_.split(" ").toList)
        )
      }.toOption
    }

    /**
      * Dynamically add users fields to the resulting token, depending on configured list of fields.
      */
    private def userTokenData(u: User, scope: Option[String], flds: List[String]): JsObject = Json.obj(
      "roles" -> Option(u.roles).filter(r => r.nonEmpty && flds.contains("roles")),
      "flags" -> Option(u.flags).filter(r => r.nonEmpty && flds.contains("flags")),
      "scope" -> Option(scope.fold(u.permissions.getOrElse(Nil))(_.split(" ").toList).mkString(" ")).filter(p => p.nonEmpty && flds.contains("scope")),
      "email" -> u.email.filter(_ => flds.contains("email")),
      "email_verified" -> Option(!u.hasFlag(User.FLAG_EMAIL_NOT_CONFIRMED)).filter(_ => flds.contains("email")),
      "phone_number" -> u.phone.filter(_ => flds.contains("phone_number")),
      "phone_number_verified" -> Option(!u.hasFlag(User.FLAG_PHONE_NOT_CONFIRMED)).filter(_ => flds.contains("phone_number")),      
    )
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
  implicit class RichTaks[A, E <: Throwable](val t: ZIO[ZEnv, E, A]) extends AnyVal {
    implicit def toUnsafeFuture: Future[A] = zio.Runtime.default.unsafeRunToFuture(t)
    implicit def unsafeRun: A = zio.Runtime.default.unsafeRun(t)
  }

  implicit class RichOptTask[A](val t: Task[Option[A]]) extends AnyVal {
    def orFail(ex: Exception): Task[A] = t.flatMap(_.map(a => Task.succeed(a)).getOrElse(Task.fail(ex)))
  }


}
