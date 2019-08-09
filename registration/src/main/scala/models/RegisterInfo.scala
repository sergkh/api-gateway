package models

import com.mohiva.play.silhouette.api.LoginInfo
import com.mohiva.play.silhouette.api.util.PasswordInfo

/**
  * @author Yaroslav Derman <yaroslav.derman@gmail.com>.
  *         created on 07.08.2016.
  */
case class RegisterInfo(loginInfo: LoginInfo, user: User, passInfo: PasswordInfo)