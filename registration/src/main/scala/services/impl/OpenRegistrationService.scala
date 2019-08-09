package services.impl

//scalastyle:off magic.number

import akka.actor.ActorSystem
import com.fotolog.redis.RedisClient
import com.impactua.bouncer.commons.models.ResponseCode._
import com.impactua.bouncer.commons.models.exceptions.AppException
import com.impactua.bouncer.commons.models.{ResponseCode, User => CommonUser}
import com.impactua.bouncer.commons.utils.RandomStringGenerator
import com.impactua.bouncer.commons.utils.RichRequest._
import com.mohiva.play.silhouette.api.LoginInfo
import com.mohiva.play.silhouette.api.util.PasswordHasher
import com.mohiva.play.silhouette.impl.providers.CredentialsProvider
import events.{EventsStream, Signup}
import forms.RegisterForm
import javax.inject.Inject
import models.RegistrationData._
import models.{OpenRegistrationData, RegistrationData, User}
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
                                        passwordHasher: PasswordHasher,
                                        eventBus: EventsStream,
                                        val userService: UserIdentityService
                                       )(implicit ctx: ExecutionContext, system: ActorSystem) extends RegistrationService {

  val redis = RedisClient(config.get[String]("redis.host"))

  final val emailCode = "emailCode:"
  final val emailTtl = 24 * 60 * 60

  final val phoneCode = "phoneCode:"
  final val phoneTtl = 10 * 60

  val requirePass = config.get[Boolean]("app.requirePassword")
  val passwordPeriod = config.getOptional[Duration]("app.passwordPeriod").map(_.toMillis)

  log.info("Open registration schema enabled")

  override def userRegistrationRequest(req: Request[_]): Future[RegistrationData] = {
    val data = req.asForm(RegisterForm.openForm)

    if (requirePass && data.password.isEmpty) {
      log.warn("Password required but not set")
      throw AppException(INVALID_PASSWORD, "Invalid password")
    }

    userService.retrieve(LoginInfo(CredentialsProvider.ID, data.loginFormatted)).flatMap {
      case Some(_) =>
        throw AppException(ResponseCode.ALREADY_EXISTS, "User with such login already exists")

      case None =>
        val passwordInfo = passwordHasher.hash(data.password.getOrElse(RandomStringGenerator.generateSecret(10)))

        val (key, ttl) = if (data.loginFormatted.contains("@")) emailCode -> emailTtl else phoneCode -> phoneTtl

        val openData = OpenRegistrationData(data.loginFormatted, passwordInfo.password, Some(ttl))

        redis.setNxAsync[String](key + data.loginFormatted, Json.toJson(openData).toString(), ttl).map {
          case true => openData
          case false =>
            log.warn("Can't write to redis. Probably this is try to re registering unconfirmed user")
            throw AppException(ResponseCode.DUPLICATE_REQUEST, "Probably this is a try to re-register unconfirmed user. Use resend-otp endpoint instead")
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
          passHash = registerData.passHash,
          passTtl = passwordPeriod
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
