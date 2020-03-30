package services

import akka.stream.scaladsl.Source
import akka.util.ByteString
import javax.inject.{Inject, Singleton}
import play.api.Configuration
import play.api.http.{HeaderNames, HttpEntity}
import play.api.libs.ws._
import play.api.mvc._
import utils.Logging

import scala.concurrent.{ExecutionContext, Future}

/**
  *
  * @author Yaroslav Derman <yaroslav.derman@gmail.com>
  *         created on 08/12/16
  */
trait ProxyService {

  def pass(serviceName: String, path: String, request: StreamedProxyRequest,
           params: Seq[(String, String)] = Seq(), headers: Seq[(String, String)] = Seq()): Future[Result]

  def passToUrl(url: String, secretKey: String, request: StreamedProxyRequest,
                params: Seq[(String, String)] = Seq(), headers: Seq[(String, String)] = Seq()): Future[Result]

}

@Singleton
class WsProxyService @Inject()(ws: WSClient, conf: Configuration)(implicit exec: ExecutionContext)
  extends ProxyService with Logging {

  final val outputHeadersFilter = List("content-type", "content-length")

  def pass(serviceName: String,
           path: String,
           request: StreamedProxyRequest,
           params: Seq[(String, String)] = Seq(),
           headers: Seq[(String, String)] = Seq()): Future[Result] = {

    val serviceConf = conf.get[Configuration](serviceName)
    val baseUrl = serviceConf.get[String]("url")

    // remove unnecessary slashes
    val url = if (baseUrl.endsWith("/") && path.startsWith("/")) {
      baseUrl + path.substring(1)
    } else {
      baseUrl + path
    }

    val secret = serviceConf.get[String]("secret")

    passToUrl(url, secret, request, params, headers)
  }

  def passToUrl(url: String, secretKey: String,
                request: StreamedProxyRequest,
                params: Seq[(String, String)] = Seq(),
                headers: Seq[(String, String)] = Seq()): Future[Result] = {
      log.info(s"Proxy $request to $url")
      val body = request.body.map(SourceBody)

      val call = ws.url(url)
        .withMethod(request.method)
        .addQueryStringParameters(queryParams(request, params): _*)
        .withHttpHeaders(buildHeaders(request, secretKey, headers): _*)

      val callWithBody = body.map(call.withBody(_)) getOrElse call

      callWithBody.stream().map { response =>
        resultBuilder(response.headers, response.status, response.bodyAsSource)
      }
  }

  @inline
  private def queryParams(request: StreamedProxyRequest, params: Seq[(String, String)] = Seq()): Seq[(String, String)] = {
    request.req.queryString.toSeq.flatMap { case (k, values) => values.map(k -> _) } ++ params
  }

  @inline
  private def buildHeaders(request: StreamedProxyRequest, secretKey: String, headers: Seq[(String, String)]) = {
    request.req.headers.headers ++ headers
  }

  @inline
  private def resultBuilder(headers: Map[String, Seq[String]], status: Int, src: Source[ByteString, _]): Result = {
    val contentType = headers.get(HeaderNames.CONTENT_TYPE).flatMap(_.headOption)

    val length = headers.get(HeaderNames.CONTENT_LENGTH) match {
      case Some(Seq(l)) => Option(l).map(_.toLong)
      case _ => None
    }

    val filteredHeaders = headers.filterKeys(key => !outputHeadersFilter.contains(key.toLowerCase)).mapValues(_.head)

    Result(
      ResponseHeader(status, filteredHeaders), HttpEntity.Streamed(src, length, contentType)
    )
  }

}

case class StreamedProxyRequest(req: Request[_],
                                source: Option[Source[ByteString, _]],
                                methodOpt: Option[String] = None) {
  def method: String = methodOpt.getOrElse(req.method)

  def body: Option[Source[ByteString, _]] = source.filter { _ =>
    req.hasBody && req.method != "GET" && req.method != "DELETE"
  }

  override def toString = req.method + " " + req.uri
}