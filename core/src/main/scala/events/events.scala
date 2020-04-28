package events
import models._

import java.util.UUID
import java.time.LocalDateTime

sealed trait Event {
  val id: String = UUID.randomUUID().toString.replace("-", "")
  val stamp: LocalDateTime = LocalDateTime.now()
  val `type`: String
}

case class RequestInfo(ip: String, userAgent: String, userId: Option[String] = None)

sealed abstract class UserEvent(val `type`: String) extends Event { def user: User }
sealed abstract class RoleEvent(val `type`: String) extends Event { def role: RolePermissions }
sealed abstract class ClientEvent(val `type`: String) extends Event { def client: ClientApp }
sealed abstract class BranchEvent(val `type`: String) extends Event { def branch: Branch }
sealed abstract class ServiceEvent(val `type`: String) extends Event

case class Signup(user: User, request: RequestInfo) extends UserEvent("signup")
case class Login(user: User, sessionId: String, expirationTime: Long, request: RequestInfo) extends UserEvent("login")
case class Logout(user: User, sessionId: String, request: RequestInfo) extends UserEvent("logout")
case class OtpGenerated(user: User, email: Option[String] = None, phone: Option[String] = None, code: String, request: RequestInfo) extends UserEvent("otp")
case class PasswordChanged(user: User, request: RequestInfo) extends UserEvent("pass_changed")
case class UserBlocked(user: User, request: RequestInfo) extends UserEvent("user_blocked")
case class UserUnblocked(user: User, request: RequestInfo) extends UserEvent("user_unblocked")
case class UserUpdated(user: User, request: RequestInfo) extends UserEvent("user_updated")
case class UserRemoved(user: User, comment: Option[String], request: RequestInfo) extends UserEvent("user_deleted")
case class PasswordReset(user: User, code: String, request: RequestInfo) extends UserEvent("pass_reset")

case class RoleCreated(role: RolePermissions, request: RequestInfo) extends RoleEvent("role_created")
case class RoleUpdated(role: RolePermissions, request: RequestInfo) extends RoleEvent("role_updated")
case class RoleRemoved(role: RolePermissions, request: RequestInfo) extends RoleEvent("role_deleted")

case class ServiceDiscovered(service: Service) extends ServiceEvent("service_discovered")
case class ServiceLost(service: Service) extends ServiceEvent("service_lost")
case class ServicesListUpdate(services: Seq[Service]) extends ServiceEvent("services_updated")

case class ClientCreated(client: ClientApp, request: RequestInfo) extends ClientEvent("client_created")
case class ClientUpdated(client: ClientApp, request: RequestInfo) extends ClientEvent("client_updated")
case class ClientRemoved(client: ClientApp, request: RequestInfo) extends ClientEvent("client_deleted")

case class BranchCreated(branch: Branch, request: RequestInfo) extends BranchEvent("branch_created")
case class BranchUpdated(oldBranch: Branch, branch: Branch, request: RequestInfo) extends BranchEvent("branch_updated")
case class BranchRemoved(branch: Branch, request: RequestInfo) extends BranchEvent("branch_deleted")
