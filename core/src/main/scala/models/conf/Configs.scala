package models.conf

import scala.concurrent.duration._

case class RegistrationConfig(
  requirePassword: Boolean = true,
  requiredFields: List[String] = Nil
)

case class ConfirmationConfig(
  phone: ConfirmationConfig.OtpConfig, 
  email: ConfirmationConfig.OtpConfig,
  log: Boolean = false
)

object ConfirmationConfig {
  case class OtpConfig(length: Int = 6, ttl: FiniteDuration = 10.minutes)
}


