package events
import models._

import java.util.UUID
import java.time.LocalDateTime

trait Event {
  val id: String = UUID.randomUUID().toString.replace("-", "")
  val stamp: LocalDateTime = LocalDateTime.now()
  val `type`: String
}

case class RequestInfo(ip: String, userAgent: String, userId: Option[String])

abstract class BaseEvent(val `type`: String, val key: String) extends Event
abstract class UserEvent(`type`: String, key: String) extends BaseEvent(`type`, key) {
  def user: User
}

case class Signup(user: User, request: RequestInfo) extends UserEvent("signup", user.id)
case class Login(user: User, sessionId: String, expirationTime: Long, request: RequestInfo) extends UserEvent("login", user.id)
case class Logout(user: User, sessionId: String, request: RequestInfo) extends UserEvent("logout", user.id)
case class OtpGenerated(user: User, email: Option[String] = None, phone: Option[String] = None, code: String, request: RequestInfo) extends UserEvent("otp", user.id)
case class PasswordChanged(user: User, request: RequestInfo) extends UserEvent("pass_changed", user.id)
case class UserBlocked(user: User, request: RequestInfo) extends UserEvent("user_blocked", user.id)
case class UserUnblocked(user: User, request: RequestInfo) extends UserEvent("user_unblocked", user.id)
case class UserUpdated(user: User, request: RequestInfo) extends UserEvent("user_updated", user.id)
case class UserRemoved(user: User, comment: Option[String], request: RequestInfo) extends UserEvent("user_deletde", user.id)
case class PasswordReset(user: User, code: String, request: RequestInfo) extends UserEvent("pass_reset", user.id)

case class RoleCreated(role: RolePermissions, request: RequestInfo) extends BaseEvent("role_created", role.role)
case class RoleUpdated(role: RolePermissions, request: RequestInfo) extends BaseEvent("role_updated", role.role)
case class RoleRemoved(role: RolePermissions, request: RequestInfo) extends BaseEvent("role_deleted", role.role)

case class ServiceDiscovered(service: Service) extends BaseEvent("service_discovered", service.name)
case class ServiceLost(service: Service) extends BaseEvent("service_lost", service.name)
case class ServicesListUpdate(services: Seq[Service]) extends BaseEvent("services_updated", "all")

case class ClientCreated(app: ClientApp, request: RequestInfo) extends BaseEvent("client_created", app.id)
case class ClientUpdated(app: ClientApp, request: RequestInfo) extends BaseEvent("client_updated", app.id)
case class ClientRemoved(appId: String, request: RequestInfo) extends BaseEvent("client_removed", appId)

case class BranchCreated(branch: Branch, request: RequestInfo) extends BaseEvent("branch_created", branch.id)
case class BranchUpdated(oldBranch: Branch, branch: Branch, request: RequestInfo) extends BaseEvent("branch_updated", oldBranch.id)
case class BranchRemoved(branchId: String, request: RequestInfo) extends BaseEvent("branch_removed", branchId)
