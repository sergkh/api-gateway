package services

import com.fotolog.redis.{BinaryConverter, RedisClient}
import javax.inject.{Inject, Singleton}
import models.AuthCode
import play.api.Configuration
import zio._
import play.api.libs.json.Json

/**
  * Service manages short term Authentication codes in redis.
  */
@Singleton
class AuthCodesService @Inject() (conf: Configuration) {
  private[this] final val redis = RedisClient(conf.get[String]("redis.host"))

  private[this] implicit val format = Json.format[AuthCode]

  private[this] implicit object BinaryResultConverter extends BinaryConverter[AuthCode] {
    override def read(data: Array[Byte]): AuthCode = Json.parse(data).as[AuthCode]
    override def write(v: AuthCode): Array[Byte] = Json.toBytes(Json.toJson(v))
  }

  private[this] val prefix = "auth-code:"

  def store(code: AuthCode): Task[AuthCode] = {
    Task.fromFuture(ec => 
      redis.setAsync(key(code.id), code, code.expireIn)
    ).map(_ => code)
  }

  def getAndRemove(id: String): Task[Option[AuthCode]] = for {
    codeOpt <- Task.fromFuture(ec => redis.getAsync[AuthCode](id))
    _       <- Task.fromFuture(ec => redis.delAsync(id))
  } yield codeOpt
  

  @inline
  private[this] def key(id: String): String = prefix + id
}
