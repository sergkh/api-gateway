package services.impl

//scalastyle:off magic.number

import akka.actor.ActorSystem
import com.fotolog.redis.RedisClient
import com.impactua.bouncer.commons.models.exceptions.AppException
import com.impactua.bouncer.commons.models.{ResponseCode, User => CommonUser}
import com.impactua.bouncer.commons.utils.RichRequest._
import com.impactua.bouncer.commons.utils.{JsonHelper, RandomStringGenerator}
import com.mohiva.play.silhouette.api.LoginInfo
import com.mohiva.play.silhouette.api.util.PasswordHasher
import com.mohiva.play.silhouette.impl.providers.CredentialsProvider
import events.{EventsStream, ReferralRegistrationEvent, Signup}
import forms.RegisterForm
import javax.inject.Inject
import models.RegistrationData._
import models._
import play.api.Configuration
import play.api.libs.json.Json
import play.api.mvc.{Request, RequestHeader}
import services.{ExtendedUserInfoService, RegistrationService, UserIdentityService}

import scala.compat.Platform
import scala.concurrent.duration.Duration
import scala.concurrent.{ExecutionContext, Future}

/**
  * @author Yaroslav Derman <yaroslav.derman@gmail.com>
  */
class ReferralRegistrationService @Inject()(config: Configuration,
                                            passwordHasher: PasswordHasher,
                                            eventBus: EventsStream,
                                            extendedUserInfoService: ExtendedUserInfoService,
                                            val userService: UserIdentityService)
                                           (implicit ctx: ExecutionContext, system: ActorSystem) extends RegistrationService {

  val redis = RedisClient(config.get[String]("redis.host"))

  final val emailCode = "emailCode:"
  final val emailTtl = 24 * 60 * 60

  final val phoneCode = "phoneCode:"
  final val phoneTtl = 10 * 60

  val passwordPeriod = config.getOptional[Duration]("app.defaultPasswordTTL").map(_.toMillis)

  log.info("Referral registration schema enabled")

  override def userRegistrationRequest(req: Request[_]): Future[RegistrationData] = {
    val data = req.asForm(RegisterForm.referralForm)
    val loginInfo = LoginInfo(CredentialsProvider.ID, data.loginFormatted)

    userService.retrieve(loginInfo).flatMap {
      case Some(_) =>
        throw AppException(ResponseCode.ALREADY_EXISTS, "User with such login already exists")

      case None =>
        val passwordInfo = passwordHasher.hash(data.password)
        val (key, ttl) = if (data.loginFormatted.contains("@")) emailCode -> emailTtl else phoneCode -> phoneTtl

        val referralData = ReferralRegistrationData(data.loginFormatted, passwordInfo.password, data.invitationCode, Some(ttl))

        redis.setNxAsync[String](key + data.loginFormatted, Json.toJson(referralData).toString(), ttl).map {
          case true => referralData
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

  override def confirmUserRegistrationRequest(login: String)(implicit requestHeader: RequestHeader): Future[User] = {
    val key = if (login.contains("@")) emailCode else phoneCode

    redis.getAsync[String](key + login.toLowerCase).flatMap {
      case Some(query) =>
        val registerData = Json.parse(query).as[ReferralRegistrationData]

        val user = User(
          email = registerData.optEmail.map(_.toLowerCase),
          phone = registerData.optPhone,
          passHash = registerData.passHash,
          passTtl = passwordPeriod
        )

        userService.save(user).flatMap { dbUser =>
          val extendedInfo = JsonHelper.toNonemptyJson(
            "_id" -> dbUser.uuid,
            "invitationCode" -> RandomStringGenerator.generateSecret(8),
            "inviterCode" -> registerData.invitationCode,
            "created" -> Platform.currentTime
          )

          extendedUserInfoService.create(extendedInfo)

          registerData.invitationCode.map { inviteCode =>
            extendedUserInfoService.retrieve(Json.obj("invitationCode" -> inviteCode), "_id").map(_.foreach { invitor =>
              val inviterUuid = (invitor \ "_id").as[Long]
              log.info(s"Sending ReferralRegistrationEvent: ${ReferralRegistrationEvent(dbUser, inviterUuid)}")
              eventBus.publish(ReferralRegistrationEvent(dbUser, inviterUuid))
            })
          }

          eventBus.publish(Signup(user, requestHeader)) map { _ =>
            log.info(s"User $dbUser successfully created")
            dbUser

          }
        }

      case None => throw new RuntimeException("Query not exist for email ")
    }
  }
}
