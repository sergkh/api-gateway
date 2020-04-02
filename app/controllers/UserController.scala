package controllers

//scalastyle:off public.methods.have.type

import zio._
import akka.actor.ActorSystem
import akka.http.scaladsl.util.FastFuture
import akka.util.ByteString
import com.mohiva.play.silhouette.api.actions.SecuredRequest
import com.mohiva.play.silhouette.api.util.{PasswordHasherRegistry, PasswordInfo}
import com.mohiva.play.silhouette.api.{LoginInfo, Silhouette}
import com.mohiva.play.silhouette.impl.providers.CredentialsProvider
import com.mohiva.play.silhouette.persistence.daos.DelegableAuthInfoDAO
import events.EventsStream
import forms.{CommonForm, ResetPasswordForm, UserForm}
import javax.inject.{Inject, Singleton}
import models.AppEvent.{UserBlocked, _}
import models.User._
import models._
import play.api.Configuration
import play.api.i18n.Lang
import play.api.libs.json.Json._
import play.api.libs.json._
import play.api.mvc.{RequestHeader, Result}
import reactivemongo.play.json._
import security.{ConfirmationCodeService, WithPermission, WithUser, WithUserAndPerm}
import services.{BranchesService, ConfirmationProvider, UserService}
import utils.FutureUtils._
import utils.Responses._
import utils.RichRequest._
import utils.Settings
import utils.StringHelpers._
import utils.TaskExt._

import scala.concurrent.{ExecutionContext, Future}

/**
  * Created by yaroslav on 29/11/15.
  */
