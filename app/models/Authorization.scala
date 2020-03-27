package models

import java.time.LocalDateTime
import reactivemongo.bson.Macros.Annotations.Key

case class DBLoginInfo(id: Option[Long], providerId: String, providerKey: String)
case class DBUserLoginInfo(id: Option[Long], userId: Long, loginInfoId: Long)
case class DBPasswordInfo(id: Option[Long], hasher: String, password: String, salt: Option[String], loginInfoId: Long)
case class DBOAuth1Info(id: Option[Long], token: String, secret: String, loginInfoId: Long)
case class DBOAuth2Info(id: Option[Long], accessToken: String, tokenType: Option[String], expiresIn: Option[Int],
                        refreshToken: Option[String], loginInfoId: Long)

case class RefreshToken(userId: String,
                        scope: Option[String],
                        expirationTime: LocalDateTime,
                        clientId: String,
                        @Key("_id") id: String,
                        requestedTime: LocalDateTime = LocalDateTime.now()) {
  def expired: Boolean = expirationTime.isBefore(LocalDateTime.now())
}

case class AccessToken(id: String, expire: LocalDateTime, scopes: List[String]) 