package models.dao

import com.mohiva.play.silhouette.api.{AuthInfo, LoginInfo}
import com.mohiva.play.silhouette.persistence.daos.DelegableAuthInfoDAO
import models.{AppException, ErrorCodes}
import services.UserService
import services.auth.SocialAuthService

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.reflect.ClassTag
import scala.reflect.runtime.universe._

class BaseSocialAuthDao[A <: AuthInfo](userService: UserService,
                                       authService: SocialAuthService)(implicit val tt: TypeTag[A], ct: ClassTag[A]) extends DelegableAuthInfoDAO[A] {

  override def find(loginInfo: LoginInfo): Future[Option[A]] = {
    userService.retrieve(loginInfo) flatMap {
      case Some(user) => authService.retrieve[A](user.id, loginInfo) map {
        case Some(auth) => Some(auth.asInstanceOf[A])
        case _ => None
      }
      case None => Future.successful(None)
    }
  }

  override def add(loginInfo: LoginInfo, authInfo: A): Future[A] = {
    userService.retrieve(loginInfo) flatMap {
      case Some(user) => authService.save(user.id, loginInfo, authInfo).map(_ => authInfo.asInstanceOf[A])
      case _ => throw AppException(ErrorCodes.ENTITY_NOT_FOUND, s"User with login info: $loginInfo is not found")
    }
  }

  override def update(loginInfo: LoginInfo, authInfo: A): Future[A] = {
    userService.retrieve(loginInfo) flatMap {
      case Some(user) => authService.update(user.id, loginInfo, authInfo).map(_ => authInfo.asInstanceOf[A])
      case _ => throw AppException(ErrorCodes.ENTITY_NOT_FOUND, s"User with login info: $loginInfo is not found")
    }
  }

  override def save(loginInfo: LoginInfo, authInfo: A): Future[A] = {
    userService.retrieve(loginInfo) flatMap {
      case Some(user) => authService.update(user.id, loginInfo, authInfo).map(_ => authInfo.asInstanceOf[A])
      case _ => throw AppException(ErrorCodes.ENTITY_NOT_FOUND, s"User with login info: $loginInfo is not found")
    }
  }

  override def remove(loginInfo: LoginInfo): Future[Unit] = {
    userService.retrieve(loginInfo) flatMap {
      case Some(user) => authService.removeOne[A](user.id, loginInfo)
      case _ => throw AppException(ErrorCodes.ENTITY_NOT_FOUND, s"User with login info: $loginInfo is not found")
    }
  }

  override val classTag: ClassTag[A] = ct
}
