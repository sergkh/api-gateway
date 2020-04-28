package services

import javax.inject.{Inject, Singleton}
import models.RefreshToken
import org.mongodb.scala.model.Filters._
import services.MongoApi._
import utils.Logging
import zio._

import scala.concurrent.ExecutionContext
import java.time.LocalDateTime
import utils.RandomStringGenerator
import play.api.libs.crypto.DefaultCookieSigner
import play.api.http.SecretConfiguration
import org.mindrot.jbcrypt.BCrypt
import play.api.Configuration
import security.KeysManager

@Singleton
class TokensService @Inject() (mongoApi: MongoApi, keys: KeysManager) extends Logging {
  
  val col = mongoApi.collection[RefreshToken]("refresh_tokens")

  def create(userId: String,
            scope: Option[String],
            expirationTime: LocalDateTime,
            clientId: String): Task[String] = {
    
    val secretPart = RandomStringGenerator.generateSecret(10)
    val token = RefreshToken(
      userId, scope, expirationTime, clientId, hashSecretPart(secretPart)
    )

    val refreshToken = token.id + "_" + secretPart
    col.insertOne(token.copy(sign = signToken(token))).toUnitTask.map(_ => refreshToken)
  }

  def get(refreshToken: String): Task[Option[RefreshToken]] = refreshToken.split("_") match {
    case Array(id, secret) => 
      col.find(equal("_id", id)).first.toOptionTask.map {
        _.filter(t => validateSecret(secret, t) && signToken(t) == t.sign)
      }
    case _ => 
      log.warn("Wrong refresh token format")
      Task.none
  }

  def delete(refreshToken: String): Task[Unit] = refreshToken.split("_") match {
    case Array(id, secret) => 
      col.deleteOne(equal("_id", id)).toUnitTask
    case _ => 
      log.warn("Wrong refresh token format")
      Task.unit
  }
  
  def list(userId: String): Task[Seq[RefreshToken]] = col.find(equal("userId", userId)).toTask

  def deleteForClient(clientId: String): Task[Unit] = col.deleteMany(equal("clientId", clientId)).toUnitTask

  private def hashSecretPart(secret: String): String = BCrypt.hashpw(secret, BCrypt.gensalt(10))
  private def validateSecret(secret: String, token: RefreshToken): Boolean = BCrypt.checkpw(secret, token.secretHash)
  private def signToken(t: RefreshToken): String =
    keys.codesSigner(s"${t.userId}:${t.scope}:${t.expirationTime}:${t.clientId}:${t.id}")

}