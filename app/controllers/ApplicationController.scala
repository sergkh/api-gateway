package controllers

//scalastyle:off magic.number
//scalastyle:off public.methods.have.type

import akka.actor.ActorSystem
import akka.http.scaladsl.util.FastFuture
import akka.stream.scaladsl.Source
import akka.util.ByteString
import com.mohiva.play.silhouette.api.exceptions.ProviderException
import com.mohiva.play.silhouette.api.repositories.AuthInfoRepository
import com.mohiva.play.silhouette.api.services.AuthenticatorService
import com.mohiva.play.silhouette.api.util.Credentials
import com.mohiva.play.silhouette.api.util.PasswordHasherRegistry
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

import scala.concurrent.ExecutionContext.Implicits._
import scala.concurrent.Future
import scala.language.implicitConversions


/**
  * The main application controller that handles login/logout, registration etc. requests.
  */
@Singleton
class ApplicationController @Inject()(silh: Silhouette[JwtEnv],
                                      userService: UserService,
                                      authInfoRepository: AuthInfoRepository,
                                      credentialsProvider: CredentialsProvider,
                                      confirmationService: ConfirmationCodeService,
                                      passwordHashers: PasswordHasherRegistry,
                                      config: Configuration,
                                      eventBus: EventsStream,
                                      proxy: ProxyService,
                                      confirmations: ConfirmationProvider,
                                      registration: RegistrationService,
                                      sessionsService: SessionsService,
                                      registrationFilters: RegistrationFiltersChain)(implicit system: ActorSystem) extends BaseController with I18nSupport {
  val requirePass = config.getOptional[Boolean]("app.requirePassword").getOrElse(true)
  val appSecret = config.get[String]("play.http.secret.key")
  val otpLength = config.getOptional[Int]("confirmation.otp.length").getOrElse(ConfirmationCodeService.DEFAULT_OTP_LEN)

  val baseUrl = config.get[String]("app.basePath").stripSuffix("/")

  val createUserPermission = "users:create"
  val internalWebPermission = "internal_web"

  implicit def toService(authService: AuthenticatorService[JWTAuthenticator]): CustomJWTAuthenticatorService =
    authService.asInstanceOf[CustomJWTAuthenticatorService]

  def index = Action { _ =>
    Redirect(baseUrl + "/docs/api.html")
  }

  def version = silh.SecuredAction(WithPermission(internalWebPermission)) { _ =>
    Ok(utils.BuildInfo.toJson)
  }

  /**
    * Authenticates a user against the credentials provider.
    *
    * @return The result to display.
    */
  def authenticate = Action.async(parse.json) { implicit request =>
    val credentials = request.asForm(LoginForm.form)

    val futureResult = for {
      loginInfo <- credsTologinInfo(credentials)
      optUser   <- userService.retrieve(loginInfo)
      result    <- processUserLogin(optUser, loginInfo, credentials)
    } yield result

    futureResult.recover {
      case e: IdentityNotFoundException =>
        log.info(s"User: ${credentials.login} not found " + e.getMessage)
        throw AppException(ErrorCodes.AUTHORIZATION_FAILED, "Invalid credentials")
      case e: InvalidPasswordException =>
        log.info(s"Password for user: ${credentials.login} not match " + e.getMessage)
        throw AppException(ErrorCodes.AUTHORIZATION_FAILED, "Invalid credentials")
      case e: ProviderException =>
        log.info(s"Invalid credentials for user: ${credentials.login} " + e.getMessage)
        throw AppException(ErrorCodes.AUTHORIZATION_FAILED, "Invalid credentials")
      case e: AppException =>
        log.info(s"App exception occurred for user: ${credentials.login} " + e.message)
        throw e
    }
  }

  private def credsTologinInfo(creds: LoginCredentials) = creds.password match {
    case Some(pass) =>
      log.trace(s"Login of ${creds.loginFormatted} with password")
      credentialsProvider.authenticate(Credentials(creds.loginFormatted, pass))
    case None if requirePass =>
      log.warn(s"Password for user ${creds.login} required but not set")
      throw AppException(ErrorCodes.AUTHORIZATION_FAILED, "Invalid password")

    case None if !requirePass =>
      val credentials = Credentials(creds.loginFormatted, "eternal-pass")
      log.trace(s"Login of ${creds.loginFormatted} without password")
      FastFuture.successful(LoginInfo(CredentialsProvider.ID, credentials.identifier))
  }

  private def processUserLogin(optUser: Option[User], loginInfo: LoginInfo,
                               credentials: LoginCredentials)(implicit request: RequestHeader): Future[Result] = optUser match {
    case Some(user) if user.hasFlag(User.FLAG_BLOCKED) =>
      log.warn(s"User ${credentials.login} is blocked")
      throw AppException(ErrorCodes.BLOCKED_USER, s"User ${credentials.login} is blocked")

    case Some(user) if credentials.password.nonEmpty && user.flags.contains(User.FLAG_2FACTOR) => // 2-factor authentication, password always required
      val (secret, code) = ConfirmationCode.generatePair(credentials.login, ConfirmationCode.OP_LOGIN, otpLength, None)

      confirmationService.create(code)

      eventBus.publish(OtpGeneration(Some(user.id), user.email, user.phone, secret, request)) map { _ =>
        log.info("Generated login code for " + user.id)
        throw AppException(ErrorCodes.CONFIRMATION_REQUIRED, "Otp confirmation required using POST /users/confirm")
      }

    case Some(user) if credentials.password.isEmpty && !user.flags.contains(User.FLAG_2FACTOR) => // passwordless authentication
      val (secret, code) = ConfirmationCode.generatePair(credentials.login, ConfirmationCode.OP_LOGIN, otpLength, None)
      confirmationService.create(code)

      eventBus.publish(OtpGeneration(Some(user.id), user.email, user.phone, secret, request)) map { _ =>
        log.info("Generated passwordless login code for " + user.id)
        throw AppException(ErrorCodes.CONFIRMATION_REQUIRED, "Otp confirmation required using POST /users/confirm")
      }

    case Some(user) if credentials.password.isDefined && 
                       user.password.exists(p => passwordHashers.find(p).exists(_.matches(p, credentials.password.get))) =>

      val userIdLoginInfo = LoginInfo(CredentialsProvider.ID, user.id)
      silh.env.authenticatorService.create(userIdLoginInfo) flatMap { authenticator =>
        silh.env.authenticatorService.init(authenticator).flatMap { token =>
          log.info(s"Succeed user authentication ${userIdLoginInfo.providerKey}")

          eventBus.publish(Login(user.id, token, request, authenticator.id, authenticator.expirationDateTime.getMillis)).flatMap { _ =>
            silh.env.authenticatorService.embed(token, Ok(JsonHelper.toNonemptyJson("token" -> token)))
          }
        }
      }

    case _ =>
      log.warn(s"User with login: ${credentials.login} is not found")
      throw AppException(ErrorCodes.ENTITY_NOT_FOUND, s"User with login: ${credentials.login} is not found")
  }

  def register = silh.UserAwareAction.async(parse.json) { implicit request =>
    request.identity match {
      case Some(user) if user.hasPermission(createUserPermission) =>
        registration.userRegistrationRequest(request).flatMap { regData =>
          finishUserRegistration(regData.login).map { u =>
            Ok(Json.toJson(u))
          }
        }
      case _ =>
        registrationFilters(request.body).flatMap { _ =>
          registration.userRegistrationRequest(request).flatMap { data =>

            val (otp, code) = ConfirmationCode.generatePair(
              data.loginFormatted, ConfirmationCode.OP_REGISTER, otpLength, Some((request.headers.headers, Some(ByteString(Json.toJson(data).toString()))))
            )

            confirmationService.create(code, ttl = data.ttl)

            eventBus.publish(OtpGeneration(None, data.optEmail, data.optPhone, otp, request)).map {_ =>
              log.info(s"Generated registration code for user login ${data.loginFormatted}")
              throw AppException(ErrorCodes.CONFIRMATION_REQUIRED, "Otp confirmation required using POST /users/confirm")
            }
          }
        }
    }
  }

  def confirm = Action.async { implicit request =>
    val confirmation = request.asForm(ConfirmForm.confirm)

    log.info(s"Confirmation of code for login ${confirmation.login}")

    getConfirmCode(confirmation.login)
      .map(_.filter(_.verify(confirmation.code)))
      .flatMap {
        case Some(code) if ConfirmationCode.OP_REGISTER == code.operation => confirmUserRegistration(code)
        case Some(code) if ConfirmationCode.OP_LOGIN == code.operation => confirmUserAuthorization(code)
        case code =>
          log.info(s"Code $confirmation not found $code")
          throw AppException(ErrorCodes.CONFIRM_CODE_NOT_FOUND, s"Code for login ${confirmation.login} is not found")
      }
  }

  /** Gets code by specified login, but if it's not found – retrieves user and tries all his possible logins */
  private def getConfirmCode(login: String): Future[Option[ConfirmationCode]] = {
    confirmationService.retrieveByLogin(login) flatMap {
      case Some(code) => FastFuture.successful(Some(code))
      case None => // try by other user logins, option filter to avoid calls if code was already found
        for {
          userOpt     <- userService.getByAnyIdOpt(login)
          codeByUuid  <- userOpt.map(u => confirmationService.retrieveByLogin(u.id))
                                .getOrElse(FastFuture.successful(None))
          codeByEmail <- userOpt.flatMap(_.email)
                                .filter(_ => codeByUuid.isEmpty)
                                .map(email => confirmationService.retrieveByLogin(email))
                                .getOrElse(FastFuture.successful(None))
          codeByPhone <- userOpt.flatMap(_.phone)
                                .filter(_ => codeByEmail.isEmpty && codeByUuid.isEmpty)
                                .map(phone => confirmationService.retrieveByLogin(phone))
                                .getOrElse(FastFuture.successful(None))
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

  private def confirmUserRegistration(confirmationCode: ConfirmationCode)(implicit request: RequestHeader) = {
    confirmAction(confirmationCode, action = _ => finishUserRegistration(confirmationCode.login))
  }

  private def finishUserRegistration(login: String)(implicit request: RequestHeader): Future[User] = {
    for {
        regData <- registration.getUnconfirmedRegistrationData(login).orFail(AppException(ErrorCodes.ENTITY_NOT_FOUND, "User not found"))
        user     = User.fromRegistration(regData)
        _       <- userService.save(user)
        _       <- eventBus.publish(Signup(user, request))
      } yield {
        log.info(s"User $user successfully created")
        user
      }
  }

  private def confirmAction(confirmationCode: ConfirmationCode, action: LoginInfo => Future[User])(implicit request: RequestHeader): Future[Result] = {
    val loginInfo = LoginInfo(CredentialsProvider.ID, confirmationCode.login)

    action(loginInfo).flatMap { user =>

      val loginConfirmation = confirmationCode.operation == ConfirmationCode.OP_LOGIN
      confirmationService.consumeByLogin(confirmationCode.login)

      for {
        authenticator <- silh.env.authenticatorService.create(LoginInfo(CredentialsProvider.ID, user.id))
        value <- silh.env.authenticatorService.init(authenticator)
        result <- silh.env.authenticatorService.embed(value, Ok(Json.obj("token" -> value)))
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

    def codeToUser(c: Option[ConfirmationCode]): Future[Option[User]] = c.map(_.operation match {
      case ConfirmationCode.OP_REGISTER => registration.getUnconfirmedRegistrationData(login).map {
        _.map(data => User(email = data.optEmail.map(_.toLowerCase), phone = data.optPhone, password = data.password))
      }
      case _ => userService.getByAnyIdOpt(login)
    }).getOrElse(Future.successful(None))

    for {
      codeOpt <- getConfirmCode(login)
      userOpt <- codeToUser(codeOpt)
      _       <- if(codeOpt.isEmpty || userOpt.isEmpty) {
                   log.info(s"OTP for user: $login doesn't exist")
                   throw AppException(ErrorCodes.CONFIRM_CODE_NOT_FOUND, s"OTP for user: $login doesn't exist")
                 } else { Future.unit }
      (otp, newCode) = codeOpt.get.regenerate()
      _ <- eventBus.publish(OtpGeneration(Some(userOpt.get.id), userOpt.get.email, userOpt.get.phone, otp, request))
    } yield {
      confirmationService.create(newCode)
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

  def session = Action.async { req =>
    silh.env.authenticatorService.retrieve(req) flatMap {
      case Some(auth) =>
        sessionsService.retrieve(auth.id) map {
          case Some(session) =>

            log.info(s"Getting session info, session ${auth.id} for ${auth.loginInfo}")

            Ok(Json.obj(
              "createdAt" -> session.createdAt,
              "expiredAt" -> session.expiredAt,
              "onlineTime" -> session.onlineTime
            ))
          case None =>
            log.warn(s"Session info ${auth.id} for ${auth.loginInfo} is not found")
            throw AppException(ErrorCodes.ENTITY_NOT_FOUND, "Session not found")
        }
      case None =>
        throw AppException(ErrorCodes.ACCESS_DENIED, "Authorization header not specified")
    }
  }
}
