package kamon.play

import akka.actor.{Actor, ActorLogging, Props}
import kamon.metric.SubscriptionsDispatcher.TickMetricSnapshot
import kamon.metric.instrument.{Counter, Histogram}
import kamon.play.TracePrinter._

/**
  *
  * @author Yaroslav Derman <yaroslav.derman@gmail.com>
  *         created on 29/06/17
  */
class TracePrinter extends Actor with ActorLogging {

  def receive = {
    case tick: TickMetricSnapshot ⇒
      tick.metrics foreach {
        case (entity, snapshot) =>

          val histograms = Map.newBuilder[String, Histogram.Snapshot]
          val counters = Map.newBuilder[String, Counter.Snapshot]
          val minMaxCounters = Map.newBuilder[String, Histogram.Snapshot]
          val gauges = Map.newBuilder[String, Histogram.Snapshot]

          val metricsData = StringBuilder.newBuilder

          metricsData.append(
            """
              |+--------------------------------------------------------------------------------------------------+
              ||                                                                                                  |
              ||                                         ENTITY                                                   |
              ||                                       -------------                                              |
              ||    Name %s, Category %s, Tags: %s                             -------------                                              |
              ||                                       -------------                                              |
              |""".format(entity.name, entity.category, entity.tags))

          log.info(metricsData.toString())

          snapshot.counters.foreach {
            case (key, sna) => counters += (key.name -> sna)
          }

          snapshot.histograms.foreach {
            case (key, sna) => histograms += (key.name -> sna)
          }

          snapshot.gauges.foreach {
            case (key, sna) => gauges += (key.name -> sna)
          }

          snapshot.minMaxCounters.foreach {
            case (key, sna) => minMaxCounters += (key.name -> sna)
          }

          logMetrics(histograms.result(), counters.result(), minMaxCounters.result(), gauges.result())
      }
  }

  def logMetrics(histograms: Map[String, Histogram.Snapshot],
                 counters: Map[String, Counter.Snapshot], minMaxCounters: Map[String, Histogram.Snapshot],
                 gauges: Map[String, Histogram.Snapshot]): Unit = {

    if (histograms.isEmpty && counters.isEmpty && minMaxCounters.isEmpty && gauges.isEmpty) {
      log.info("No metrics reported")
      return
    }

    val metricsData = StringBuilder.newBuilder

    metricsData.append(
      """
        |+--------------------------------------------------------------------------------------------------+
        ||                                                                                                  |
        ||                                         Counters                                                 |
        ||                                       -------------                                              |
        |""".stripMargin)

    counters.foreach { case (name, snapshot) ⇒ metricsData.append(userCounterString(name, snapshot)) }

    metricsData.append(
      """||                                                                                                  |
         ||                                                                                                  |
         ||                                        Histograms                                                |
         ||                                      --------------                                              |
         |""".stripMargin)

    histograms.foreach {
      case (name, snapshot) ⇒
        metricsData.append("|  %-40s                                                        |\n".format(name))
        metricsData.append(compactHistogramView(snapshot))
        metricsData.append("\n|                                                                                                  |\n")
    }

    metricsData.append(
      """||                                                                                                  |
         ||                                      MinMaxCounters                                              |
         ||                                    -----------------                                             |
         |""".stripMargin)

    minMaxCounters.foreach {
      case (name, snapshot) ⇒
        metricsData.append("|  %-40s                                                        |\n".format(name))
        metricsData.append(histogramView(snapshot))
        metricsData.append("\n|                                                                                                  |\n")
    }

    metricsData.append(
      """||                                                                                                  |
         ||                                          Gauges                                                  |
         ||                                        ----------                                                |
         |"""
        .stripMargin)

    gauges.foreach {
      case (name, snapshot) ⇒
        metricsData.append("|  %-40s                                                        |\n".format(name))
        metricsData.append(histogramView(snapshot))
        metricsData.append("\n|                                                                                                  |\n")
    }

    metricsData.append(
      """||                                                                                                  |
         |+--------------------------------------------------------------------------------------------------+"""
        .stripMargin)

    log.info(metricsData.toString())
  }

  def userCounterString(counterName: String, snapshot: Counter.Snapshot): String = {
    "|             %30s  =>  %-12s                                     |\n"
      .format(counterName, snapshot.count)
  }

  def compactHistogramView(histogram: Histogram.Snapshot): String = {
    val sb = StringBuilder.newBuilder

    sb.append("|    Min: %-11s  50th Perc: %-12s   90th Perc: %-12s   95th Perc: %-12s |\n".format(
      histogram.min, histogram.percentile(50.0D), histogram.percentile(90.0D), histogram.percentile(95.0D)))
    sb.append("|                      99th Perc: %-12s 99.9th Perc: %-12s         Max: %-12s |".format(
      histogram.percentile(99.0D), histogram.percentile(99.9D), histogram.max))

    sb.toString()
  }

  def histogramView(histogram: Histogram.Snapshot): String =
    "|          Min: %-12s           Average: %-12s                Max: %-12s      |"
      .format(histogram.min, histogram.average, histogram.max)

}

object TracePrinter {

  def props = Props[TracePrinter]

  implicit class RichHistogramSnapshot(histogram: Histogram.Snapshot) {
    def average: Double = {
      if (histogram.numberOfMeasurements == 0) 0D
      else histogram.sum / histogram.numberOfMeasurements
    }
  }

}




