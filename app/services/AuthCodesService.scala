package services

import com.fotolog.redis.{BinaryConverter, RedisClient}
import javax.inject.{Inject, Singleton}
import models.AuthCode
import play.api.Configuration
import zio._
import play.api.libs.json.Json
import play.api.libs.crypto.DefaultCookieSigner
import play.api.http.SecretConfiguration
import utils.RandomStringGenerator
import utils.Logging
import org.mindrot.jbcrypt.BCrypt
import java.time.LocalDateTime
import security.KeysManager

/**
  * Service manages short term Authentication codes in redis.
  */
@Singleton
class AuthCodesService @Inject() (redis: RedisClient, keys: KeysManager) extends Logging {
  private[this] implicit val format = Json.format[AuthCode]

  private[this] implicit object BinaryResultConverter extends BinaryConverter[AuthCode] {
    override def read(data: Array[Byte]): AuthCode = Json.parse(data).as[AuthCode]
    override def write(v: AuthCode): Array[Byte] = Json.toBytes(Json.toJson(v))
  }

  private[this] val prefix = "auth-code:"

  def create(userId: String, scope: Option[String], expTime: LocalDateTime, clientId: String): Task[String] = {
    val secretPart = RandomStringGenerator.generateSecret(10)

    val authCode = AuthCode(userId, scope, expTime, clientId, hashSecretPart(secretPart))
    val code = authCode.id + "_" + secretPart

    Task.fromFuture(ec => 
      redis.setAsync(key(authCode.id), authCode.copy(sign = signCode(authCode)), authCode.expireIn)
    ).map(_ => code)
  }

  def getAndRemove(code: String): Task[Option[AuthCode]] = code.split("_") match {
    case Array(id, secret) =>
      for {
        codeOpt <- Task.fromFuture(ec => redis.getAsync[AuthCode](id))
        _       <-  if (codeOpt.isDefined) Task.fromFuture(ec => redis.delAsync(id)) else Task.unit
      } yield codeOpt.filter(c => c.sign == signCode(c) && !c.expired && validateSecret(secret, c))
    case _ =>
      log.warn("Wrong auth code format")
      Task.none
  }

  private def signCode(c: AuthCode): String = 
    keys.codesSigner(s"${c.userId}:${c.scope}:${c.expirationTime}:${c.clientId}:${c.id}:${c.requestedTime}:${c.secretHash}")

  @inline
  private[this] def key(id: String): String = prefix + id

  private def hashSecretPart(secret: String): String = BCrypt.hashpw(secret, BCrypt.gensalt(10))
  private def validateSecret(secret: String, c: AuthCode): Boolean = BCrypt.checkpw(secret, c.secretHash)
}
