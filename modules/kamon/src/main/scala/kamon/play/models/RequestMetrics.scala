package kamon.play.models

import java.util.concurrent.atomic.AtomicLong

import kamon.metric.instrument.{CollectionContext, CounterSnapshot, InstrumentFactory, Time}
import kamon.metric.{DefaultEntitySnapshot, EntityRecorderFactory, EntitySnapshot, GenericEntityRecorder}
import kamon.play.Keys
import play.api.mvc.Result

/**
  *
  * @author Yaroslav Derman <yaroslav.derman@gmail.com>
  *         created on 27/06/17
  */
class TotalRequestMetrics(instrumentFactory: InstrumentFactory) extends GenericEntityRecorder(instrumentFactory) {
  private val connections = counter("connections")
  private val openConnections = histogram("open-connections")
  private val requests = counter("requests")
  private val openRequests = histogram("open-requests")
}

class RequestMetrics(instrumentFactory: InstrumentFactory) extends GenericEntityRecorder(instrumentFactory) {

  private val activeReq = new AtomicLong(0L)

  def incrementActiveRequests = activeReq.incrementAndGet()

  def decrementActiveRequests = activeReq.decrementAndGet()

  def activeRequests = activeReq.get()

  def recordTimeResponse(traceName: String, time: Long, result: Result): Unit = {
    histogram(traceName + ".request-time", Time.Milliseconds).record(time)
    histogram(traceName + s".${result.header.status}.request-time", Time.Milliseconds).record(time)
  }

  override def collect(collectionContext: CollectionContext): EntitySnapshot = {
    val parentSnapshot = super.collect(collectionContext)
    val metrics = parentSnapshot.metrics ++ Map(
      Keys.counterKey("active-requests") → CounterSnapshot(activeRequests)
    )   

    new DefaultEntitySnapshot(metrics)
  }

}


object RequestMetrics extends EntityRecorderFactory[RequestMetrics] {
  override def category = "http-requests"

  override def createRecorder(instrumentFactory: InstrumentFactory): RequestMetrics = new RequestMetrics(instrumentFactory)

}
