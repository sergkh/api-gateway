package service.fakes

import play.api.mvc.{Result, Results}
import services.{ProxyService, StreamedProxyRequest}

import scala.concurrent.Future

/**
  * @author Yaroslav Derman <yaroslav.derman@gmail.com>.
  *         created on 07.08.2016.
  */
class TestProxy extends ProxyService {
  override def pass(serviceName: String, path: String, request: StreamedProxyRequest, params: Seq[(String, String)], headers: Seq[(String, String)]): Future[Result] = Future.successful(Results.Ok)

  override def passToUrl(url: String, secretKey: String, request: StreamedProxyRequest, params: Seq[(String, String)], headers: Seq[(String, String)]): Future[Result] = Future.successful(Results.Ok)
}
