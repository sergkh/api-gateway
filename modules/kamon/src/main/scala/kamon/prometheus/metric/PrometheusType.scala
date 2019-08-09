package kamon.prometheus.metric

/** Designates which type the metric family belongs to according to the Prometheus data model.
  *
  * @param prometheusType the Prometheus type, as encoded the ProtoBuf-generated classes
  * @param text the textual representation of the type, as encoded in the Prometheus text format
  */
sealed abstract class PrometheusType(val prometheusType: MetricType.Value, val text: String)

object PrometheusType {
  /** Used for all counter metrics. */
  case object Counter extends PrometheusType(MetricType.COUNTER, "counter")

  /** Used for all histogram metrics. */
  case object Histogram extends PrometheusType(MetricType.HISTOGRAM, "histogram")
}

object MetricType extends Enumeration {
  val COUNTER = Value(0)
  val GAUGE = Value(1)
  val SUMMARY = Value(2)
  val UNTYPED = Value(3)
  val HISTOGRAM = Value(4)
}
