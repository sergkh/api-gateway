package models

import java.time.LocalDateTime
import java.time.ZoneOffset

import org.mongodb.scala.bson.annotations.BsonProperty
import utils.RandomStringGenerator

case class DBLoginInfo(id: Option[Long], providerId: String, providerKey: String)
case class DBUserLoginInfo(id: Option[Long], userId: Long, loginInfoId: Long)
case class DBPasswordInfo(id: Option[Long], hasher: String, password: String, salt: Option[String], loginInfoId: Long)
case class DBOAuth1Info(id: Option[Long], token: String, secret: String, loginInfoId: Long)
case class DBOAuth2Info(id: Option[Long], accessToken: String, tokenType: Option[String], expiresIn: Option[Int],
                        refreshToken: Option[String], loginInfoId: Long)

/**
  * Short term authorization code (10 min MAX), that can be exchanged to access token or
  * refresh token.
  * Auth code consists of 2 parts separated by underscore:
  *  * id part used to lookup code
  *  * secret part that is only returned to user, but only a hash of it stored in the DB
  *    used to validate code.
  */                    
case class AuthCode(userId: String,
                    scope: Option[String],
                    expirationTime: LocalDateTime,
                    clientId: String,
                    secretHash: String,
                    id: String = RandomStringGenerator.generateSecret(64),                    
                    requestedTime: LocalDateTime = LocalDateTime.now(),
                    sign: String = "") {
  def expireIn: Int = ((expirationTime.toInstant(ZoneOffset.UTC).toEpochMilli / 1000) - LocalDateTime.now().toInstant(ZoneOffset.UTC).getEpochSecond).toInt
  def expired: Boolean = expirationTime.isBefore(LocalDateTime.now())
  def scopesList: List[String] = scope.map(_.split(" ").toList).getOrElse(Nil)
}

/**
  * Long term Refresh token, that can be used in TokenController.getAccessToken to 
  * obtain an access token.
  * Refresh token consists of 2 parts separated by underscore:
  *  * id part used to lookup token
  *  * secret part that is only returned to user, but only a hash of it stored in the DB
  *    used to validate token
  */
case class RefreshToken(userId: String,
                        scope: Option[String],
                        expirationTime: LocalDateTime,
                        clientId: String,
                        secretHash: String,
                        sign: String = "",
                        @BsonProperty("_id") id: String = RandomStringGenerator.generateSecret(32),
                        requestedTime: LocalDateTime = LocalDateTime.now()) {
  def expired: Boolean = expirationTime.isBefore(LocalDateTime.now())
}

case class AccessToken(id: String, expire: LocalDateTime, scopes: List[String]) 