@Singleton
class UserController @Inject()(
                                silh: Silhouette[JwtEnv],
                                config: Configuration,
                                eventBus: EventsStream,
                                userService: UserService,
                                passDao: DelegableAuthInfoDAO[PasswordInfo],
                                passwordHashers: PasswordHasherRegistry,
                                confirmationService: ConfirmationCodeService,
                                confirmationValidator: ConfirmationProvider,
                                branches: BranchesService
                              )(implicit exec: ExecutionContext, system: ActorSystem)
  extends BaseController {

  val otpLength = config.getOptional[Int]("confirmation.otp.length").getOrElse(ConfirmationCodeService.DEFAULT_OTP_LEN)
  val otpEmailLength = config.getOptional[Int]("confirmation.otp.email-length").getOrElse(otpLength)
  val otpPhoneTTLSeconds = 10 * 60
  val optEmailTTLSeconds = 3 * 24 * 60 * 60 // 3 days TODO: make a setting

  val requirePass    = config.get[Boolean]("app.requirePassword")
  val requireFields  = config.get[String]("app.requireFields").split(",").map(_.trim).toList


  implicit def listUserWrites = new Writes[Seq[User]] {
    override def writes(o: Seq[User]): JsValue = JsArray(for (obj <- o) yield Json.toJson(obj))
  }

  val adminReadPerm = WithPermission("users:read")
  val adminEditPerm = WithPermission("users:edit")
  val blockPerm = WithPermission("users:block")

  def add = silh.SecuredAction(adminReadPerm).async { request =>
    val data = request.asForm(UserForm.createUser)

    val user = User(
      email = data.email,
      phone = data.phone,
      firstName = data.firstName,
      lastName = data.lastName,
      password = data.password.map(passwordHashers.current.hash),
      flags = data.flags,
      roles = data.roles
    )

    val errors = User.validateNewUser(user, requireFields, requirePass)

    if (errors.nonEmpty) {
      log.warn(s"Registration fields were required but not set: ${errors.mkString("\n")}")
       throw AppException(ErrorCodes.INVALID_REQUEST, errors.mkString("\n"))
    }

    for {
      _               <- validateBranchAccess(user.branch, request.identity)      
      emailExists     <- data.email.map(userService.exists).getOrElse(FastFuture.successful(false))
      phoneExists     <- data.phone.map(userService.exists).getOrElse(FastFuture.successful(false))
      _               <- conditionalFail(emailExists, ErrorCodes.ALREADY_EXISTS, "Email already exists")
      _               <- conditionalFail(phoneExists, ErrorCodes.ALREADY_EXISTS, "Phone already exists")
      hierarchy       <- data.branch.map(
                          b => branches.get(b).orFail(AppException(ErrorCodes.ENTITY_NOT_FOUND, "Branch is not found")).map(_.hierarchy)
                        ) getOrElse FastFuture.successful(Nil)
      userWithBranch  <- user.copy(hierarchy = hierarchy)
      _               <- userService.save(userWithBranch)
      _               <- eventBus.publish(Signup(userWithBranch, request))
    } yield {
      log.info(s"Created a new user $userWithBranch by ${request.identity.id}")

      Ok(Json.toJson(userWithBranch))
    }
  }

  def get(id: String) = silh.SecuredAction(adminReadPerm || WithUser(id)).async { request =>
    userService.getRequestedUser(id, request.identity).map { user =>
      log.info(s"Obtained user $user")
      Ok(Json.toJson(user))
    }
  }

  def list() = silh.SecuredAction(adminReadPerm).async { implicit request =>
    val page = request.asForm(CommonForm.paginated)
    val branchCritOpt = request.identity.branch map { b => Json.obj("hierarchy" -> b) }

    userService.list(branchCritOpt, page.offset, page.limit).flatMap { users =>

      log.info(s"Obtained users list for ${request.identity}, offset: ${page.offset}")

      Ok(Json.obj(
        "items" -> Json.toJson(users))
      )
    }
  }

  def changePassword = silh.UserAwareAction.async(parse.json) { implicit request =>
    val data = request.asForm(UserForm.updatePass)

    val futureUser = request.identity match {
      case Some(user) => Future.successful(user)
      case None => data.login match {
        case Some(login) => userService.getByAnyId(login)
        case None => throw AppException(ErrorCodes.ENTITY_NOT_FOUND, s"User not found")
      }
    }

    futureUser.flatMap { user =>

      val loginInfo = LoginInfo(CredentialsProvider.ID, user.identifier)

      val updPass = passwordHashers.current.hash(data.newPassword)
      val updUser = user.copy(password = Some(updPass))

      passDao.find(loginInfo).flatMap {
        case Some(passInfo) if data.password.isDefined =>
          if (passwordHashers.all.exists(_.matches(passInfo, data.password.get))) {

            passDao.update(loginInfo, updPass).flatMap { _ =>
              eventBus.publish(PasswordChange(updUser, request)) map { _ =>

                log.info(s"User $user changed password")
                NoContent.discardingCookies()
              }
            }
          } else {
            log.info(s"User $user try to change password but passwords don't match")
            throw AppException(ErrorCodes.ACCESS_DENIED, s"Old password is wrong")
          }
        case None if data.login.isEmpty =>
          passDao.update(loginInfo, updPass).flatMap { _ =>
            eventBus.publish(PasswordChange(updUser, request)) map { _ =>
              log.info(s"User $user set password")
              NoContent.discardingCookies()
            }
          }

        case other =>
          log.info(s"Password info doens't match request: $other for pass change")
          throw AppException(ErrorCodes.ENTITY_NOT_FOUND, s"User ${user.identifier} not found")
      }
    }
  }

  def resetPassword = Action.async(parse.json) { implicit request =>
    val login = request.asForm(ResetPasswordForm.form).login
    val loginInfo = LoginInfo(CredentialsProvider.ID, login)

    for {
      user <- silh.env.identityService.retrieve(loginInfo).orFail(AppException(ErrorCodes.ENTITY_NOT_FOUND, s"User $login not found"))
      (otp, code) = ConfirmationCode.generatePair(login, ConfirmationCode.OP_PASSWORD_RESET, otpLength, None)
      _    <- confirmationService.create(code).toUnsafeFuture
      _    <- eventBus.publish(PasswordReset(user, otp, request))
    } yield {
      log.info(s"Generate reset password code for user: ${user.id} ($login)")
      NoContent
    }
  }

  def resetPasswordConfirm = Action.async(parse.json) { implicit request =>
    val data = request.asForm(ResetPasswordForm.confirm)

    val reqLogin = data.login

    userService.getByAnyId(reqLogin).flatMap { user =>
      val reqUserId = user.identifier

      val confirmCode = data.code
      confirmationService.retrieveByLogin(reqLogin).toUnsafeFuture flatMap {
        case Some(code) =>
          val loginInfo = LoginInfo(CredentialsProvider.ID, user.id)
          for {
            user <- silh.env.identityService.retrieve(loginInfo)
            authenticator <- silh.env.authenticatorService.create(loginInfo)
            value <- silh.env.authenticatorService.init(authenticator)
            result <- silh.env.authenticatorService.embed(value, NoContent)
          } yield {
            user match {
              case Some(u) =>
                if (!u.identifier.equals(reqUserId)) {
                  log.info(s"Code $confirmCode not found for login $reqLogin")
                  throw AppException(ErrorCodes.ENTITY_NOT_FOUND, s"User ${code.login} not found")
                } else {
                  val updPass = passwordHashers.current.hash(data.password)
                  val updUser = u.copy(password = Some(updPass) )

                  passDao.update(loginInfo, updPass).flatMap { _ =>
                    eventBus.publish(PasswordChange(updUser, request)) flatMap { _ =>
                      confirmationService.consumeByLogin(reqLogin).toUnsafeFuture
                    }
                  }

                  log.info(s"User $reqLogin change password with reset")
                  result.discardingCookies()
                }
              case _ =>
                log.info(s"User ${code.login} doesn't found")
                throw AppException(ErrorCodes.ENTITY_NOT_FOUND, s"User ${code.login} not found")
            }
          }
        case _ =>
          log.info(s"Code $confirmCode not found")
          throw AppException(ErrorCodes.CONFIRM_CODE_NOT_FOUND, s"Code $confirmCode not found")
      }
    }
  }

  def put(id: String) = silh.SecuredAction(adminEditPerm || WithUserAndPerm(id, "user:update")).async(parse.json) { implicit request =>
    log.info(s"Updating user: ${request.body}")

    val data = request.asForm(UserForm.updateUser)

    val editor = request.identity

    userService.getRequestedUser(id, editor).flatMap { editedUser =>
      updateUserInternal(request, editedUser, data.update(editedUser), editor)
    }
  }

  // TODO: needs to be fixed first
  def patch(id: String)= TODO
  /* silh.SecuredAction(editPerm || WithUser(id)).async(parse.json) { implicit request =>
    log.info(s"Updating user: ${request.body}")

    val patch = request.body.as[JsonPatch]

    val editor = request.identity

    userService.getRequestedUser(id, editor).flatMap { oldUser =>
      val update = patch(Json.toJson(oldUser)).as[User]
      updateUserInternal(request, oldUser, update, editor)
    }

  } */

  private def updateUserInternal(request: SecuredRequest[JwtEnv, JsValue], oldUser: User, update: User, editor: User): Future[Result] = {
    val updatedUserFuture = for {
      _ <- validateUpdate(update, oldUser, editor)
      _ <- validateBranchAccess(oldUser.branch, editor)
      userWithRoles <- userService.withPermissions(update)
      userWithBranches <- updateBranches(oldUser, userWithRoles, editor)
    } yield userWithBranches

    updatedUserFuture.flatMap { user =>

      if (user.email.isEmpty && user.phone.isEmpty) {
        log.info("Nor phone or email specified for user " + user.id)
        throw AppException(ErrorCodes.IDENTIFIER_REQUIRED, "Nor phone nor email specified")
      }

      for {
        // allow admin to set any email/phone
        _ <- verifyConfirmationRequired(oldUser, user, request, Some(request.body))
        storedUser <- userService.update(user, true)
        _ <- notifyUserUpdate(oldUser, storedUser, request)
      } yield {
        log.info(s"User $user was updated by ${request.identity.id}")
        Ok(Json.toJson(storedUser))
      }
    }
  }

  def delete(id: String, comment: Option[String]) = silh.SecuredAction(adminEditPerm || WithUserAndPerm(id, "user:update")).async { implicit request =>
    for {
      user <- userService.getRequestedUser(id, request.identity)
      _ <- validateBranchAccess(user.branch, request.identity)
      _ <- userService.delete(user)
      _ <- eventBus.publish(UserDelete(user, comment, request))
    } yield {
      log.info(s"User $user was deleted by $id ${comment.map(c => "with comment: " + c ).getOrElse("without comment")}")
      NoContent
    }
  }

  def count = silh.SecuredAction(adminReadPerm).async { req =>
    userService.count(req.identity.branch).map { count =>
      log.info(
        s"Get count of users requested by ${req.identity.identifier}, branch: ${req.identity.branch.getOrElse("none")}, count: $count"
      )
      Ok(Json.obj("count" -> count))
    }
  }

  def search = silh.SecuredAction(adminReadPerm).async { request =>
    val data = request.asForm(UserForm.searchUser)
    val user = request.identity

    val criteria = data.q.replaceAll(" ", "") match {
      case uuid: String if isNumberString(uuid) => Json.obj("_id" -> Json.obj("$regex" -> (uuid + ".*")))
      case phone: String if User.checkPhone(phone) => Json.obj("phone" -> Json.obj("$regex" -> ("\\" + phone + ".*")))
      case email: String => Json.obj("email" -> Json.obj("$regex" -> (".*" + email + ".*")))
    }

    val branchCrit = user.branch map { b => Json.obj("hierarchy" -> b) } getOrElse Json.obj()

    userService.search(criteria ++ branchCrit, offset = 0, limit = data.limit.getOrElse(Settings.DEFAULT_LIMIT)).map { users =>
      log.info(
        s"Find users with query:${data.q}, cnt:${users.length} requested by ${user.identifier} branch: ${user.branch.getOrElse("none")}"
      )
      Ok(Json.toJson(users))
    }
  }

  def checkExistence(key: String) = Action.async { implicit request =>
    userService.getByAnyIdOpt(key) map { _.map(_ => NoContent).getOrElse {
        log.info(s"User with identifier:$key doesn't exist")
        throw AppException(ErrorCodes.ENTITY_NOT_FOUND, s"User with identifier:`$key` doesn't exist")
      }
    }
  }

  def blockUser(id: String) = silh.SecuredAction(blockPerm).async(parse.json) { implicit request =>

    val blockFlag = request.asForm(UserForm.blockUser).block

    def updateUser(blockFlag: Boolean, user: User): User = if (blockFlag) {
      user.withFlags(User.FLAG_BLOCKED)
    } else {
      user.withoutFlags(User.FLAG_BLOCKED)
    }

    for {
      editUser <- userService.getRequestedUser(id, request.identity)
      _ <- validateBranchAccess(editUser.branch, request.identity)
      user <- userService.updateFlags(updateUser(blockFlag, editUser))
      _ <- eventBus.publish(if(blockFlag) UserBlocked(user, request) else UserUnblocked(user, request))
    } yield {
      log.info(s"User $user was ${if(blockFlag) "blocked" else "unblocked" } by ${request.identity.id}")
      NoContent
    }
  }

  private def validateUpdate(update: User, oldUser: User, editor: User): Future[Unit] = {
    for {
      _ <- conditionalFail(oldUser.version != update.version,
                  ErrorCodes.CONCURRENT_MODIFICATION,
                  s"Concurrent modification: current user version is ${oldUser.version} while provided one is ${oldUser.version}"
      )
      _ <- conditional(update.email.isDefined && update.email != oldUser.email,
        userService.getByAnyIdOpt(update.email.get).map { oldOpt =>
          conditionalFail(oldOpt.exists(_.id != oldUser.id), ErrorCodes.ALREADY_EXISTS, "Email already used by another user")
        })

      _ <- conditional(update.phone.isDefined && update.phone != oldUser.phone,
        userService.getByAnyIdOpt(update.phone.get).map { oldOpt =>
          conditionalFail(oldOpt.exists(_.id != oldUser.id), ErrorCodes.ALREADY_EXISTS, "Phone already used by another user")
        })
    } yield {
      val flagsChanged = update.flags.toSet != oldUser.flags.toSet

      if (flagsChanged && !editor.hasPermission("users:edit")) {
        throw AppException(ErrorCodes.ACCESS_DENIED, "User cannot change admin flags")
      }

      if ((update.roles.sorted != oldUser.roles) && !editor.hasPermission("users:edit")) {
        throw AppException(ErrorCodes.ACCESS_DENIED, "User cannot change roles")
      }
    }
  }

  private def notifyUserUpdate(editUser: User, newUser: User, request: RequestHeader): Future[Unit] = {
    eventBus.publish(UserUpdate(newUser, request)) flatMap { _ =>
      (editUser.hasFlag(User.FLAG_BLOCKED), newUser.hasFlag(User.FLAG_BLOCKED)) match {
        case (true, false) => eventBus.publish(UserUnblocked(newUser, request))
        case (false, true) => eventBus.publish(UserBlocked(newUser, request))
        case (_, _) => Future.unit
      }
    }
  }

  private def verifyConfirmationRequired(editUser: User, newUser: User, request: SecuredRequest[JwtEnv, JsValue], body: Option[JsValue] = None) = {
    if (editUser.phone != newUser.phone && newUser.phone.isDefined && !confirmationValidator.verifyConfirmed(request)) {
      val (otp, code) = ConfirmationCode.generatePair(editUser.id, request, otpLength, body.map(json => ByteString(Json.toBytes(json))))

      for {
        _   <- confirmationService.create(code, ttl = Some(otpPhoneTTLSeconds)).toUnsafeFuture
        _   <- confirmationService.create(code.copy(login = editUser.phone.get), ttl = Some(otpPhoneTTLSeconds)).toUnsafeFuture // store code for both UUID and phone
        _   <- eventBus.publish(OtpGeneration(Some(newUser.id), None, newUser.phone, otp))        
      } yield {
        log.info(s"Sending phone confirmation code to ${newUser.id}")
        throw AppException(ErrorCodes.CONFIRMATION_REQUIRED, "Otp confirmation required using POST /users/confirm")
      }

    } else if (editUser.email != newUser.email && newUser.email.isDefined && !confirmationValidator.verifyConfirmed(request)) {
      val (otp, code) = ConfirmationCode.generatePair(
        editUser.id, request, otpEmailLength, body.map(json => ByteString(Json.toBytes(json)))
      )

      for {
        _   <- confirmationService.create(code, ttl = Some(optEmailTTLSeconds)).toUnsafeFuture
        _   <- confirmationService.create(code.copy(login = newUser.email.get), ttl = Some(optEmailTTLSeconds)).toUnsafeFuture // store code for both UUID and phone
        _   <- eventBus.publish(OtpGeneration(Some(newUser.id), newUser.email, None, otp))        
      } yield {
        log.info(s"Sending email confirmation code to ${newUser.id}")
        throw AppException(ErrorCodes.CONFIRMATION_REQUIRED, "Otp confirmation required using POST /users/confirm")
      }      
    } else {
      Future.unit
    }
  }

  private def updateBranches(oldUser: User, newUser: User, editor: User): Future[User] = {
    val branchId = newUser.branch orElse oldUser.branch getOrElse Branch.ROOT

    branches.isAuthorized(branchId, editor).flatMap {
      case true if newUser.branch != oldUser.branch => // branch updated
        branches.get(branchId) map {
          case Some(branch) =>
            newUser.copy(hierarchy = branch.hierarchy)
          case None =>
            throw AppException(ErrorCodes.ENTITY_NOT_FOUND, s"Branch $branchId is not found")
        }
      case true =>
        FastFuture.successful(newUser)
      case false =>
        throw AppException(ErrorCodes.ACCESS_DENIED, s"Denied access to branch: $branchId")
    }
  }

  private def validateBranchAccess(branchId: Option[String], user: User): Future[Unit] = {
    branches.isAuthorized(branchId.getOrElse(Branch.ROOT), user) map {
      case false => throw AppException(ErrorCodes.ACCESS_DENIED, s"Denied access to branch: $branchId")
      case true => ()
    }
  }
}
