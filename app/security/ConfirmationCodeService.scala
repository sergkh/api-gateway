package security

import java.util.Base64

import akka.util.ByteString
import zio._
import com.fotolog.redis.{BinaryConverter, RedisClient}
import javax.inject.Inject
import models.ConfirmationCode
import play.api.Configuration
import play.api.inject.ApplicationLifecycle
import play.api.libs.json.{JsObject, JsString, JsSuccess, Json, OFormat}
import security.ConfirmationCodeService._

import scala.concurrent.Future

class ConfirmationCodeService @Inject()(conf: Configuration, lifecycle: ApplicationLifecycle) {

  private val redis = RedisClient(conf.get[String]("redis.host"))
  private val expTime = 12 * 60 * 60

  def create(code: ConfirmationCode, ttl: Option[Int] = None): Task[Unit] =
    Task.fromFuture(ec => redis.setAsync(code.login, code, ttl.getOrElse(expTime))).map(_ => ())

  def retrieveByLogin(login: String): Task[Option[ConfirmationCode]] = Task.fromFuture(_ => redis.getAsync[ConfirmationCode](login))
  
  def consumeByLogin(login: String): Task[Unit] = Task.fromFuture(_ => redis.delAsync(login)).map(_ => ())

  lifecycle.addStopHook(() => Future.successful(redis.shutdown()))
}

object ConfirmationCodeService {

  implicit val requestFormat = OFormat[ConfirmationCode.StoredRequest](
    js => {
      val obj = js.as[JsObject]

      JsSuccess(ConfirmationCode.StoredRequest(
        obj.asOpt[Map[String, String]].map(_.toList).getOrElse(Nil),
        obj.asOpt[String].map(s => Base64.getDecoder.decode(s)).map(ByteString.apply)
      ))
    },
    (r: ConfirmationCode.StoredRequest) => Json.obj(
      "h" -> JsObject(r.headers.map{ case (k,v) => k -> JsString(v)}),
      "req" -> r.req.map(ba => Base64.getEncoder.encodeToString(ba.toArray))
    )
  )
  implicit val confirmationCodeFormat = Json.format[ConfirmationCode]

  val DEFAULT_OTP_LEN = 6

  implicit val converter: BinaryConverter[ConfirmationCode] = new BinaryConverter[ConfirmationCode] {
    override def read(data: Array[Byte]): ConfirmationCode = Json.parse(data).as[ConfirmationCode]
    override def write(v: ConfirmationCode): Array[Byte] = Json.toBytes(Json.toJson(v))
  }
}