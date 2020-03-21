package security

import com.fotolog.redis.{BinaryConverter, RedisClient}
import javax.inject.Inject
import models.ConfirmationCode
import play.api.Configuration
import play.api.inject.ApplicationLifecycle
import security.ConfirmationCodeService._
import utils.KryoSerializer

import scala.concurrent.ExecutionContext.Implicits._
import scala.concurrent.Future

class ConfirmationCodeService @Inject()(conf: Configuration, lifecycle: ApplicationLifecycle) {

  private val redis = RedisClient(conf.get[String]("redis.host"))
  private val expTime = 12 * 60 * 60

  def create(code: ConfirmationCode, ttl: Option[Int] = None): Option[ConfirmationCode] = {
    redis.setAsync(code.login, code, ttl.getOrElse(expTime))
    Some(code)
  }

  def retrieveByLogin(login: String): Future[Option[ConfirmationCode]] = {
    redis.getAsync[ConfirmationCode](login) map { codeOpt =>
      codeOpt
    }
  }

  def consumeByLogin(login: String) {
    redis.del(login)
  }

  lifecycle.addStopHook(() => Future.successful(redis.shutdown()))
}

object ConfirmationCodeService {

  val DEFAULT_OTP_LEN = 6

  implicit val converter: BinaryConverter[ConfirmationCode] = new BinaryConverter[ConfirmationCode] {
    override def read(data: Array[Byte]): ConfirmationCode = KryoSerializer.fromBytes[ConfirmationCode](data)

    override def write(v: ConfirmationCode): Array[Byte] = KryoSerializer.toBytes[ConfirmationCode](v)
  }
}