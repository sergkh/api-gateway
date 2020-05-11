package models.conf

import scala.concurrent.duration._
import scala.io.Source

case class RegistrationConfig(
  requirePassword: Boolean = true,
  requiredFields: List[String] = Nil
)

case class ConfirmationConfig(
  phone: ConfirmationConfig.OtpConfig = ConfirmationConfig.OtpConfig(), 
  email: ConfirmationConfig.OtpConfig = ConfirmationConfig.OtpConfig(),
  log: Boolean = false
)

object ConfirmationConfig {
  case class OtpConfig(length: Int = 6, ttl: FiniteDuration = 10.minutes)
}


case class CryptoConfig(
  keystore: CryptoConfig.KeystoreConfig = CryptoConfig.KeystoreConfig(),
  accessToken: CryptoConfig.AccessTokenConfig = CryptoConfig.AccessTokenConfig(),
  authCodes: CryptoConfig.AuthCodesConfig = CryptoConfig.AuthCodesConfig()
)

object CryptoConfig {
  case class KeystoreConfig(file: Option[String] = None, password: Option[String] = None, passwordFile: Option[String] = None) {
    def pass: Option[String] = password orElse passwordFile.map(f => Source.fromFile(f).getLines().mkString)
  }

  case class AccessTokenConfig(
    signKeyAlias: Option[String] = None,
    deprecatedKeyAliases: Option[String] = None
  ) {
    /** Map of Key IDs to alias in keystore for deprecated key aliases */
    def deprecatedKeyAliasesList: List[String] = deprecatedKeyAliases
      .map(_.split(",").toList.map(_.trim)).getOrElse(Nil)
  }
  
  case class AuthCodesConfig(signKeyAlias: Option[String] = None)
}

case class AuthConfig(
  implicitFlowEnabled: Boolean = true,
  refreshTokenTTL: FiniteDuration = 365 days,
  authCodeTTL: FiniteDuration = 10 minutes
)