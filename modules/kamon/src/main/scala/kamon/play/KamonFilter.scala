package kamon.play

import javax.inject.{Inject, Singleton}

import akka.stream.Materializer
import kamon.Kamon
import kamon.play.models.{KamonExtensions, RequestMetrics}
import play.api.http.HttpErrorHandler
import play.api.mvc.{Filter, RequestHeader, Result}

import scala.compat.Platform
import scala.concurrent.{ExecutionContext, Future}

/**
  *
  * @author Yaroslav Derman <yaroslav.derman@gmail.com>
  *         created on 29/06/17
  */
@Singleton
class KamonFilter @Inject()(errorHandler: HttpErrorHandler)(implicit val mat: Materializer, ec: ExecutionContext) extends Filter {

  private val kamonRequestRecorder = Kamon.metrics.entity(RequestMetrics, "kamon-filter")

  override def apply(next: (RequestHeader) => Future[Result])(requestHeader: RequestHeader): Future[Result] = {

    kamonRequestRecorder.incrementActiveRequests

    val name = KamonExtensions.generateTraceName(requestHeader)

    val start = Platform.currentTime
    next.apply(requestHeader).map { res =>
      val end = Platform.currentTime
      kamonRequestRecorder.recordTimeResponse(name, end - start, res)
      kamonRequestRecorder.decrementActiveRequests
      res
    }.recoverWith {
      case ex =>
        val end = Platform.currentTime
        errorHandler.onServerError(requestHeader, ex).map { res =>
          kamonRequestRecorder.recordTimeResponse(name, end - start, res)
          kamonRequestRecorder.decrementActiveRequests
          res
        }
    }
  }

}
