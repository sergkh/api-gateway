package controllers

import zio._
import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext.Implicits._
import scala.concurrent.Future
import scala.language.implicitConversions
import com.mohiva.play.silhouette.api.{LoginInfo, Silhouette}
import services.UserService
import play.api.Configuration
import models._
import events.EventsStream
import services.RegistrationFiltersChain
import akka.actor.ActorSystem
import security.ConfirmationCodeService
import models.AppEvent.{OtpGeneration, Signup}
import play.api.mvc.RequestHeader
import utils.FutureUtils._
import akka.util.ByteString
import play.api.libs.json.Json
import com.fotolog.redis.RedisClient
import play.api.mvc.Request
import utils.RichRequest._
import com.mohiva.play.silhouette.api.util.PasswordHasherRegistry
import forms.UserForm
import play.api.libs.json.JsValue
import akka.http.scaladsl.util.FastFuture
import play.api.data.validation.Valid

@Singleton
class RegistrationController @Inject()(silh: Silhouette[JwtEnv],
                                      userService: UserService,
                                      confirmationService: ConfirmationCodeService,
                                      config: Configuration,
                                      eventBus: EventsStream,
                                      passwordHashers: PasswordHasherRegistry,
                                      registrationFilters: RegistrationFiltersChain)(implicit system: ActorSystem) extends BaseController {
  
  val otpLength = config.getOptional[Int]("confirmation.otp.length").getOrElse(ConfirmationCodeService.DEFAULT_OTP_LEN)
  val otpEmailLength = config.getOptional[Int]("confirmation.otp.email-length").getOrElse(otpLength)
  val otpEmailTTLSeconds = 3 * 24 * 60 * 60 // 3 days, TODO: make a setting
  val otpPhoneTTLSeconds = 10 * 60

  val requirePass    = config.get[Boolean]("app.requirePassword")
  val requireFields  = config.get[String]("app.requireFields").split(",").map(_.trim).toList

  log.info(s"Required user identifiers: $requireFields. Password required: $requirePass")

  implicit val createUserFormat = Json.reads[UserForm.CreateUser].map { c =>
    import forms.FormConstraints._
   
    require(c.email.forall(email => emailAddress.apply(email) == Valid), "Email format is invalid")
    require(c.phone.forall(phone => phoneNumber(phone) == Valid), "Email format is invalid")  
    require(c.password.forall(pass => passwordConstraint(pass) == Valid), "Password format is invalid")

   c
  }
    
  def register = silh.UserAwareAction.async(parse.json) { implicit request =>
    for {
      registerBody  <- registrationFilters(request.body)
      user          <- userRegistrationRequest(registerBody)
      _             <- Task.fromFuture(ec => eventBus.publish(Signup(user, request)))
    } yield Ok(Json.toJson(user))
  }

  private def userRegistrationRequest(registerBody: JsValue): Task[User] = {
    val data = registerBody.as[UserForm.CreateUser]

    val user = User(
      email = data.email,
      phone = data.phone,
      firstName = data.firstName.filter(_.isEmpty),
      lastName = data.lastName.filter(_.isEmpty),
      password = data.password.map(passwordHashers.current.hash),
      flags = data.email.map(_ => User.FLAG_EMAIL_NOT_CONFIRMED).toList ++ data.phone.map(_ => User.FLAG_PHONE_NOT_CONFIRMED).toList
    )

    val errors = User.validateNewUser(user, requireFields, requirePass)

    if (errors.nonEmpty) {
      log.warn(s"Registration fields were required but not set: ${errors.mkString("\n")}")
      Task.fail(AppException(ErrorCodes.INVALID_REQUEST, errors.mkString("\n")))
    } else {

      for {
        emailExists     <- data.email.map(email => Task.fromFuture(ex => userService.exists(email)) ).getOrElse(Task.succeed(false))
        phoneExists     <- data.phone.map(phone => Task.fromFuture(ex => userService.exists(phone))).getOrElse(Task.succeed(false))
        _               <- if (emailExists || phoneExists) Task.fail(AppException(ErrorCodes.ALREADY_EXISTS, "Email or phone already exists")) else Task.unit
        _               <- Task.fromFuture(ex => userService.save(user))
        _               <- publishEmailConfirmationCode(user)
        _               <- publishPhoneConfirmationCode(user)
      } yield {
        log.info(s"New user $user registered")
        user
      }
    }
  }

  private def publishEmailConfirmationCode(user: User): Task[Unit] = {
    user.email.map { email =>
      for {
        otpCode <- Task(ConfirmationCode.generatePair(user.id, ConfirmationCode.OP_EMAIL_CONFIRM, otpEmailLength, None))
        (otp, code) = otpCode
        _ <- confirmationService.create(code, ttl = Some(otpEmailTTLSeconds))
        _ <- confirmationService.create(code.copy(login = email), ttl = Some(otpEmailTTLSeconds))
        _ <- Task.fromFuture(ec => eventBus.publish(OtpGeneration(Some(user.id), email = Some(email), code = otp))) 
      } yield ()
    } getOrElse Task.unit
  }

  private def publishPhoneConfirmationCode(user: User): Task[Unit] = {
    user.phone.map { phone =>
      for {
        otpCode <- Task(ConfirmationCode.generatePair(user.id, ConfirmationCode.OP_PHONE_CONFIRM, otpLength, None))
        (otp, code) = otpCode
        _ <- confirmationService.create(code, ttl = Some(otpPhoneTTLSeconds))
        _ <- confirmationService.create(code.copy(login = phone), ttl = Some(otpPhoneTTLSeconds))
        _ <- Task.fromFuture(ec => eventBus.publish(OtpGeneration(Some(user.id), phone = Some(phone), code = otp))) 
      } yield ()
    } getOrElse Task.unit
  }
}
