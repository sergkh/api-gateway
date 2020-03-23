package services

import akka.http.scaladsl.util.FastFuture
import javax.inject.{Inject, Singleton}
import play.api.Configuration
import play.api.libs.json.JsValue
import services.RegistrationFilter
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


  def apply(regData: JsValue): Future[JsValue] = {
    if (filters.isEmpty) {
      FastFuture.successful(regData)
    } else {
      runFilters(regData, filters)
    }
  }

  def runFilters(data: JsValue, filters: Seq[RegistrationFilter]): Future[JsValue] = filters match {
    case Nil => FastFuture.successful(data)
    case filter :: Nil => filter.filter(data)
    case filter :: tail => filter.filter(data).flatMap { d =>
      runFilters(d, tail)
    }
  }
}
