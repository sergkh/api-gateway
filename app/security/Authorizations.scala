package security

import com.mohiva.play.silhouette.api.Authorization
import com.mohiva.play.silhouette.impl.authenticators.JWTAuthenticator
import models.User
import play.api.mvc.Request
import services.BranchesService
import utils.Responses._

import scala.concurrent.ExecutionContext.Implicits._
import scala.concurrent.Future

/**
  * Created by yaroslav on 04/01/16.
  */
case class WithPermission(permission: String) extends Authorization[User, JWTAuthenticator] {
  override def isAuthorized[B](identity: User, authenticator: JWTAuthenticator)(implicit request: Request[B]): Future[Boolean] = {
    Future.successful((authenticator.isOauth && authenticator.hasAnyOauthPermission(permission)) || identity.hasPermission(permission))
  }
}

case class WithBranchPermission(permission: String)(implicit branches: BranchesService) {
  def apply(branchProvider: => String): Authorization[User, JWTAuthenticator] = new Authorization[User, JWTAuthenticator] {
    override def isAuthorized[B](identity: User, authenticator: JWTAuthenticator)(implicit request: Request[B]): Future[Boolean] = {
      branches.isAuthorized(branchProvider, identity) map { result =>
        result && (
          (authenticator.isOauth && authenticator.hasAnyOauthPermission(permission)) || identity.hasPermission(permission)
        )
      }
    }
  }
}

case class WithAnyPermission(permissions: String*) extends Authorization[User, JWTAuthenticator] {
  override def isAuthorized[B](identity: User, authenticator: JWTAuthenticator)(implicit request: Request[B]): Future[Boolean] = {
    Future.successful((authenticator.isOauth && authenticator.hasAnyOauthPermission(permissions:_*)) || identity.hasAnyPermission(permissions :_*))
  }
}

case class WithUser(anyId: String) extends Authorization[User, JWTAuthenticator] {
  override def isAuthorized[B](identity: User, authenticator: JWTAuthenticator)(implicit request: Request[B]): Future[Boolean] = {
    Future.successful((authenticator.isOauth && authenticator.checkOauthUser(anyId)) || (identity.checkId(anyId) || "me".equalsIgnoreCase(anyId)))
  }
}

case class NotOauthUser(anyId: String) extends Authorization[User, JWTAuthenticator] {
  override def isAuthorized[B](identity: User, authenticator: JWTAuthenticator)(implicit request: Request[B]): Future[Boolean] = {
    Future.successful(authenticator.notOauth && (identity.checkId(anyId) || "me".equalsIgnoreCase(anyId)))
  }
}

case object NotOauth extends Authorization[User, JWTAuthenticator] {
  override def isAuthorized[B](identity: User, authenticator: JWTAuthenticator)(implicit request: Request[B]): Future[Boolean] = {
    Future.successful(authenticator.notOauth)
  }
}