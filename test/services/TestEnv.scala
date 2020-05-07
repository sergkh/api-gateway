package services

import zio._
import security.KeysManager
import models.conf.CryptoConfig
import com.fotolog.redis.RedisClient
import com.mohiva.play.silhouette.password.BCryptPasswordHasher
import com.mohiva.play.silhouette.api.util.PasswordHasherRegistry
import models.conf.ConfirmationConfig
import services.ClientAuthenticator

object TestEnv {
  val confirmationConf = ConfirmationConfig()

  val redis = RedisClient("redis-mem://test")
  val keysManager = new KeysManager(CryptoConfig())
  
  val confirmationService = new ConfirmationCodeService(keysManager, redis, confirmationConf)

  val passHasherRegistry = PasswordHasherRegistry(new BCryptPasswordHasher())

  val anyClientAuth = new ClientAuthenticator() {
    def authenticateClient(clientId: String, clientSecret: String): Task[Boolean] = Task.succeed(true)
  }

}
