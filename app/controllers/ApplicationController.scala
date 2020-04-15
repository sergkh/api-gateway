package controllers

//scalastyle:off magic.number
//scalastyle:off public.methods.have.type

import akka.actor.ActorSystem
import com.mohiva.play.silhouette.api.services.AuthenticatorService
import com.mohiva.play.silhouette.api.{LoginInfo, Silhouette}
import com.mohiva.play.silhouette.impl.authenticators.JWTAuthenticator
import com.mohiva.play.silhouette.impl.providers.CredentialsProvider
import events.EventsStream
import forms._
import javax.inject.{Inject, Singleton}
import models.AppEvent._
import models._
import play.api.Configuration
import play.api.i18n.I18nSupport
import play.api.libs.json.Json
import play.api.mvc.{RequestHeader, Result}
import security.{ConfirmationCodeService, CustomJWTAuthenticatorService, WithPermission}
import services._
import utils.RichRequest._
import utils.TaskExt._
import zio._

import scala.concurrent.ExecutionContext.Implicits._
import scala.concurrent.Future
import scala.language.implicitConversions
import utils.RandomStringGenerator

@Singleton
class ApplicationController @Inject()(silh: Silhouette[JwtEnv],
                                      userService: UserService,
                                      confirmationService: ConfirmationCodeService,
                                      config: Configuration,
                                      eventBus: EventsStream)(implicit system: ActorSystem) extends BaseController with I18nSupport {

  val baseUrl = config.get[String]("app.basePath").stripSuffix("/")

  def index = Action { _ => Redirect(baseUrl + "/docs/api.html") }
  def version = Action { _ => Ok(utils.BuildInfo.toJson) }

  def confirm = Action.async { implicit request =>
    val confirmation = request.asForm(ConfirmForm.confirm)

    log.info(s"Confirmation code for login ${confirmation.login}")

    confirmationService.consume(confirmation.login, confirmation.code)
      .flatMap {
        case Some(emailConfirm) if emailConfirm.operation == ConfirmationCode.OP_EMAIL_CONFIRM =>
          userService.updateFlags(emailConfirm.userId, removeFlags = List(User.FLAG_EMAIL_NOT_CONFIRMED)).map { _ =>
            log.info(s"Users ${emailConfirm.userId} email confirmed")
          }
        case Some(phoneConfirm) if phoneConfirm.operation == ConfirmationCode.OP_PHONE_CONFIRM =>
          userService.updateFlags(phoneConfirm.userId, removeFlags = List(User.FLAG_PHONE_NOT_CONFIRMED)).map { _ =>
            log.info(s"Users ${phoneConfirm.userId} phone confirmed")
          }
        case code =>
          log.info(s"Code requested by $confirmation is not found or not supported. Got: $code")
          Task.fail(AppException(ErrorCodes.CONFIRM_CODE_NOT_FOUND, s"Code for login ${confirmation.login} is not found"))
      }.map { _ =>
        NoContent
      }
  }

  def resendOtp = Action.async { implicit request =>
    val login = request.asForm(ConfirmForm.regenerateCode)

    for {
      code <- confirmationService.get(login)
                                 .orFail(AppException(ErrorCodes.CONFIRM_CODE_NOT_FOUND, s"Confirmation code is not found"))
      otp = RandomStringGenerator.generateNumericPassword(code.otpLen, code.otpLen)      
      _ <- confirmationService.create(
        code.userId,
        code.ids,
        code.operation,
        otp,
        code.ttl
      )
      _ <- eventBus.publish(OtpGeneration(Some(code.userId), code.email, code.phone, otp))
    } yield {      
      log.info(s"Regenerated otp code for user: ${code.userId} by login $login")
      NoContent
    }
  }

  /**
    * Handles the Sign Out action.
    *
    * @return The result to display.
    */
  def logout = silh.SecuredAction.async { implicit request =>
    val result = Redirect(routes.ApplicationController.index()).withNewSession

    eventBus.publish(Logout(request.identity, request.authenticator.id, request, request.authenticator.id)) flatMap { _ =>
      Task.fromFuture(ec => silh.env.authenticatorService.discard(request.authenticator, result))
    }
  }
}
