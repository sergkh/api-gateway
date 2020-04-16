package models

import events._

abstract class UserModificationEvent(name: String, key: String) extends BaseAppEvent(name, key) {
  def user: User
}

case class RequestInfo(ip: String, userAgent: String, userId: Option[String])

object AppEvent {
  class OAuthAppEvent(name: String, key: String) extends BaseAppEvent(name, key)
  
  case class Signup(user: User, request: RequestInfo) extends BaseAppEvent("signup", user.id)
  case class Login(userId: String, sessionId: String, expirationTime: Long, request: RequestInfo) extends BaseAppEvent("login", userId)
  case class Logout(user: User, sessionId: String, request: RequestInfo) extends BaseAppEvent("logout", user.id)
  case class OtpGenerated(userId: Option[String], email: Option[String] = None, phone: Option[String] = None, code: String, request: RequestInfo)
      extends BaseAppEvent("otp", userId.getOrElse("none"))
  case class LoginConfirmed(user: User, request: RequestInfo) extends UserModificationEvent("login_confirmation", user.id)
  case class PasswordChanged(user: User, request: RequestInfo) extends UserModificationEvent("pass_changed", user.id)
  case class UserBlocked(user: User, request: RequestInfo) extends UserModificationEvent("user_blocked", user.id)
  case class UserUnblocked(user: User, request: RequestInfo) extends UserModificationEvent("user_unblocked", user.id)
  case class UserUpdated(user: User, request: RequestInfo) extends UserModificationEvent("user_update", user.id)
  case class UserRemoved(user: User, comment: Option[String], request: RequestInfo) extends UserModificationEvent("user_delete", user.id)

  case class RoleCreated(role: RolePermissions, request: RequestInfo) extends BaseAppEvent("role_created", role.role)
  case class RoleUpdated(role: RolePermissions, request: RequestInfo) extends BaseAppEvent("role_updated", role.role)
  case class RoleRemoved(role: RolePermissions, request: RequestInfo) extends BaseAppEvent("role_deleted", role.role)

  case class PasswordReset(user: User, code: String, request: RequestInfo) extends UserModificationEvent("pass_reset", user.id)

  case class WithoutPassConfirmation(user: User)
    extends UserModificationEvent("without_pass_confirmation", user.id)

  case class ClientCreated(app: ClientApp, request: RequestInfo)
    extends OAuthAppEvent("application_created", app.id)

  case class ClientUpdated(app: ClientApp, request: RequestInfo)
    extends OAuthAppEvent("application_updated", app.id)

  case class ClientRemoved(appId: String, request: RequestInfo)
    extends OAuthAppEvent("application_removed", appId)

  case class BranchCreated(branch: Branch, request: RequestInfo) extends BaseAppEvent("branch_created", branch.id)
  case class BranchUpdated(oldBranch: Branch, branch: Branch, request: RequestInfo) extends BaseAppEvent("branch_updated", oldBranch.id)
  case class BranchRemoved(branchId: String, request: RequestInfo) extends BaseAppEvent("branch_removed", branchId)

}