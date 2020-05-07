package controllers

import akka.actor.ActorSystem
import com.mohiva.play.silhouette.api.Silhouette
import com.mohiva.play.silhouette.api.util.PasswordHasherRegistry
import events._
import forms.UserForm
import javax.inject.{Inject, Singleton}
import models._
import play.api.Configuration
import play.api.data.validation.Valid
import play.api.libs.json.Json
import play.api.mvc.RequestHeader
import services.{ConfirmationCodeService, RegistrationFiltersChain, UserService, ClientAuthenticator}
import utils.RandomStringGenerator
import utils.RichRequest._
import zio._

import scala.language.implicitConversions
import models.conf.{ConfirmationConfig, RegistrationConfig}

@Singleton
class RegistrationController @Inject()(userService: UserService,
                                      clientAuth: ClientAuthenticator,
                                      confirmations: ConfirmationCodeService,
                                      config: RegistrationConfig,
                                      confirmationConf: ConfirmationConfig,
                                      eventBus: EventsStream,
                                      passwordHashers: PasswordHasherRegistry,
                                      registrationFilters: RegistrationFiltersChain) extends BaseController {

  def register = Action.async(parse.json(1024)) { implicit request =>

    val clientCreds = request.basicAuth.map(Task.succeed(_)).getOrElse {
      log.warn(s"No client authentication provided. Request: ${request.reqInfo}")
      Task.fail(AppException(ErrorCodes.AUTHORIZATION_FAILED, "Client authorization required"))
    }

    for {
      creds           <- clientCreds
      _               <- clientAuth.authenticateClientOrFail(creds._1, creds._2)
      transformedReq  <- registrationFilters(request)
      user            <- userRegistrationRequest(transformedReq, creds._1)
      _               <- eventBus.publish(Signup(user, request.reqInfo))
    } yield Ok(Json.toJson(user))
  }

  private def userRegistrationRequest(req: RequestHeader, clientId: String): Task[User] = {
    val data = req.asForm(UserForm.createUser)

    log.info(s"Registration data: ${data.copy(password = data.password.map(_ => "***"))} from $clientId")

    val user = User(
      email = data.email,
      phone = data.phone,
      firstName = data.firstName.filter(_.isEmpty),
      lastName = data.lastName.filter(_.isEmpty),
      password = data.password.map(passwordHashers.current.hash),
      flags = data.email.map(_ => User.FLAG_EMAIL_NOT_CONFIRMED).toList ++ data.phone.map(_ => User.FLAG_PHONE_NOT_CONFIRMED).toList,
      extra = data.extra.getOrElse(Map.empty)
    )

    val errors = User.validateNewUser(user, config.requiredFields, config.requirePassword)

    if (errors.nonEmpty) {
      log.warn(s"Registration fields were required but not set: ${errors.mkString("\n")}")
      Task.fail(AppException(ErrorCodes.INVALID_REQUEST, errors.mkString("\n")))
    } else {

      for {

        emailExists     <- data.email.map(email => userService.exists(email)).getOrElse(Task.succeed(false))
        phoneExists     <- data.phone.map(phone => userService.exists(phone)).getOrElse(Task.succeed(false))
        _               <- if (emailExists || phoneExists) Task.fail(AppException(ErrorCodes.ALREADY_EXISTS, "Email or phone already exists")) else Task.unit
        _               <- userService.save(user)
        _               <- publishEmailConfirmationCode(user, req.reqInfo)
        _               <- publishPhoneConfirmationCode(user, req.reqInfo)
      } yield {
        log.info(s"New user $user registered")
        user
      }
    }
  }

  private def publishEmailConfirmationCode(user: User, requestInfo: RequestInfo): Task[Unit] = {
    user.email.map { email =>

      val otp = RandomStringGenerator.generateNumericPassword(confirmationConf.email.length, confirmationConf.email.length)

      for {
        _ <- confirmations.create(
          user.id, List(user.id, email), ConfirmationCode.OP_EMAIL_CONFIRM, otp, ttl = confirmationConf.email.ttl
        )
        _ <- eventBus.publish(OtpGenerated(user, email = Some(email), code = otp, request = requestInfo))
      } yield ()
    } getOrElse Task.unit
  }

  private def publishPhoneConfirmationCode(user: User, requestInfo: RequestInfo): Task[Unit] = {
    user.phone.map { phone =>
      val otp = RandomStringGenerator.generateNumericPassword(confirmationConf.phone.length, confirmationConf.phone.length)

      for {
        _ <- confirmations.create(
          user.id, List(user.id, phone), ConfirmationCode.OP_PHONE_CONFIRM, otp, ttl = confirmationConf.phone.ttl
        )
        _ <- eventBus.publish(OtpGenerated(user, phone = Some(phone), code = otp, request = requestInfo))
      } yield ()
    } getOrElse Task.unit
  }
}
