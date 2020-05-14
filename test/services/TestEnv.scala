package services

import zio._
import security.KeysManager
import models._
import com.fotolog.redis.RedisClient
import com.mohiva.play.silhouette.password.BCryptPasswordHasher
import com.mohiva.play.silhouette.api.util.PasswordHasherRegistry
import models.conf.{ConfirmationConfig, CryptoConfig}
import services.ClientAuthenticator
import java.{util => ju}

object TestEnv {
  val KnownClientId = "test"
  val TestClientAuth = "Authorization" -> ("Basic " + ju.Base64.getEncoder().encodeToString(s"$KnownClientId:test".getBytes()))

  val confirmationConf = ConfirmationConfig()

  val redis = RedisClient("redis-mem://test")
  val keysManager = new KeysManager(CryptoConfig())
  
  val confirmationService = new ConfirmationCodeService(keysManager, redis, confirmationConf)

  val passHasherRegistry = PasswordHasherRegistry(new BCryptPasswordHasher())

  val testClientAuthenticator = new ClientAuthenticator() {
    def authenticateClient(clientId: String, clientSecret: String): Task[Boolean] = Task.succeed(clientId == KnownClientId)
  }
}
