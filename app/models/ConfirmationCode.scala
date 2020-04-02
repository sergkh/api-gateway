package models

import akka.util.ByteString
import models.ConfirmationCode._
import org.mindrot.jbcrypt.BCrypt
import play.api.libs.json.JsValue
import play.api.mvc.{Request, RequestHeader}
import services.CodeGenerator



case class ConfirmationCode(login: String,
                            operation: String,
                            codeHash: String,
                            otpLength: Int,
                            request: Option[StoredRequest] = None) {
  def regenerate(): (String, ConfirmationCode) = {
    val code = CodeGenerator.generateNumericPassword(otpLength, otpLength)
    code -> copy(codeHash = BCrypt.hashpw(code, BCrypt.gensalt(SALT)))
  }

  def verify(code: String): Boolean = BCrypt.checkpw(code, codeHash)
}

object ConfirmationCode {
  type StoredRequest = (Seq[(String, String)], Option[ByteString])

  val OP_EMAIL_CONFIRM = "email-confirm"
  val OP_PHONE_CONFIRM = "phone-confirm"
  val OP_LOGIN    = "login"
  val OP_PASSWORD_RESET = "password-reset"

  final val SALT = 10

  def generatePair(login: String,
            operation: String,
            otpLength: Int,
            body: Option[StoredRequest]): (String, ConfirmationCode) = {
    ConfirmationCode(login, operation, "", otpLength, request = body).regenerate()
  }

  def generatePair(login: String, request: RequestHeader, otpLength: Int, body: Option[ByteString]): (String, ConfirmationCode) = {
    generatePair(login, request.method + " " + request.path + request.rawQueryString, otpLength,
      Some((request.headers.headers, body))
    )
  }

  def generatePair(login: String, otpLength: Int, request: Request[JsValue]): (String, ConfirmationCode) =
    generatePair(login, request, otpLength, Some(ByteString(request.body.toString())))
}