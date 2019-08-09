package services

import java.io.Serializable
import javax.inject.{Inject, Singleton}

import akka.http.scaladsl.util.FastFuture
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.util.ByteString
import com.fotolog.redis.{BinaryConverter, RedisClient}
import com.impactua.bouncer.commons.models.ResponseCode
import com.impactua.bouncer.commons.models.exceptions.AppException
import com.impactua.bouncer.commons.utils.Logging
import play.api.Configuration
import play.api.http.HttpEntity.Streamed
import play.api.http.Status
import play.api.mvc.{ResponseHeader, Result}
import services.RequestTracker._
import utils.KryoSerializer

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

trait RequestTracker {
  def checkForDuplicate(req: StreamedProxyRequest): Future[Option[Result]]
  def storeResponse(req: StreamedProxyRequest, result: Result): Future[Result]
}

@Singleton
class IdleRequestTracker extends RequestTracker {
  override def checkForDuplicate(req: StreamedProxyRequest) = none
  override def storeResponse(req: StreamedProxyRequest, result: Result) = FastFuture.successful(result)
}

@Singleton
class RedisRequestTracker @Inject() (conf: Configuration)
                                    (implicit val mat: Materializer, ec: ExecutionContext) extends RequestTracker {

  final val alreadyReportedStatus = 208
  final val timeout = 6.hours.toSeconds.toInt
  final val converter = BinaryResultConverter
  final val redis = RedisClient(conf.get[String]("redis.host"))

  override def checkForDuplicate(req: StreamedProxyRequest) = if (needToBeStored(req.method, req)) {
    val strKey = key(req)

    redis.getAsync(strKey)(converter) flatMap {
      case Some(Some(data)) =>
        FastFuture.successful(Some(data.toHttpResult(status = alreadyReportedStatus)))
      case Some(None) =>
        throw AppException(ResponseCode.DUPLICATE_REQUEST, "Please wait for a previous request to finish")
      case None =>
        redis.setNxAsync(strKey, Array[Byte](), timeout)
        none
    }
  } else {
    none
  }

  override def storeResponse(req: StreamedProxyRequest, result: Result) = {
    if (needToBeStored(req.method, req)) {
      result.body.consumeData.map { byteString =>
        val binaryResult = CachedResult(byteString, result)
        redis.setAsync[Option[CachedResult]](key(req), Some(binaryResult), timeout)(converter)
        binaryResult.toHttpResult()
      }
    } else {
      FastFuture.successful(result)
    }
  }

}

object RequestTracker {

  val header = "x-request-id"

  final val none = FastFuture.successful(None)

  def needToBeStored(method: String, req: StreamedProxyRequest): Boolean =
    (method == "POST" || method == "PUT" || method == "DELETE") && req.userOpt.isDefined && req.req.headers.hasHeader(header)

  def key(req: StreamedProxyRequest): String = "rq:" + req.userOpt.get.uuidStr + ":" + req.req.headers(header)

  implicit object BinaryResultConverter extends BinaryConverter[Option[CachedResult]] {
    override def read(data: Array[Byte]): Option[CachedResult] = if (data.isEmpty) None else KryoSerializer.fromBytes[Option[CachedResult]](data)

    override def write(v: Option[CachedResult]): Array[Byte] = KryoSerializer.toBytes[Option[CachedResult]](v)
  }
}

case class CachedResult(data: ByteString,
                        contentLength: Option[Long],
                        contentType: Option[String],
                        status: Int,
                        headers: Map[String, String],
                        reasonPhrase: Option[String]) extends Serializable {

  def toHttpResult(status: Int = status): Result = {
    Result(
      ResponseHeader(status, headers, reasonPhrase),
      Streamed(Source.single(data), contentLength, contentType)
    )
  }
}

object CachedResult {
  def apply(data: ByteString, result: Result): CachedResult =
    CachedResult(data,
      result.body.contentLength,
      result.body.contentType,
      result.header.status,
      result.header.headers,
      result.header.reasonPhrase)
}