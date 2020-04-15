package controllers

//scalastyle:off public.methods.have.type

import akka.actor.ActorSystem
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
import play.api.libs.json.Json._
import play.api.libs.json._
import play.api.mvc.{RequestHeader, Result}
import security.{ConfirmationCodeService, WithPermission, WithUser, WithUserAndPerm}
import services.{BranchesService, UserService}
import utils.JwtExtension._
import utils.RichRequest._
import utils.TaskExt._
import zio._

import scala.concurrent.ExecutionContext
import utils.RandomStringGenerator

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
                                branches: BranchesService
                              )(implicit exec: ExecutionContext, system: ActorSystem)
  extends BaseController {

  val otpLength = config.getOptional[Int]("confirmation.otp.length").getOrElse(ConfirmationCodeService.DEFAULT_OTP_LEN)
  val otpEmailLength = config.getOptional[Int]("confirmation.otp.email-length").getOrElse(otpLength)
  val otpPhoneTTLSeconds = 10 * 60
  val optEmailTTLSeconds = 3 * 24 * 60 * 60 // 3 days TODO: make a setting

  val requirePass    = config.get[Boolean]("registration.requirePassword")
  val requireFields  = config.get[String]("registration.requiredFields").split(",").map(_.trim).toList

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
      flags = data.flags.getOrElse(Nil),
      roles = data.roles.getOrElse(Nil)
    )

    val errors = User.validateNewUser(user, requireFields, requirePass)

    if (errors.nonEmpty) {
      log.warn(s"Registration fields were required but not set: ${errors.mkString("\n")}")
       throw AppException(ErrorCodes.INVALID_REQUEST, errors.mkString("\n"))
    }

    for {
      _               <- validateBranchAccess(user.branch, request.identity)      
      emailExists     <- data.email.map(userService.exists).getOrElse(Task.succeed(false))
      phoneExists     <- data.phone.map(userService.exists).getOrElse(Task.succeed(false))
      _               <- failIf(emailExists, ErrorCodes.ALREADY_EXISTS, "Email already exists")
      _               <- failIf(phoneExists, ErrorCodes.ALREADY_EXISTS, "Phone already exists")
      hierarchy       <- data.branch.map(
                          b => branches.get(b).orFail(AppException(ErrorCodes.ENTITY_NOT_FOUND, "Branch is not found")).map(_.hierarchy)
                        ).getOrElse(Task.succeed(Nil))
      userWithBranch  = user.copy(hierarchy = hierarchy)
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

    userService.list(request.identity.branch, page.offset, page.limit).map { users =>

      log.info(s"Obtained users list for ${request.identity}, offset: ${page.offset}")

      Ok(Json.obj(
        "items" -> Json.toJson(users))
      )
    }
  }

  def changePassword = silh.UserAwareAction.async(parse.json) { implicit request =>
    val data = request.asForm(UserForm.updatePass)

    val futureUser = request.identity match {
      case Some(user) => Task.succeed(user)
      case None => data.login match {
        case Some(login) => userService.getByAnyId(login)
        case None        => Task.fail(AppException(ErrorCodes.ENTITY_NOT_FOUND, s"User not found"))
      }
    }

    futureUser.flatMap { user =>

      val loginInfo = LoginInfo(CredentialsProvider.ID, user.identifier)

      val updPass = passwordHashers.current.hash(data.newPassword)
      val updUser = user.copy(password = Some(updPass))

      Task.fromFuture(ec => passDao.find(loginInfo)).flatMap {
        case Some(passInfo) if data.password.isDefined =>
          if (passwordHashers.all.exists(_.matches(passInfo, data.password.get))) {

            Task.fromFuture(ec => passDao.update(loginInfo, updPass)).flatMap { _ =>
              eventBus.publish(PasswordChanged(updUser, request)) map { _ =>

                log.info(s"User $user changed password")
                NoContent.discardingCookies()
              }
            }
          } else {
            log.info(s"User $user try to change password but passwords don't match")
            Task.fail(AppException(ErrorCodes.ACCESS_DENIED, s"Old password is wrong"))
          }
        case None if data.login.isEmpty =>
          Task.fromFuture(ec => passDao.update(loginInfo, updPass)).flatMap { _ =>
            eventBus.publish(PasswordChanged(updUser, request)) map { _ =>
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
    val login = request.asForm(ResetPasswordForm.initReset)

    for {
      user   <- userService.getActiveUser(login).orFail(AppException(ErrorCodes.ENTITY_NOT_FOUND, s"User $login not found"))      
      otp     =  RandomStringGenerator.generateNumericPassword(otpLength, otpLength)
      _      <- confirmationService.create(
                      user.id, 
                      List(user.id) ++ user.email.toList ++ user.phone.toList,
                      ConfirmationCode.OP_PASSWORD_RESET,
                      otp,
                      otpPhoneTTLSeconds
                    )
      _      <- eventBus.publish(PasswordReset(user, otp, request))
    } yield {
      log.info(s"Generate reset password code for user: ${user.id} ($login)")
      NoContent
    }
  }

  def resetPasswordConfirm = Action.async(parse.json) { implicit request =>
    val data = request.asForm(ResetPasswordForm.confirm)

    val login = data.login
    val confirmCode = data.code
    val loginInfo = LoginInfo(CredentialsProvider.ID, data.login)

    for {
      code          <- confirmationService.consume(login, confirmCode).orFail(AppException(ErrorCodes.CONFIRM_CODE_NOT_FOUND, s"Code $confirmCode not found"))
      user          <- userService.getActiveUser(code.userId).orFail(AppException(ErrorCodes.ENTITY_NOT_FOUND, s"User ${data.login} not found"))
      _             <- failIf(!user.checkId(login), ErrorCodes.ENTITY_NOT_FOUND, s"User ${login} is not found")
      updatedPass    = passwordHashers.current.hash(data.password)
      _             <- Task.fromFuture(ec => passDao.update(loginInfo, updatedPass))
      _             <- eventBus.publish(PasswordChanged(user, request))
    } yield {
      log.info(s"User ${user.id} reset password using login $login")
      NoContent
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
  def patch(id: String) = TODO
  /* silh.SecuredAction(editPerm || WithUser(id)).async(parse.json) { implicit request =>
    log.info(s"Updating user: ${request.body}")

    val patch = request.body.as[JsonPatch]

    val editor = request.identity

    userService.getRequestedUser(id, editor).flatMap { oldUser =>
      val update = patch(Json.toJson(oldUser)).as[User]
      updateUserInternal(request, oldUser, update, editor)
    }

  } */

  private def updateUserInternal(request: SecuredRequest[JwtEnv, JsValue], oldUser: User, update: User, editor: User): Task[Result] = {    
    for {
      _                <- validateUpdate(update, oldUser, editor)
      _                <- validateBranchAccess(oldUser.branch, editor)
      userWithRoles    <- userService.withPermissions(update)
      userWithBranches <- updateBranches(oldUser, userWithRoles, editor)
      finalUser        <- addEmailPhoneConfirmation(oldUser, userWithBranches)
      _                <- userService.update(finalUser)
      _                <- notifyUserUpdate(oldUser, finalUser, request)
    } yield {
      log.info(s"User $finalUser was updated by ${request.identity.id}")
      Ok(Json.toJson(finalUser))
    }
  }

  def delete(id: String, comment: Option[String]) = silh.SecuredAction(adminEditPerm || WithUserAndPerm(id, "user:update")).async { implicit request =>
    for {
      user <- userService.getRequestedUser(id, request.identity)
      _    <- validateBranchAccess(user.branch, request.identity)
      _    <- userService.delete(user)
      _    <- eventBus.publish(UserDelete(user, comment, request))
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

  def checkExistence(key: String) = Action.async { implicit request =>
    userService.getByAnyIdOpt(key) map { _.map(_ => NoContent).getOrElse {
        log.info(s"User with identifier:$key doesn't exist")
        throw AppException(ErrorCodes.ENTITY_NOT_FOUND, s"User with identifier:`$key` doesn't exist")
      }
    }
  }

  def blockUser(id: String) = silh.SecuredAction(blockPerm).async(parse.json) { implicit request =>
    val block = request.asForm(UserForm.blockUser)

    for {
      editUser <- userService.getRequestedUser(id, request.identity)
      _        <- validateBranchAccess(editUser.branch, request.identity)
      user     <- userService.updateFlags(editUser.id, 
        addFlags    = if(block) List(User.FLAG_BLOCKED) else Nil,
        removeFlags = if(!block) List(User.FLAG_BLOCKED) else Nil
      ).orFail(AppException(ErrorCodes.INTERNAL_SERVER_ERROR, "Failed to update user"))
      _        <- eventBus.publish(if(block) UserBlocked(user, request) else UserUnblocked(user, request))
    } yield {
      log.info(s"User $user was ${if(block) "blocked" else "unblocked" } by ${request.identity.id}")
      NoContent
    }
  }

  private def validateUpdate(update: User, oldUser: User, editor: User): Task[Unit] = {
    val errors = User.validateNewUser(update, requireFields, requirePass)

    for {
      _ <- if (errors.nonEmpty) {
        log.warn(s"Registration fields were required but not set: ${errors.mkString("\n")}")
        Task.fail(AppException(ErrorCodes.INVALID_REQUEST, errors.mkString("\n")))
      } else Task.unit
      _ <- failIf(oldUser.version != update.version,
                  ErrorCodes.CONCURRENT_MODIFICATION,
                  s"Concurrent modification: current user version is ${oldUser.version} while provided one is ${oldUser.version}"
      )
      _ <- if (update.email.isDefined && update.email != oldUser.email) {
          userService.getByAnyIdOpt(update.email.get).flatMap { oldOpt =>
            failIf(oldOpt.exists(_.id != oldUser.id), ErrorCodes.ALREADY_EXISTS, "Email already used by another user")
          }
        } else Task.unit

      _ <- if(update.phone.isDefined && update.phone != oldUser.phone) {
          userService.getByAnyIdOpt(update.phone.get).flatMap { oldOpt =>
            failIf(oldOpt.exists(_.id != oldUser.id), ErrorCodes.ALREADY_EXISTS, "Phone already used by another user")
          }
        } else Task.unit
      flagsChanged = update.flags.toSet != oldUser.flags.toSet  
      _ <- failIf(flagsChanged && !editor.hasPermission("users:edit"), ErrorCodes.ACCESS_DENIED, "User cannot change admin flags")
      _ <- failIf((update.roles.sorted != oldUser.roles) && !editor.hasPermission("users:edit"), ErrorCodes.ACCESS_DENIED, "User cannot change roles")
    } yield ()
  }

  private def notifyUserUpdate(editUser: User, newUser: User, request: RequestHeader): Task[Unit] = {
    eventBus.publish(UserUpdate(newUser, request)) flatMap { _ =>
      (editUser.hasFlag(User.FLAG_BLOCKED), newUser.hasFlag(User.FLAG_BLOCKED)) match {
        case (true, false) => eventBus.publish(UserUnblocked(newUser, request))
        case (false, true) => eventBus.publish(UserBlocked(newUser, request))
        case (_, _) => Task.unit
      }
    }
  }

  private def addEmailPhoneConfirmation(oldUser: User, newUser: User): Task[User] = {
    val phoneChanged = oldUser.phone != newUser.phone && newUser.phone.isDefined
    val emailChanged = oldUser.email != newUser.email && newUser.email.isDefined
    
    val addFlags = List(User.FLAG_PHONE_NOT_CONFIRMED).filter(_ => phoneChanged) ++ List(User.FLAG_EMAIL_NOT_CONFIRMED).filter(_ => emailChanged)

    val updatedUser = newUser.withFlags(addFlags:_*)

    val phoneUpdate = if (phoneChanged) {
      val otp = RandomStringGenerator.generateNumericPassword(otpLength, otpLength)

            for {
        _ <- confirmationService.create(oldUser.id, List(oldUser.id, newUser.phone.get), ConfirmationCode.OP_PHONE_CONFIRM, otp, otpPhoneTTLSeconds)
        _ <- eventBus.publish(OtpGeneration(Some(newUser.id), phone = newUser.email, code = otp))
        _ <- Task(log.info(s"Sent email confirmation code to ${newUser.id}"))
      } yield ()

      Task.unit
    } else Task.unit

    val emailUpdate = if (emailChanged) {
      val otp = RandomStringGenerator.generateNumericPassword(otpEmailLength, otpEmailLength)

      for {
        _ <- confirmationService.create(oldUser.id, List(oldUser.id, newUser.email.get), ConfirmationCode.OP_EMAIL_CONFIRM, otp, optEmailTTLSeconds)
        _ <- eventBus.publish(OtpGeneration(Some(newUser.id), email = newUser.email, code = otp))
        _ <- Task(log.info(s"Sent email confirmation code to ${newUser.id}"))
      } yield ()
    } else Task.unit

    for {
      _ <- phoneUpdate
      _ <- emailUpdate
    } yield updatedUser
  }

  private def updateBranches(oldUser: User, newUser: User, editor: User): Task[User] = {
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
        Task.succeed(newUser)
      case false =>
        Task.fail(AppException(ErrorCodes.ACCESS_DENIED, s"Denied access to branch: $branchId"))
    }
  }

  private def validateBranchAccess(branchId: Option[String], user: User): Task[Unit] = {
    branches.isAuthorized(branchId.getOrElse(Branch.ROOT), user) map {
      case false => throw AppException(ErrorCodes.ACCESS_DENIED, s"Denied access to branch: $branchId")
      case true => ()
    }
  }
}
