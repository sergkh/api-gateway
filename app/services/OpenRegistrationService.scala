package services

//scalastyle:off magic.number

import java.security.SecureRandom

import akka.actor.ActorSystem
import com.fotolog.redis.RedisClient
import com.mohiva.play.silhouette.api.LoginInfo
import com.mohiva.play.silhouette.api.util.PasswordHasherRegistry
import com.mohiva.play.silhouette.impl.providers.CredentialsProvider
import forms.RegisterForm
import javax.inject.Inject
import models.RegistrationData._
import models.{AppException, ErrorCodes, OpenRegistrationData, RegistrationData}
import org.slf4j.LoggerFactory
import play.api.Configuration
import play.api.libs.json.Json
import play.api.mvc.{Request, RequestHeader}
import scala.concurrent.duration.Duration
import scala.concurrent.{ExecutionContext, Future}
import play.api.libs.json.JsObject
import utils.RichRequest._

trait RegistrationService {
  def userRegistrationRequest(req: Request[_]): Future[RegistrationData]
  def getUnconfirmedRegistrationData(login: String): Future[Option[RegistrationData]]
}

trait UserExistenceService {
  def exists(id: String): Future[Boolean]
}

/**
  * @author Yaroslav Derman <yaroslav.derman@gmail.com>.
  */
class OpenRegistrationService @Inject()(config: Configuration,
                                        passwordHashers: PasswordHasherRegistry,
                                        userService: UserExistenceService
                                       )(implicit ctx: ExecutionContext, system: ActorSystem) extends RegistrationService {

  val log = LoggerFactory.getLogger(getClass)

  val redis = RedisClient(config.get[String]("redis.host"))

  final val emailCode = "emailCode:"
  final val emailTtl = 24 * 60 * 60

  final val phoneCode = "phoneCode:"
  final val phoneTtl = 10 * 60

  val requirePass = config.get[Boolean]("app.requirePassword")

  override def userRegistrationRequest(req: Request[_]): Future[RegistrationData] = {
    val data = req.asForm(RegisterForm.openForm)

    if (requirePass && data.password.isEmpty) {
      log.warn("Password required but not set")
      throw AppException(ErrorCodes.INVALID_REQUEST, "Invalid password")
    }

    userService.exists(data.loginFormatted).flatMap {
      case true =>
        throw AppException(ErrorCodes.ALREADY_EXISTS, "User with such login already exists")

      case false =>
        val passwordInfoOpt = data.password.map(passwordHashers.current.hash).map(_.password)

        val (key, ttl) = if (data.loginFormatted.contains("@")) emailCode -> emailTtl else phoneCode -> phoneTtl

        val openData = OpenRegistrationData(data.loginFormatted, passwordInfoOpt, Some(ttl))

        redis.setNxAsync[String](key + data.loginFormatted, Json.toJson(openData).toString(), ttl).map {
          case true => openData
          case false =>
            log.warn("Can't write to redis. Probably this is try to re registering unconfirmed user")
            throw AppException(ErrorCodes.DUPLICATE_REQUEST, "Probably this is a try to re-register unconfirmed user. Use resend-otp endpoint instead")
        }
    }
  }

  def getUnconfirmedRegistrationData(login: String): Future[Option[RegistrationData]] = {
    val key = if (login.contains("@")) emailCode else phoneCode

    redis.getAsync[String](key + login.toLowerCase).map {
      _.map { data => Json.parse(data).as[OpenRegistrationData] }
    }
  }
}
