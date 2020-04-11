package services

import zio._
import akka.http.scaladsl.util.FastFuture
import javax.inject.{Inject, Singleton}
import play.api.Configuration
import play.api.libs.json.JsValue
import java.util.{Set => JSet}

import org.slf4j.LoggerFactory

import scala.concurrent.{ExecutionContext, Future}
import scala.collection.JavaConverters._

@Singleton
class RegistrationFiltersChain @Inject() (filtersSet: JSet[RegistrationFilter], config: Configuration)(implicit ec: ExecutionContext) {

  val log = LoggerFactory.getLogger(getClass)

  val filters = filtersSet.asScala.toSeq

  if (filters.nonEmpty) {
    log.info(s"Configured registration filters: ${filters.map(_.getClass.getSimpleName).mkString("\n")}")
  } else {
    log.debug("No registration filters configured")
  }


  def apply(regData: JsValue): Task[JsValue] = {
    if (filters.isEmpty) {
      IO.succeed(regData)
    } else {
      runFilters(regData, filters)
    }
  }

  def runFilters(data: JsValue, filters: Seq[RegistrationFilter]): Task[JsValue] = filters match {
    case Nil => IO.succeed(data)
    case filter :: Nil => filter.filter(data)
    case filter :: tail => filter.filter(data).flatMap { d =>
      runFilters(d, tail)
    }
  }
}
