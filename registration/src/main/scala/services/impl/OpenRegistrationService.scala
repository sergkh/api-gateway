package services.impl

//scalastyle:off magic.number

import java.security.SecureRandom

import akka.actor.ActorSystem
import com.fotolog.redis.RedisClient
import com.mohiva.play.silhouette.api.LoginInfo
import com.mohiva.play.silhouette.api.util.PasswordHasherRegistry
import com.mohiva.play.silhouette.impl.providers.CredentialsProvider
import events.{EventsStream, Signup}
import forms.RegisterForm
import javax.inject.Inject
import models.RegistrationData._
import models.{AppException, ErrorCodes, OpenRegistrationData, RegistrationData, User}
import org.slf4j.LoggerFactory
import play.api.Configuration
import play.api.libs.json.Json
import play.api.mvc.{Request, RequestHeader}
import services.{RegistrationService, UserIdentityService}

import scala.concurrent.duration.Duration
import scala.concurrent.{ExecutionContext, Future}

/**
  * @author Yaroslav Derman <yaroslav.derman@gmail.com>.
  */
class OpenRegistrationService @Inject()(config: Configuration,
                                        passwordHashers: PasswordHasherRegistry,
                                        eventBus: EventsStream,
                                        val userService: UserIdentityService
                                       )(implicit ctx: ExecutionContext, system: ActorSystem) extends RegistrationService {

  val log = LoggerFactory.getLogger(getClass)

  val redis = RedisClient(config.get[String]("redis.host"))

  final val emailCode = "emailCode:"
  final val emailTtl = 24 * 60 * 60

  final val phoneCode = "phoneCode:"
  final val phoneTtl = 10 * 60

  val requirePass = config.get[Boolean]("app.requirePassword")

  override def userRegistrationRequest(req: Request[_]): Future[RegistrationData] = {
    val data = RegisterForm.openForm.bindFromRequest()(req).fold(
      error => throw AppException(ErrorCodes.INVALID_REQUEST, error.toString),
      data => data
    )

    if (requirePass && data.password.isEmpty) {
      log.warn("Password required but not set")
      throw AppException(ErrorCodes.INVALID_REQUEST, "Invalid password")
    }

    userService.retrieve(LoginInfo(CredentialsProvider.ID, data.loginFormatted)).flatMap {
      case Some(_) =>
        throw AppException(ErrorCodes.ALREADY_EXISTS, "User with such login already exists")

      case None =>
        val passwordInfo = passwordHashers.current.hash(data.password.getOrElse {
          val random = new Array[Byte](50)
          new SecureRandom().nextBytes(random)
          new String(random)
        })

        val (key, ttl) = if (data.loginFormatted.contains("@")) emailCode -> emailTtl else phoneCode -> phoneTtl

        val openData = OpenRegistrationData(data.loginFormatted, passwordInfo.password, Some(ttl))

        redis.setNxAsync[String](key + data.loginFormatted, Json.toJson(openData).toString(), ttl).map {
          case true => openData
          case false =>
            log.warn("Can't write to redis. Probably this is try to re registering unconfirmed user")
            throw AppException(ErrorCodes.DUPLICATE_REQUEST, "Probably this is a try to re-register unconfirmed user. Use resend-otp endpoint instead")
        }
    }
  }

  def getUserByLogin(login: String): Future[Option[User]] = {
    val key = if (login.contains("@")) emailCode else phoneCode

    redis.getAsync[String](key + login.toLowerCase).map {
      _.map { data =>
        val registerData = Json.parse(data).as[OpenRegistrationData]

        User(
          email = registerData.optEmail.map(_.toLowerCase),
          phone = registerData.optPhone,
          passHash = registerData.passHash
        )
      }
    }
  }

  def confirmUserRegistrationRequest(login: String)(implicit requestHeader: RequestHeader): Future[User] = {
    val key = if (login.contains("@")) emailCode else phoneCode

    redis.getAsync[String](key + login.toLowerCase).flatMap {
      case Some(query) =>
        val registerData = Json.parse(query).as[OpenRegistrationData]

        val user = User(
          email = registerData.optEmail.map(_.toLowerCase),
          phone = registerData.optPhone,
          passHash = registerData.passHash
        )

        userService.save(user).flatMap { u =>
          eventBus.publish(Signup(user, requestHeader)) map { _ =>
            log.info(s"User $user successfully created")
            u
          }
        }

      case None => throw new RuntimeException("Query not exist for email ")
    }
  }
}
