package controllers

//scalastyle:off public.methods.have.type

import akka.actor.ActorSystem
import akka.http.scaladsl.util.FastFuture
import akka.util.ByteString
import com.mohiva.play.silhouette.api.actions.SecuredRequest
import com.mohiva.play.silhouette.api.util.{PasswordHasherRegistry, PasswordInfo}
import com.mohiva.play.silhouette.api.{LoginInfo, Silhouette}
import com.mohiva.play.silhouette.impl.providers.CredentialsProvider
import com.mohiva.play.silhouette.persistence.daos.DelegableAuthInfoDAO
import events.EventsStream
import forms.{ResetPasswordForm, UserForm}
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
import security.{ConfirmationCodeService, WithAnyPermission, WithUser}
import services.{BranchesService, ConfirmationProvider, ExtendedUserInfoService, UserService}
import utils.FutureUtils._
import utils.Responses._
import utils.RichJson._
import utils.RichRequest._
import utils.StringHelpers._

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
                                extendedInfoService: ExtendedUserInfoService,
                                branches: BranchesService
                              )(implicit exec: ExecutionContext, system: ActorSystem)
  extends BaseController {

  val otpLength = config.getOptional[Int]("confirmation.otp.length").getOrElse(ConfirmationCodeService.DEFAULT_OTP_LEN)
  val otpEmailLength = config.getOptional[Int]("confirmation.otp.email-length").getOrElse(otpLength)
  val optEmailTTLSeconds = 3 * 24 * 60 * 60 // 3 days

  implicit def listUserWrites = new Writes[Seq[User]] {
    override def writes(o: Seq[User]): JsValue = JsArray(for (obj <- o) yield Json.toJson(obj))
  }

  val readPerm = WithAnyPermission("users:read")
  val editPerm = WithAnyPermission("users:edit")
  val blockPerm = WithAnyPermission("users:block")

  def get(id: String) = silh.SecuredAction(readPerm || WithUser(id)).async { request =>
    userService.getRequestedUser(id, request.identity).map { user =>
      log.info(s"Obtained user $user")
      Ok(Json.toJson(user))
    }
  }

  def list() = silh.SecuredAction(readPerm).async { implicit request =>
    val queryParams = request.asForm(UserForm.queryUser)
    val branchCritOpt = request.identity.branch map { b => Json.obj("hierarchy" -> b) }

    userService.list(branchCritOpt, queryParams).flatMap { users =>

      log.info(s"Obtained users list for ${request.identity}")

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

      val updUser = user.copy(
        passHash = passwordHashers.current.hash(data.newPass).password
      )

      passDao.find(loginInfo).flatMap {
        case Some(passInfo) if data.pass.isDefined =>
          if (passwordHashers.all.exists(_.matches(passInfo, data.pass.get))) {

            passDao.update(loginInfo, PasswordInfo(passwordHashers.current.id, updUser.passHash)).flatMap { _ =>
              eventBus.publish(PasswordChange(updUser, request, request2lang)) map { _ =>

                log.info(s"User $user changed password")
                NoContent.discardingCookies()
              }
            }
          } else {
            log.info(s"User $user try to change password but passwords don't match")
            throw AppException(ErrorCodes.ACCESS_DENIED, s"Old password is wrong")
          }
        case None if data.login.isEmpty =>
          passDao.update(loginInfo, PasswordInfo(passwordHashers.current.id, updUser.passHash)).flatMap { _ =>
            eventBus.publish(PasswordChange(updUser, request, request2lang)) map { _ =>
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

    silh.env.identityService.retrieve(loginInfo).flatMap {
      case Some(user) =>
        val (otp, code) = ConfirmationCode.generatePair(login, ConfirmationCode.OP_PASSWORD_RESET, otpLength, None)
        confirmationService.create(code)

        eventBus.publish(PasswordReset(user, otp, request, request2lang)) map { _ =>
          log.info(s"Generate reset password code for user: ${user.id} ($login)")
          NoContent
        }
      case None =>
        throw AppException(ErrorCodes.ENTITY_NOT_FOUND, s"User $login not found")
    }
  }

  def resetPasswordConfirm = Action.async(parse.json) { implicit request =>
    val data = request.asForm(ResetPasswordForm.confirm)

    val reqLogin = data.login

    userService.getByAnyId(reqLogin).flatMap { user =>
      val reqUserId = user.identifier

      val confirmCode = data.code
      confirmationService.retrieveByLogin(reqLogin) flatMap {
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
                  val updUser = u.copy(passHash = passwordHashers.current.hash(data.password).password)

                  passDao.update(loginInfo, PasswordInfo(passwordHashers.current.id, updUser.passHash)).flatMap { _ =>
                    eventBus.publish(PasswordChange(updUser, request, request2lang)) map { _ =>
                      confirmationService.consumeByLogin(reqLogin)
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

  def put(id: String) = silh.SecuredAction(editPerm || WithUser(id)).async(parse.json) { implicit request =>
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

  def delete(id: String, comment: Option[String]) = silh.SecuredAction(editPerm || WithUser(id)).async { implicit request =>
    for {
      user <- userService.getRequestedUser(id, request.identity)
      _ <- validateBranchAccess(user.branch, request.identity)
      _ <- userService.delete(user)
      - <- extendedInfoService.delete(Json.obj("_id" -> user.id))
      _ <- eventBus.publish(UserDelete(user, comment, request, request2lang))
    } yield {
      log.info(s"User $user was deleted by $id ${comment.map(c => "with comment: " + c ).getOrElse("without comment")}")
      NoContent
    }
  }

  def count = silh.SecuredAction(readPerm).async { req =>
    userService.count(req.identity.branch).map { count =>
      log.info(
        s"Get count of users requested by ${req.identity.identifier}, branch: ${req.identity.branch.getOrElse("none")}, count: $count"
      )
      Ok(Json.obj("count" -> count))
    }
  }

  def search = silh.SecuredAction(readPerm).async { request =>
    val data = request.asForm(UserForm.searchUser)
    val user = request.identity

    val criteria = data.q.replaceAll(" ", "") match {
      case uuid: String if isNumberString(uuid) => Json.obj("_id" -> Json.obj("$regex" -> (uuid + ".*")))
      case phone: String if User.checkPhone(phone) => Json.obj("phone" -> Json.obj("$regex" -> ("\\" + phone + ".*")))
      case email: String => Json.obj("email" -> Json.obj("$regex" -> (".*" + email + ".*")))
    }

    val branchCrit = user.branch map { b => Json.obj("hierarchy" -> b) } getOrElse Json.obj()

    userService.search(criteria ++ branchCrit, QueryParams(_limit = data.limit)).map { users =>
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
      _ <- eventBus.publish(if(blockFlag) UserBlocked(user, request, request2lang) else UserUnblocked(user, request, request2lang))
    } yield {
      log.info(s"User $user was ${if(blockFlag) "blocked" else "unblocked" } by ${request.identity.id}")
      NoContent
    }
  }

  def retrieveExtendedInfo(anyId: String) = silh.SecuredAction(editPerm || WithUser(anyId)).async { implicit request =>
    userService.getRequestedUser(anyId, request.identity).flatMap { user =>
      extendedInfoService.retrieve4user(user.id).map { optInfo =>
        log.info(s"Obtained extended user info for ${user.id} by ${request.identity.id}")
        Ok(optInfo.map(_.rename("_id", "uuid")).getOrElse(JsObject(Nil)))
      }
    }
  }

  def createExtendedInfo(anyId: String) = silh.SecuredAction(WithUser(anyId)).async(parse.json) { implicit request =>
    val extendedInfo = request.body.as[JsObject] ++ Json.obj("_id" -> request.identity.id)

    extendedInfoService.create(extendedInfo).map { info =>
      log.info(s"Save extended user info for ${request.identity.id} with $info")
      Ok(info)
    }
  }

  def updateExtendedInfo(anyId: String) = silh.SecuredAction(editPerm || WithUser(anyId)).async(parse.json) { implicit request =>
    userService.getRequestedUser(anyId, request.identity).flatMap { user =>
      val selector = Json.obj("_id" -> user.id)

      val update = request.body.as[JsObject].without("id")

      if (update.fields.isEmpty) {
        log.info(s"Update object can not be empty")
        throw AppException(ErrorCodes.INVALID_REQUEST, "Nothing to update")
      }

      val updateObj = Json.obj("$set" -> update)

      extendedInfoService.update(selector, updateObj).map {
        case Some(info) =>
          log.info(s"Update extended user info for ${user.id} by ${request.identity.id} with $info")
          Ok(info)
        case None =>
          log.warn(s"Extended info for user ${user.id} not found")
          throw AppException(ErrorCodes.ENTITY_NOT_FOUND, s"Extended info for user ${user.id} not found")
      }
    }
  }

  def retrieveOwnStructure(anyId: String) = silh.SecuredAction(WithUser(anyId)).async { implicit request =>
    val user = request.identity
    val queryParams = request.asForm(UserForm.queryUser)

    extendedInfoService.retrieve4user(user.id).flatMap {
      case Some(info) =>
        val referralCode = (info \ "invitationCode").as[String]
        val selector = Json.obj(
          "inviterCode" -> referralCode,
          "created" -> Json.obj("$gte" -> queryParams.since, "$lte" -> queryParams.until)
        )

        val fCount = extendedInfoService.count(selector)
        val fItems = extendedInfoService.retrieveList(selector, queryParams.limit, queryParams.offset, "_id")

        for {
          count <- fCount
          items <- fItems
          userIds = items.map(o => (o \ "_id").as[Long])
          criteria = Json.obj("_id" -> Json.obj("$in" -> userIds))
          users <- userService.list(Some(criteria), queryParams)
        } yield {
          val usersJson = users.map(u => Json.toJson(u).as[JsObject].without("permissions", "flags"))
          log.info(s"Obtained own user structure for ${user.id}")
          Ok(Json.obj("items" -> usersJson, "count" -> count))
        }

      case None =>
        log.warn(s"Extended info for user ${user.id} not found")
        throw AppException(ErrorCodes.ENTITY_NOT_FOUND, s"Extended info for user ${user.id} not found")
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
    eventBus.publish(UserUpdate(newUser, request, Lang.defaultLang)) flatMap { _ =>
      (editUser.hasFlag(User.FLAG_BLOCKED), newUser.hasFlag(User.FLAG_BLOCKED)) match {
        case (true, false) => eventBus.publish(UserUnblocked(newUser, request, Lang.defaultLang))
        case (false, true) => eventBus.publish(UserBlocked(newUser, request, Lang.defaultLang))
        case (_, _) => Future.unit
      }
    }
  }

  private def verifyConfirmationRequired(editUser: User, newUser: User, request: SecuredRequest[JwtEnv, JsValue], body: Option[JsValue] = None) = {
    if (editUser.phone != newUser.phone && newUser.phone.isDefined && !confirmationValidator.verifyConfirmed(request)) {
      val (otp, code) = ConfirmationCode.generatePair(editUser.id, request, otpLength, body.map(json => ByteString(Json.toBytes(json))))

      // store code for both UUID and phone
      confirmationService.create(code)
      confirmationService.create(code.copy(login = editUser.phone.get))

      eventBus.publish(OtpGeneration(Some(newUser.id), None, newUser.phone, otp, request)) map { _ =>
        log.info(s"Sending phone confirmation code to ${newUser.id}")
        throw AppException(ErrorCodes.CONFIRMATION_REQUIRED, "Otp confirmation required using POST /users/confirm")
      }
    } else if (editUser.email != newUser.email && newUser.email.isDefined && !confirmationValidator.verifyConfirmed(request)) {
      val (otp, code) = ConfirmationCode.generatePair(
        editUser.id, request, otpEmailLength, body.map(json => ByteString(Json.toBytes(json)))
      )

      // store code for both UUID and email with increased TTL
      confirmationService.create(code, ttl = Some(optEmailTTLSeconds))
      confirmationService.create(code.copy(login = newUser.email.get), ttl = Some(optEmailTTLSeconds))

      eventBus.publish(OtpGeneration(Some(newUser.id), newUser.email, None, otp, request)) map { _ =>
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
