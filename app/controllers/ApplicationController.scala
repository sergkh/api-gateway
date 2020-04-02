package controllers

//scalastyle:off magic.number
//scalastyle:off public.methods.have.type

import zio._
import akka.actor.ActorSystem
import akka.http.scaladsl.util.FastFuture
import akka.stream.scaladsl.Source
import akka.util.ByteString
import com.mohiva.play.silhouette.api.exceptions.ProviderException
import com.mohiva.play.silhouette.api.services.AuthenticatorService
import com.mohiva.play.silhouette.api.util.Credentials
import com.mohiva.play.silhouette.api.{LoginInfo, Silhouette}
import com.mohiva.play.silhouette.impl.authenticators.JWTAuthenticator
import com.mohiva.play.silhouette.impl.exceptions.{IdentityNotFoundException, InvalidPasswordException}
import com.mohiva.play.silhouette.impl.providers.CredentialsProvider
import events.EventsStream
import forms.LoginForm.LoginCredentials
import forms._
import javax.inject.{Inject, Singleton}
import models.AppEvent._
import models._
import play.api.Configuration
import play.api.i18n.I18nSupport
import play.api.libs.json.Json
import play.api.mvc.{Headers, Request, RequestHeader, Result}
import security.{ConfirmationCodeService, CustomJWTAuthenticatorService, WithPermission}
import services._
import services.RegistrationFiltersChain
import utils.JsonHelper
import utils.RichRequest._
import utils.FutureUtils._
import utils.TaskExt._

import scala.concurrent.ExecutionContext.Implicits._
import scala.concurrent.Future
import scala.language.implicitConversions

@Singleton
class ApplicationController @Inject()(silh: Silhouette[JwtEnv],
                                      userService: UserService,
                                      confirmationService: ConfirmationCodeService,
                                      config: Configuration,
                                      eventBus: EventsStream,
                                      proxy: ProxyService,
                                      confirmations: ConfirmationProvider,
                                      sessionsService: SessionsService)(implicit system: ActorSystem) extends BaseController with I18nSupport {
  
  val requirePass = config.getOptional[Boolean]("app.requirePassword").getOrElse(true)
  val appSecret = config.get[String]("play.http.secret.key")
  val otpLength = config.getOptional[Int]("confirmation.otp.length").getOrElse(ConfirmationCodeService.DEFAULT_OTP_LEN)

  val baseUrl = config.get[String]("app.basePath").stripSuffix("/")
  val internalWebPermission = "internal_web"

  implicit def toService(authService: AuthenticatorService[JWTAuthenticator]): CustomJWTAuthenticatorService =
    authService.asInstanceOf[CustomJWTAuthenticatorService]

  def index = Action { _ =>
    Redirect(baseUrl + "/docs/api.html")
  }

  def version = silh.SecuredAction(WithPermission(internalWebPermission)) { _ =>
    Ok(utils.BuildInfo.toJson)
  }

  def confirm = Action.async { implicit request =>
    val confirmation = request.asForm(ConfirmForm.confirm)

    log.info(s"Confirmation of code for login ${confirmation.login}")

    getConfirmCode(confirmation.login)
      .map(_.filter(_.verify(confirmation.code)))
      .flatMap {
        case Some(code) if ConfirmationCode.OP_LOGIN == code.operation => Task.fromFuture(ec => confirmUserAuthorization(code))
        case code =>
          log.info(s"Code $confirmation not found $code")
          Task.fail(AppException(ErrorCodes.CONFIRM_CODE_NOT_FOUND, s"Code for login ${confirmation.login} is not found"))
      }
  }

  /** Gets code by specified login, but if it's not found – retrieves user and tries all his possible logins */
  private def getConfirmCode(login: String): Task[Option[ConfirmationCode]] = {
    confirmationService.retrieveByLogin(login) flatMap {
      case Some(code) => Task.succeed(Some(code))
      case None => // try by other user logins, option filter to avoid calls if code was already found
        for {
          userOpt     <- Task.fromFuture(ec => userService.getByAnyIdOpt(login))
          codeByUuid  <- userOpt.map(u => confirmationService.retrieveByLogin(u.id)).getOrElse(Task.succeed(None))
          codeByEmail <- userOpt.flatMap(_.email)
                                .filter(_ => codeByUuid.isEmpty)
                                .map(email => confirmationService.retrieveByLogin(email))
                                .getOrElse(Task.succeed(None))
          codeByPhone <- userOpt.flatMap(_.phone)
                                .filter(_ => codeByEmail.isEmpty && codeByUuid.isEmpty)
                                .map(phone => confirmationService.retrieveByLogin(phone))
                                .getOrElse(Task.succeed(None))
        } yield codeByUuid orElse codeByEmail orElse codeByPhone
    }
  }

  private def confirmUserAuthorization(confirmationCode: ConfirmationCode)(implicit request: RequestHeader) = {
    confirmAction(confirmationCode, action = loginInfo =>
      userService.retrieve(loginInfo).map(_.getOrElse {
          log.info(s"User ${loginInfo.providerKey} not found")
          throw AppException(ErrorCodes.ENTITY_NOT_FOUND, s"User ${loginInfo.providerKey} not found")
        }
      )
    )
  }

  private def confirmAction(confirmationCode: ConfirmationCode, action: LoginInfo => Future[User])(implicit request: RequestHeader): Future[Result] = {
    val loginInfo = LoginInfo(CredentialsProvider.ID, confirmationCode.login)

    action(loginInfo).flatMap { user =>

      val loginConfirmation = confirmationCode.operation == ConfirmationCode.OP_LOGIN
      
      for {
        _             <- confirmationService.consumeByLogin(confirmationCode.login).toUnsafeFuture
        authenticator <- silh.env.authenticatorService.create(LoginInfo(CredentialsProvider.ID, user.id))
        value         <- silh.env.authenticatorService.init(authenticator)
        result        <- silh.env.authenticatorService.embed(value, Ok(Json.obj("token" -> value)))
        _ <- if (loginConfirmation) {
          eventBus.publish(WithoutPassConfirmation(user, request, request2lang))
        } else {
          eventBus.publish(LoginConfirmation(user, request, request2lang))
        }
      } yield {
        if (loginConfirmation) {
          log.info(s"User ${confirmationCode.login} confirm auth/registration without password")
        } else {
          log.info(s"User ${confirmationCode.login} confirm registration")
        }

        result
      }
    }
  }

  def resendOtp = Action.async(parse.json(512)) { implicit request =>
    val login = request.asForm(ConfirmForm.reConfirm).login

    def codeToUser(c: Option[ConfirmationCode]): Task[Option[User]] = 
      c.map(_ => Task.fromFuture(ec => userService.getByAnyIdOpt(login)))
       .getOrElse(Task.succeed(None))

    for {
      codeOpt <- getConfirmCode(login)
      userOpt <- codeToUser(codeOpt)
      _       <- if(codeOpt.isEmpty || userOpt.isEmpty) {
                    log.info(s"OTP for user: $login doesn't exist")
                    Task.fail(AppException(ErrorCodes.CONFIRM_CODE_NOT_FOUND, s"OTP for user: $login doesn't exist"))
                 } else { Task.unit }
      (otp, newCode) = codeOpt.get.regenerate()
      _ <- Task.fromFuture(ec => eventBus.publish(OtpGeneration(Some(userOpt.get.id), userOpt.get.email, userOpt.get.phone, otp)))
      _ <- confirmationService.create(newCode)
    } yield {      
      log.info(s"Regenerated otp code for user: ${userOpt.get.id} by login $login")
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
      silh.env.authenticatorService.discard(request.authenticator, result)
    }
  }

}
