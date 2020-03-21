package services


import com.google.inject.ImplementedBy
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import play.api.Configuration
import play.api.libs.Codecs
import play.api.mvc.RequestHeader

import scala.compat.Platform
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.Try

@ImplementedBy(classOf[HmacConfirmationProvider])
trait ConfirmationProvider {
  def confirmationHeader(code: String, secret: String): (String, String)
  def verifyConfirmed(implicit req: RequestHeader): Boolean
}


class HmacConfirmationProvider @Inject() (config: Configuration) extends ConfirmationProvider {

  val ttl = 10 seconds
  val secret = config.getOptional[String]("play.http.secret.key")
    .getOrElse(throw new RuntimeException("Undefined play.http.secret.key value")).getBytes

  def verifyConfirmed(implicit request: RequestHeader) = {
    request.headers.get("x-confirm") exists { header =>
      header.split(";") match {
        case Array(code, timestamp, signature) =>
          Try {
            (calculateHMAC(code + ";" + timestamp, secret) == signature) && (timestamp.toLong >= Platform.currentTime - ttl.toMillis)
          }.getOrElse { false }
        case _ =>
          false
      }
    }
  }

  def confirmationHeader(code: String, secret: String): (String, String) = {
    val timeCode = code + ";" + Platform.currentTime
    "x-confirm" -> (timeCode + ";" + calculateHMAC(timeCode, secret.getBytes))
  }

  def calculateHMAC(data: String, secret: Array[Byte]) = {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(new SecretKeySpec(secret, "HmacSHA1"))
    Codecs.toHexString(mac.doFinal(data.getBytes()))
  }
}