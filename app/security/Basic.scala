package security

import java.util.Base64

import akka.event.slf4j.Logger
import javax.inject.Inject
import models.{AppException, ErrorCodes}
import play.api.mvc._
import play.mvc.Http

import scala.concurrent.{ExecutionContext, Future}

/**
  * @author Yaroslav Derman <yaroslav.derman@gmail.com>.
  */

class Basic @Inject()(bodyParser: BodyParsers.Default)(implicit ctx: ExecutionContext) {

  def secured(login: String, pass: String): Secured = new Secured(login, pass)

  class Secured(login: String, pass: String) extends ActionBuilder[Request, AnyContent] {

    private val MSG_WRONG_AUTH_DATA = "Wrong authorization data"

    val log = Logger("BasicSecured")

    override protected def executionContext: ExecutionContext = ctx

    override def parser: BodyParser[AnyContent] = bodyParser

    override def invokeBlock[A](request: Request[A], block: Request[A] => Future[Result]): Future[Result] = {
      request.headers.get(Http.HeaderNames.AUTHORIZATION) match {
        case Some(authHeader) if authHeader.contains("Basic") =>

          val Array(basicLogin, basicPass) = new String(Base64.getDecoder.decode(authHeader.replaceFirst("Basic ", ""))).split(":")

          if (login == basicLogin && pass == basicPass) {
            block(request)
          } else {
            log.warn(s"Wrong basic authorization data on ${request.method} ${request.path} ($login != $basicLogin || $pass != $basicPass)")
            throw AppException(ErrorCodes.AUTHORIZATION_FAILED, MSG_WRONG_AUTH_DATA)
          }

        case Some(wrongAuth) =>
          log.warn(s"Wrong basic authorization data on ${request.method} ${request.path} with '$wrongAuth'")
          throw AppException(ErrorCodes.ACCESS_DENIED, MSG_WRONG_AUTH_DATA)

        case None =>
          log.info(s"Unauthorized error on ${request.method} ${request.path}")
          throw AppException(ErrorCodes.ACCESS_DENIED, "Authorization required")
      }
    }
  }

}


