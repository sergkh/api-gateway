package models

/**
  *
  * @author Sergey Khruschak <sergey.khruschak@gmail.com>
  *         Created on 12/10/15.
  */
case class DBLoginInfo(id: Option[Long], providerId: String, providerKey: String)
case class DBUserLoginInfo(id: Option[Long], userId: Long, loginInfoId: Long)
case class DBPasswordInfo(id: Option[Long], hasher: String, password: String, salt: Option[String], loginInfoId: Long)
case class DBOAuth1Info(id: Option[Long], token: String, secret: String, loginInfoId: Long)
case class DBOAuth2Info(id: Option[Long], accessToken: String, tokenType: Option[String], expiresIn: Option[Int],
                        refreshToken: Option[String], loginInfoId: Long)

