package kamon.prometheus

import java.net.InetAddress

import com.typesafe.config.{Config, ConfigFactory}
import kamon.util.ConfigTools.Syntax

import scala.collection.JavaConversions
import scala.concurrent.duration.FiniteDuration

/** A settings object used for configuring how the extension should behave.
  *
  * @param config the configuration source for the settings.  Note that in normal operation, this will come from
  *               Kamon’s actor system
  *
  * @author Daniel Solano Gómez
  */
class PrometheusSettings(config: Config) {
  config.checkValid(ConfigFactory.defaultReference(), "kamon.prometheus")
  private val prometheusConfig = config.getConfig("kamon.prometheus")

  /** The extension’s endpoint will serve new results according to this value. */
  val flushInterval: FiniteDuration = prometheusConfig.getFiniteDuration("flush-interval")

  /** The subscriptions that determine which metrics the extension will publish to Prometheus. */
  val subscriptions: Map[String, List[String]] = {
    import JavaConversions.asScalaBuffer

    val subs: Config = prometheusConfig.getConfig("subscriptions")
    subs.firstLevelKeys.map { category ⇒
      category → subs.getStringList(category).toList
    }.toMap
  }

  /** All of the labels that will be added to all published metrics. */
  val labels: Map[String, String] = {
    val labelsConfig = prometheusConfig.getConfig("labels")
    val defaultLabels = Map("host" -> InetAddress.getLocalHost.getHostName)
    defaultLabels ++ labelsConfig.firstLevelKeys.map(name ⇒ name → labelsConfig.getString(name)).toMap
  }
}
