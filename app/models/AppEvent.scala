package models

import events._
import play.api.i18n.Lang
import play.api.mvc.RequestHeader

abstract class UserModificationEvent(name: String, key: String) extends BaseAppEvent(name, key) {
  def user: User
}

object AppEvent {
  class OAuthAppEvent(name: String, key: String) extends BaseAppEvent(name, key)

  case class Login(userId: String, token: String, request: RequestHeader, sessionId: String, expirationTime: Long) extends BaseAppEvent("login", userId)
  case class Logout(user: User, token: String, request: RequestHeader, sessionId: String) extends BaseAppEvent("logout", user.uuidStr)
  case class OtpGeneration(userId: Option[String], email: Option[String], phone: Option[String], code: String, request: RequestHeader) extends BaseAppEvent("otp", userId.getOrElse("none"))
  case class LoginConfirmation(user: User, request: RequestHeader, lang: Lang) extends UserModificationEvent("login_confirmation", user.uuidStr)
  case class PasswordChange(user: User, request: RequestHeader, lang: Lang) extends UserModificationEvent("pass_changing", user.uuidStr)
  case class PasswordTTLChange(user: User, request: RequestHeader, lang: Lang) extends UserModificationEvent("pass_ttl_changing", user.uuidStr)
  case class UserBlocked(user: User, request: RequestHeader, lang: Lang) extends UserModificationEvent("user_blocked", user.uuidStr)
  case class UserUnblocked(user: User, request: RequestHeader, lang: Lang) extends UserModificationEvent("user_unblocked", user.uuidStr)
  case class UserUpdate(user: User, request: RequestHeader, lang: Lang) extends UserModificationEvent("user_update", user.uuidStr)
  case class UserDelete(user: User, comment: Option[String], request: RequestHeader, lang: Lang) extends UserModificationEvent("user_delete", user.uuidStr)
  case class UserInvitation(email: String, url: String, user: User) extends BaseAppEvent("user_invitation", user.uuidStr)

  case class RoleCreated(role: RolePermissions) extends BaseAppEvent("role_created", role.role)
  case class RoleUpdated(role: RolePermissions) extends BaseAppEvent("role_updated", role.role)
  case class RoleDeleted(role: RolePermissions) extends BaseAppEvent("role_deleted", role.role)

  case class PasswordReset(user: User, code: String, request: RequestHeader, lang: Lang) extends UserModificationEvent("pass_reset", user.uuidStr)

  case class WithoutPassConfirmation(user: User, request: RequestHeader, lang: Lang)
    extends UserModificationEvent("without_pass_confirmation", user.uuidStr)

  case class ApplicationCreated(userId: String, app: ThirdpartyApplication, request: RequestHeader)
    extends OAuthAppEvent("application_created", app.id.toString)

  case class ApplicationUpdated(userId: String, app: ThirdpartyApplication, request: RequestHeader)
    extends OAuthAppEvent("application_updated", app.id.toString)

  case class ApplicationRemoved(userId: String, appId: Long, request: RequestHeader)
    extends OAuthAppEvent("application_removed", appId.toString)

  case class OauthTokenCreated(userId: String, oauthId: String, token: String, request: RequestHeader)
    extends BaseAppEvent("oauth_token_created", userId)

  case class OauthTokenUpdated(userId: String, oauthId: String, token: String, request: RequestHeader)
    extends BaseAppEvent("oauth_token_updated", userId)

  case class OauthTokenRemoved(userId: String, oauthId: String, token: String, request: RequestHeader)
    extends BaseAppEvent("oauth_token_removed", userId)

  case class BranchCreated(userId: String, branch: Branch, request: RequestHeader) extends BaseAppEvent("branch_created", userId)
  case class BranchUpdated(userId: String, oldBranch: Branch, branch: Branch, request: RequestHeader) extends BaseAppEvent("branch_updated", userId)
  case class BranchRemoved(userId: String, branchId: String, request: RequestHeader) extends BaseAppEvent("branch_removed", userId)

}