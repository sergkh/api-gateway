package utils


import models.AppException

import scala.annotation.implicitNotFound
import scala.concurrent.{ExecutionContext, Future}

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

}
