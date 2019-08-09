package kamon.prometheus.metric

import java.util.regex.Pattern

/** A group of related metrics.  See [[com.monsanto.arch.kamon.prometheus.converter.SnapshotConverter SnapshotConverter]]
  * for information about the role this class plays in mediating between Kamon’s and Prometheus’ data models.
  *
  * @param name the name of the metric, which must conform to Prometheus’ naming guidelines
  * @param prometheusType the Prometheus metric type to which this family corresponds
  * @param help optional descriptive text about the metric
  * @param metrics the individual data that make up this family
  */
case class PrometheusMetricFamily(name: String, prometheusType: PrometheusType, help: Option[String], metrics: Seq[Metric]) {
  require(PrometheusMetricFamily.isValidMetricFamilyName(name), "Name must be a valid Prometheus metric name.")

  /** Returns a copy of the metric family with the given help. */
  def withHelp(newHelp: String) = copy(help = Some(newHelp))
}

private[prometheus] object PrometheusMetricFamily {
  /** The pattern for valid Prometheus metric names. */
  private val MetricNamePattern = Pattern.compile("^[a-zA-Z_:][a-zA-Z0-9_:]*$")

  /** Verifies whether a metric family name is valid. */
  def isValidMetricFamilyName(name: String): Boolean = MetricNamePattern.matcher(name).matches()
}
