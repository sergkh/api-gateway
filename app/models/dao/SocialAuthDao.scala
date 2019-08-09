package models.dao

import com.impactua.bouncer.commons.models.ResponseCode
import com.impactua.bouncer.commons.models.exceptions.AppException
import com.mohiva.play.silhouette.api.{AuthInfo, LoginInfo}
import com.mohiva.play.silhouette.persistence.daos.DelegableAuthInfoDAO
import services.UserService
import services.auth.SocialAuthService

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.reflect.ClassTag
import scala.reflect.runtime.universe._

/**
  * Created by faiaz on 15.05.17.
  */
class SocialAuthDao[A <: AuthInfo](userService: UserService,
                                   authService: SocialAuthService)(implicit val tt: TypeTag[A], ct: ClassTag[A]) extends DelegableAuthInfoDAO[A] {

  override def find(loginInfo: LoginInfo): Future[Option[A]] = {
    userService.retrieve(loginInfo) flatMap {
      case Some(user) => authService.retrieve[A](user.uuid, loginInfo) map {
        case Some(auth) => Some(auth.asInstanceOf[A])
        case _ => None
      }
      case None => Future.successful(None)
    }
  }

  override def add(loginInfo: LoginInfo, authInfo: A): Future[A] = {
    userService.retrieve(loginInfo) flatMap {
      case Some(user) => authService.save(user.uuid, loginInfo, authInfo).map(_ => authInfo.asInstanceOf[A])
      case _ => throw AppException(ResponseCode.USER_NOT_FOUND, s"User with login info: $loginInfo is not found")
    }
  }

  override def update(loginInfo: LoginInfo, authInfo: A): Future[A] = {
    userService.retrieve(loginInfo) flatMap {
      case Some(user) => authService.update(user.uuid, loginInfo, authInfo).map(_ => authInfo.asInstanceOf[A])
      case _ => throw AppException(ResponseCode.USER_NOT_FOUND, s"User with login info: $loginInfo is not found")
    }
  }

  override def save(loginInfo: LoginInfo, authInfo: A): Future[A] = {
    userService.retrieve(loginInfo) flatMap {
      case Some(user) => authService.update(user.uuid, loginInfo, authInfo).map(_ => authInfo.asInstanceOf[A])
      case _ => throw AppException(ResponseCode.USER_NOT_FOUND, s"User with login info: $loginInfo is not found")
    }
  }

  override def remove(loginInfo: LoginInfo): Future[Unit] = {
    userService.retrieve(loginInfo) flatMap {
      case Some(user) => authService.removeOne[A](user.uuid, loginInfo)
      case _ => throw AppException(ResponseCode.USER_NOT_FOUND, s"User with login info: $loginInfo is not found")
    }
  }
}
