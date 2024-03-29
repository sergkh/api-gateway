package module

import javax.inject.Singleton
import com.google.inject.AbstractModule
import events.EventsStream
import net.codingwell.scalaguice.ScalaModule
import services.{ZioEventStream, ServicesManager}
import com.google.inject.Provides
import play.api.Configuration
import net.ceedubs.ficus.Ficus._
import net.ceedubs.ficus.readers.ArbitraryTypeReader._
import net.ceedubs.ficus.readers.{EnumerationReader, ValueReader}
import models.conf._
import com.fotolog.redis.RedisClient
import play.api.Logger

/**
  * @author Yaroslav Derman <yaroslav.derman@gmail.com>
  */
class GeneralModule extends AbstractModule with ScalaModule {

  val log = Logger(getClass())

  override def configure(): Unit = {
    bind[ServicesManager].asEagerSingleton()
    bind[EventsStream].to[ZioEventStream].asEagerSingleton()
  }

  @Provides
  @Singleton
  def registrationConfig(conf: Configuration): RegistrationConfig = {
    val r = RegistrationConfig(
      conf.get[Boolean]("registration.requirePassword"),
      conf.get[String]("registration.requiredFields").split(",").map(_.trim).toList
    )

    log.info(s"Required user identifiers: ${r.requiredFields.mkString(",")}. Password required: ${r.requirePassword}")

    r
  }

  @Provides
  @Singleton
  def otpConfig(conf: Configuration): ConfirmationConfig = {
    val c = conf.underlying.as[ConfirmationConfig]("confirmation")

    if (c.log) {
      log.warn("!WARNING : OTP codes logging is on. Do not use it in production")
    }

    c
  }

  @Provides
  @Singleton
  def cryptoConfig(conf: Configuration): CryptoConfig = conf.underlying.as[CryptoConfig]("crypto")

  @Provides
  @Singleton
  def authConfig(conf: Configuration): AuthConfig = conf.underlying.as[AuthConfig]("auth")

  @Provides
  def redis(conf: Configuration): RedisClient = RedisClient(conf.get[String]("redis.host"))
}
