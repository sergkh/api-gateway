package services.dao

import com.google.inject.Inject
import com.mohiva.play.silhouette.api.LoginInfo
import com.mohiva.play.silhouette.api.util.PasswordInfo
import utils.TaskExt._
import com.mohiva.play.silhouette.persistence.daos.DelegableAuthInfoDAO
import services.UserService

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.reflect.ClassTag

/**
  * The DAO to store the password information.
  */
class PasswordInfoDao @Inject()(userService: UserService) extends DelegableAuthInfoDAO[PasswordInfo] {

  override val classTag: ClassTag[PasswordInfo] = scala.reflect.classTag[PasswordInfo]

  override def find(loginInfo: LoginInfo): Future[Option[PasswordInfo]] = {
    userService.retrieve(loginInfo).map(_.flatMap(_.password))
  }

  override def add(loginInfo: LoginInfo, pass: PasswordInfo): Future[PasswordInfo] = {
    userService.updatePassHash(loginInfo.providerKey, pass).map(_ => pass).toUnsafeFuture
  }

  override def update(loginInfo: LoginInfo, pass: PasswordInfo): Future[PasswordInfo] = {
    userService.updatePassHash(loginInfo.providerKey, pass).map(_ => pass).toUnsafeFuture
  }

  override def save(loginInfo: LoginInfo, pass: PasswordInfo): Future[PasswordInfo] = {
    userService.updatePassHash(loginInfo.providerKey, pass).map(_ => pass).toUnsafeFuture
  }

  override def remove(loginInfo: LoginInfo): Future[Unit] = {
    userService.updatePassHash(loginInfo.providerKey, null).toUnsafeFuture
  }
}
