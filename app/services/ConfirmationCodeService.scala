package services

import com.fotolog.redis.{BinaryConverter, RedisClient}
import javax.inject.{Inject, Singleton}
import models.ConfirmationCode
import org.mindrot.jbcrypt.BCrypt
import play.api.Configuration
import play.api.inject.ApplicationLifecycle
import play.api.libs.json.Json
import security.KeysManager
import utils.Logging
import zio._

import scala.concurrent.Future
import scala.concurrent.duration._

@Singleton
class ConfirmationCodeService @Inject()(conf: Configuration, keys: KeysManager, lifecycle: ApplicationLifecycle) extends Logging {
  final val SALT_ITERATIONS = 10

  private val redis = RedisClient(conf.get[String]("redis.host"))

  private val DefaultTTL = 3.hours.toSeconds.toInt
  private val prefix = "ccds:"

  private val logOtp = conf.getOptional[Boolean]("confirmation.otp.log").getOrElse(false)

  if (logOtp) {
    log.warn("!WARNING : OTP codes logging is on")
  }

  def create(userId: String, searchIdentifiers: List[String], operation: String, otp: String,  ttl: Int = DefaultTTL): Task[Unit] = {

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

    if (logOtp) {
      log.info(s"OTP code for $userId: $otp")
    }

    val signedCode = code.withSignature(signCode(code))

    Task.collectAll(searchIdentifiers.map { id =>
      Task.fromFuture(_ => 
        redis.setAsync(prefix + id, signedCode, ttl)
      )
    }).map(_ => ())
  }

  def get(login: String): Task[Option[ConfirmationCode]] =
    Task.fromFuture(_ => redis.getAsync[ConfirmationCode](prefix + login)).map {codeOpt => 
      codeOpt.filterNot(c => c.expired || c.signature != signCode(c))
    }
  
  def consume(login: String, code: String): Task[Option[ConfirmationCode]] = for {
    codeOpt <- get(login)
    _       <- if (codeOpt.isDefined) Task.fromFuture(_ => redis.delAsync(login)) else Task.unit
  } yield codeOpt.filter(_.verify(code))

  private def hashOtpCode(code: String): String = BCrypt.hashpw(code, BCrypt.gensalt(SALT_ITERATIONS))

  private def signCode(c: ConfirmationCode): String =
    keys.codesSigner(s"${c.userId}:${c.ids}:${c.operation}:${c.codeHash}:${c.otpLen}:${c.expiresAt}:${c.ttl}")

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