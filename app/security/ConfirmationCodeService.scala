package security

import zio._
import com.fotolog.redis.{BinaryConverter, RedisClient}
import javax.inject.Inject
import models.ConfirmationCode
import play.api.Configuration
import play.api.inject.ApplicationLifecycle
import security.ConfirmationCodeService._
import utils.KryoSerializer

import scala.concurrent.ExecutionContext.Implicits._
import scala.concurrent.Future
import scala.concurrent.ExecutionContext

class ConfirmationCodeService @Inject()(conf: Configuration, lifecycle: ApplicationLifecycle)(implicit ec: ExecutionContext) {

  private val redis = RedisClient(conf.get[String]("redis.host"))
  private val expTime = 12 * 60 * 60

  def create(code: ConfirmationCode, ttl: Option[Int] = None): Task[Unit] =
    Task.fromFuture(ec => redis.setAsync(code.login, code, ttl.getOrElse(expTime))).map(_ => ())

  def retrieveByLogin(login: String): Task[Option[ConfirmationCode]] = Task.fromFuture(ec => redis.getAsync[ConfirmationCode](login))
  
  def consumeByLogin(login: String): Task[Unit] = Task.fromFuture(ec =>redis.delAsync(login)).map(_ => ())

  lifecycle.addStopHook(() => Future.successful(redis.shutdown()))
}

object ConfirmationCodeService {

  val DEFAULT_OTP_LEN = 6

  implicit val converter: BinaryConverter[ConfirmationCode] = new BinaryConverter[ConfirmationCode] {
    override def read(data: Array[Byte]): ConfirmationCode = KryoSerializer.fromBytes[ConfirmationCode](data)

    override def write(v: ConfirmationCode): Array[Byte] = KryoSerializer.toBytes[ConfirmationCode](v)
  }
}