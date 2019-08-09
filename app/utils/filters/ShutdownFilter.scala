package utils.filters

import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong}
import javax.inject.{Inject, Singleton}

import akka.stream.Materializer
import com.impactua.bouncer.commons.models.ResponseCode
import com.impactua.bouncer.commons.utils.Logging
import com.impactua.bouncer.commons.web.SignalHandlerInstaller
import play.api.Configuration
import play.api.inject.DefaultApplicationLifecycle
import play.api.libs.json.Json
import play.api.mvc.Results.ServiceUnavailable
import play.api.mvc._

import scala.compat.Platform
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ShutdownFilter @Inject()(appShutdownRegister: ApplicationShutdownRegister)
                              (implicit val mat: Materializer, ec: ExecutionContext) extends Filter with Logging {

  override def apply(nextFilter: (RequestHeader) => Future[Result])(request: RequestHeader): Future[Result] = {
    if (appShutdownRegister.isShutdown) {
      val unavailable = ResponseCode.SERVICE_UNAVAILABLE

      Future.successful(
        ServiceUnavailable(Json.obj(
          "code" -> unavailable.id,
          "error" -> unavailable.toString,
          "message" -> "service unavailable",
          "timestamp" -> Platform.currentTime
        ))
      )
    } else {
      appShutdownRegister.incrementActiveRequests
      val result = nextFilter(request)
      result.onComplete(_ => appShutdownRegister.decrementActiveRequests)
      result
    }
  }

}


// TODO: change to use signals instead
@Singleton
class ApplicationShutdownRegister @Inject()(lifecycle: DefaultApplicationLifecycle, conf: Configuration)(implicit ctx: ExecutionContext) extends Logging {

  private final val MAX_TRIES = 20

  private val delayTime = conf.getOptional[Duration]("filters.shutdown.delay-time").getOrElse(5.seconds).toMillis

  private val isShuttingDown = new AtomicBoolean(false)

  private val activeRequests = new AtomicLong(0L)

  def incrementActiveRequests: Unit = activeRequests.incrementAndGet()
  def decrementActiveRequests: Unit = activeRequests.decrementAndGet()

  SignalHandlerInstaller.installHandler("TERM", "INT") { (oldHandler, signal) =>
    log.warn(s"Shutdown signal $signal received, going into shutdown state")
    var tries = MAX_TRIES
    isShuttingDown.set(true)

    while (activeRequests.get != 0 && tries > 0) {
      tries -= 1
      Thread.sleep(delayTime)
    }

    log.warn(s"Terminating application")
    oldHandler.handle(signal)
  }

  def isShutdown: Boolean = isShuttingDown.get()

}
