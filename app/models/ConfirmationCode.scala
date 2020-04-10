package models

import akka.util.ByteString
import models.ConfirmationCode._
import org.mindrot.jbcrypt.BCrypt
import play.api.libs.json.{JsValue, Json}
import play.api.mvc.{Request, RequestHeader}
import services.CodeGenerator

case class ConfirmationCode(userId: String,
                            ids: List[String],
                            operation: String,
                            codeHash: String,
                            otpLen: Int,      // necessary for regeneration of the code
                            ttl: Int,         // necessary for regeneration of the code
                            expiresAt: Long,
                            signature: String = "") {
  def withSignature(sign: String): ConfirmationCode = copy(signature = sign)
  def expired: Boolean = expiresAt < System.currentTimeMillis()
  def verify(code: String): Boolean = BCrypt.checkpw(code, codeHash)

  def email: Option[String] = ids.find(User.checkEmail)
  def phone: Option[String] = ids.find(User.checkPhone)
}

object ConfirmationCode {
  case class StoredRequest(headers: Seq[(String, String)], req: Option[ByteString])

  val OP_EMAIL_CONFIRM = "email-confirm"
  val OP_PHONE_CONFIRM = "phone-confirm"
  val OP_LOGIN         = "login"
  val OP_PASSWORD_RESET = "password-reset"
}