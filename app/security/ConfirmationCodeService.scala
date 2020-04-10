package security

import java.util.Base64

import akka.util.ByteString
import zio._
import com.fotolog.redis.{BinaryConverter, RedisClient}
import org.mindrot.jbcrypt.BCrypt
import javax.inject.Inject
import models.ConfirmationCode
import play.api.Configuration
import play.api.inject.ApplicationLifecycle
import play.api.libs.json.{JsObject, JsString, JsSuccess, Json, OFormat}
import security.ConfirmationCodeService._

import scala.concurrent.Future
import java.time.LocalDateTime
import play.components.CryptoComponents
import play.api.libs.crypto.CookieSigner

class ConfirmationCodeService @Inject()(conf: Configuration, 
                                        crypto: CryptoComponents,
                                        lifecycle: ApplicationLifecycle) {
  final val SALT_ITERATIONS = 10

  private val secret = conf.get[String]("play.http.secret.key")
  private val redis = RedisClient(conf.get[String]("redis.host"))
  private val DefaultTTL = 12 * 60 * 60
  private val prefix = "ccds:"

  def create(userId: String,
             searchIdentifiers: List[String],
             operation: String, 
             otp: String,
             ttl: Int = DefaultTTL): Task[Unit] = {

    val expiresAt = System.currentTimeMillis() + ttl * 1000L

    val code = ConfirmationCode(
      userId,
      searchIdentifiers,
      operation,
      hashOtpCode(otp),
      otp.length(),
      ttl = ttl,
      expiresAt = expiresAt
    )

    val signedCode = code.withSignature(signCode(code))

    Task.collectAll(searchIdentifiers.map { id =>
      Task.fromFuture(ec => redis.setAsync(prefix + id, signedCode, ttl))
    }).map(_ => ())
  }

  def get(login: String): Task[Option[ConfirmationCode]] = 
    Task.fromFuture(_ => redis.getAsync[ConfirmationCode](login)).map(_.filterNot(c => c.expired || c.signature != signCode(c)))
  
  def consume(login: String, code: String): Task[Option[ConfirmationCode]] = for {
    codeOpt <- get(login)
    _       <- if (codeOpt.isDefined) Task.fromFuture(_ => redis.delAsync(login)) else Task.unit
  } yield codeOpt.filter(_.verify(code))
    

  private def hashOtpCode(code: String): String = BCrypt.hashpw(code, BCrypt.gensalt(SALT_ITERATIONS))

  private def signCode(c: ConfirmationCode): String =
    crypto.cookieSigner().sign(s"${c.userId}:${c.ids}:${c.operation}:${c.codeHash}:${c.otpLen}:${c.expiresAt}:${c.ttl}")

  lifecycle.addStopHook(() => Future.successful(redis.shutdown()))
}

object ConfirmationCodeService {

  implicit val confirmationCodeFormat = Json.format[ConfirmationCode]

  val DEFAULT_OTP_LEN = 6

  implicit val converter: BinaryConverter[ConfirmationCode] = new BinaryConverter[ConfirmationCode] {
    override def read(data: Array[Byte]): ConfirmationCode = Json.parse(data).as[ConfirmationCode]
    override def write(v: ConfirmationCode): Array[Byte] = Json.toBytes(Json.toJson(v))
  }
}