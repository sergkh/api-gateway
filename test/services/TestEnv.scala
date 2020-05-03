package services

import security.KeysManager
import models.conf.CryptoConfig
import com.fotolog.redis.RedisClient
import com.mohiva.play.silhouette.password.BCryptPasswordHasher
import com.mohiva.play.silhouette.api.util.PasswordHasherRegistry
import models.conf.ConfirmationConfig

object TestEnv {
  val confirmationConf = ConfirmationConfig()

  val redis = RedisClient("redis-mem://test")
  val keysManager = new KeysManager(CryptoConfig())
  
  val confirmationService = new ConfirmationCodeService(keysManager, redis, confirmationConf)

  val passHasherRegistry = PasswordHasherRegistry(new BCryptPasswordHasher())
}
