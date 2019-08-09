package models.dao

import com.google.inject.Inject
import com.mohiva.play.silhouette.api.LoginInfo
import com.mohiva.play.silhouette.api.util.PasswordInfo
import com.mohiva.play.silhouette.password.BCryptPasswordHasher
import com.mohiva.play.silhouette.persistence.daos.DelegableAuthInfoDAO
import services.UserService

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

/**
  * The DAO to store the password information.
  */
class PasswordInfoDao @Inject()(userService: UserService) extends DelegableAuthInfoDAO[PasswordInfo] {

  override def find(loginInfo: LoginInfo): Future[Option[PasswordInfo]] = {
    userService.retrieve(loginInfo).map(_.map(u => PasswordInfo(BCryptPasswordHasher.ID, u.passHash)))
  }

  override def add(loginInfo: LoginInfo, pass: PasswordInfo): Future[PasswordInfo] = {
    userService.updatePassHash(loginInfo.providerKey, pass).map(_ => pass)
  }

  override def update(loginInfo: LoginInfo, pass: PasswordInfo): Future[PasswordInfo] = {
    userService.updatePassHash(loginInfo.providerKey, pass).map(_ => pass)
  }

  override def save(loginInfo: LoginInfo, pass: PasswordInfo): Future[PasswordInfo] = {
    userService.updatePassHash(loginInfo.providerKey, pass).map(_ => pass)
  }

  override def remove(loginInfo: LoginInfo): Future[Unit] = {
    userService.updatePassHash(loginInfo.providerKey, null)
  }
}
