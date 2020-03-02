package security

import java.util.Base64

import javax.inject.Inject
import akka.event.slf4j.Logger
import utils.StringHelpers._
import models.{AppException, ErrorCodes, ThirdpartyApplication}
import play.api.libs.json.Json
import play.api.mvc._
import play.modules.reactivemongo.ReactiveMongoApi
import play.mvc.Http
import reactivemongo.play.json._
import reactivemongo.play.json.collection._
import services.UserService
import models.ErrorCodes._

import scala.concurrent.{ExecutionContext, Future}

/**
  * @author Yaroslav Derman <yaroslav.derman@gmail.com>.
  */

class Oauth @Inject()(mongo: ReactiveMongoApi,
                      bodyParser: BodyParsers.Default,
                      userService: UserService)(implicit ctx: ExecutionContext) {

  def appsCollection = mongo.database.map(_.collection[JSONCollection](ThirdpartyApplication.COLLECTION_NAME))

  object Secured extends ActionBuilder[Request, AnyContent] {

    private val MSG_WRONG_AUTH_DATA = "Wrong oauth authorization data"

    val log = Logger("OauthSecured")

    override protected def executionContext: ExecutionContext = ctx

    override def parser: BodyParser[AnyContent] = bodyParser

    override def invokeBlock[A](request: Request[A], block: (Request[A]) => Future[Result]): Future[Result] = {
      request.headers.get(Http.HeaderNames.AUTHORIZATION) match {
        case Some(authHeader) if authHeader.contains("Basic") =>

          val Array(appId, appSecret) = new String(Base64.getDecoder.decode(authHeader.replaceFirst("Basic ", ""))).split(":")

          if (!isNumberString(appId)) {
            log.info("Invalid application id: " + appId)
            throw AppException(ErrorCodes.INVALID_REQUEST, "Invalid application id")
          }

          appsCollection.flatMap(_.find(Json.obj("_id" -> appId)).one[ThirdpartyApplication]).flatMap {
            case Some(application) =>

              application.checkSecret(appSecret)

              userService.getByAnyId(application.userId.toString).flatMap { user =>
                block(request)
              }

            case None => throw AppException(ErrorCodes.APPLICATION_NOT_FOUND, s"Application $appId not found")
          }

        case Some(wrongAuth) =>
          log.warn(s"Wrong oauth authorization data on ${request.method} ${request.path} with '$wrongAuth'")
          Future.successful(Results.Forbidden(MSG_WRONG_AUTH_DATA))
        case None =>
          log.info(s"Unauthorized error on ${request.method} ${request.path}")
          Future.successful(Results.Unauthorized(MSG_WRONG_AUTH_DATA))
      }
    }
  }

}


