package services

import zio._
import javax.inject.{Inject, Singleton}
import play.api.Configuration
import java.util.{Set => JSet}

import org.slf4j.LoggerFactory
import play.api.mvc.RequestHeader

import scala.jdk.CollectionConverters._

@Singleton
class RegistrationFiltersChain @Inject() (filtersSet: JSet[RegistrationFilter]) {

  val log = LoggerFactory.getLogger(getClass)

  val filters = filtersSet.asScala.toSeq

  if (filters.nonEmpty) {
    log.info(s"Configured registration filters: ${filters.map(_.getClass.getSimpleName).mkString("\n")}")
  } else {
    log.debug("No registration filters configured")
  }


  def apply(regData: RequestHeader): Task[RequestHeader] = {
    if (filters.isEmpty) {
      IO.succeed(regData)
    } else {
      runFilters(regData, filters)
    }
  }

  def runFilters(data: RequestHeader, filters: Seq[RegistrationFilter]): Task[RequestHeader] = filters match {
    case Nil => IO.succeed(data)
    case filter :: Nil => filter.filter(data)
    case filter :: tail => filter.filter(data).flatMap { d =>
      runFilters(d, tail)
    }
  }
}
