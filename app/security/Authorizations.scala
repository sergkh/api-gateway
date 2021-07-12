package security

import com.mohiva.play.silhouette.api.Authorization
import com.mohiva.play.silhouette.impl.authenticators.JWTAuthenticator
import models.User
import play.api.mvc.Request
import services.BranchesService
import utils.JwtExtension._
import utils.TaskExt._

import scala.concurrent.ExecutionContext.Implicits._
import scala.concurrent.Future
import play.api.Logger

case class WithPermission(permission: String) extends Authorization[User, JWTAuthenticator] {
  override def isAuthorized[B](identity: User, authenticator: JWTAuthenticator)(implicit request: Request[B]): Future[Boolean] = {
    Logger("test").info(s"Identity: $identity, auth: $authenticator, perm: ${authenticator.asPartialUser.exists(_.hasPermission(permission))}")
    Future.successful(authenticator.asPartialUser.exists(_.hasPermission(permission)) && identity.hasPermission(permission))
  }
}

case class WithBranchPermission(permission: String)(implicit branches: BranchesService) {
  def apply(branchProvider: => String): Authorization[User, JWTAuthenticator] = new Authorization[User, JWTAuthenticator] {
    override def isAuthorized[B](identity: User, authenticator: JWTAuthenticator)(implicit request: Request[B]): Future[Boolean] = {
      branches.isAuthorized(branchProvider, identity).toUnsafeFuture map { result =>
        result && authenticator.asPartialUser.exists(_.hasPermission(permission)) && identity.hasPermission(permission)
      }
    }
  }
}

case class WithUser(anyId: String) extends Authorization[User, JWTAuthenticator] {
  override def isAuthorized[B](identity: User, authenticator: JWTAuthenticator)(implicit request: Request[B]): Future[Boolean] = {
    Future.successful(identity.checkId(anyId) || "me".equalsIgnoreCase(anyId))
  }
}

case class WithUserAndPerm(anyId: String, permission: String) extends Authorization[User, JWTAuthenticator] {
  override def isAuthorized[B](identity: User, authenticator: JWTAuthenticator)(implicit request: Request[B]): Future[Boolean] = {
    Future.successful((identity.checkId(anyId) || "me".equalsIgnoreCase(anyId)) && authenticator.asPartialUser.exists(_.hasPermission(permission)))
  }
}