package services

import javax.inject.{Inject, Singleton}

import com.fotolog.redis.{BinaryConverter, RedisClient}
import play.api.Configuration
import models.AuthCode
import scala.concurrent.{Future, ExecutionContext}
import utils.KryoSerializer

/**
  * Service manages short term Authentication codes in redis.
  */
@Singleton
class AuthCodesService @Inject() (conf: Configuration) (implicit ec: ExecutionContext) {
  private[this] final val redis = RedisClient(conf.get[String]("redis.host"))

  private[this] implicit object BinaryResultConverter extends BinaryConverter[AuthCode] {
    override def read(data: Array[Byte]): AuthCode = KryoSerializer.fromBytes[AuthCode](data)
    override def write(v: AuthCode): Array[Byte] = KryoSerializer.toBytes[AuthCode](v)
  }

  private[this] val prefix = "auth-code:"

  def store(code: AuthCode): Future[AuthCode] = {
    redis.setAsync(key(code.id), code, code.expireIn).map(_ => code)
  }

  def getAndRemove(id: String): Future[Option[AuthCode]] = for {
    codeOpt <- redis.getAsync[AuthCode](id)
    _       <- redis.delAsync(id)
  } yield codeOpt
  

  @inline
  private[this] def key(id: String): String = prefix + id
}
