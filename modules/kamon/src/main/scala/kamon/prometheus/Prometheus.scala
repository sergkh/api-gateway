package kamon.prometheus

import akka.ConfigurationException
import akka.actor.{ExtendedActorSystem, Extension, ExtensionId, ExtensionIdProvider}
import akka.event.Logging
import kamon.Kamon
import kamon.metric.TickMetricSnapshotBuffer
import kamon.prometheus.converter.SnapshotConverter
import kamon.prometheus.metric.PrometheusMetricFamily

import scala.concurrent.{Future, Promise}
import scala.util.control.NonFatal

object Prometheus extends ExtensionId[PrometheusExtension] with ExtensionIdProvider {

  private val kamonInstancePromise = Promise[PrometheusExtension]()

  lazy val kamonInstance: Future[PrometheusExtension] = {
    kamonInstancePromise.future
  }

  override def createExtension(system: ExtendedActorSystem): PrometheusExtension = {
    system.name match {
      case "kamon" ⇒
        try {
          val extension = new PrometheusExtension(system)
          kamonInstancePromise.success(extension)
          extension
        } catch {
          case NonFatal(e) ⇒
            kamonInstancePromise.failure(e)
            throw e
        }
      case other ⇒
        val log = Logging(system, this.getClass)
        log.warning("Creating a new Prometheus extension for the actor " +
          s"system $other, maybe you should just use " +
          s"Prometheus.awaitKamonInstance or Prometheus.kamonInstance?")
        new PrometheusExtension(system)
    }
  }

  override def lookup(): ExtensionId[_ <: Extension] = Prometheus
}

/** A Kamon module that collects metrics from Kamon and stores them in a Prometheus-friendly format.
  *
  * @param system the Actor system to which this class acts as an extension
  *
  * @author Daniel Solano Gómez
  */
class PrometheusExtension(system: ExtendedActorSystem) extends Kamon.Extension {
  /** Handy log reference. */
  private val log = Logging(system, classOf[PrometheusExtension])
  /** Expose the extension’s settings. */
  val settings: PrometheusSettings = new PrometheusSettings(system.settings.config)

  // ensure that the refresh interval is not less than the tick interval
  if (settings.flushInterval < Kamon.metrics.settings.tickInterval) {
    val msg = s"The Prometheus flush interval (${settings.flushInterval.toCoarsest}) must be equal to or " +
      s"greater than the Kamon tick interval (${Kamon.metrics.settings.tickInterval.toCoarsest})"
    throw new ConfigurationException(msg)
  }

  /** Returns true if the results from the extension need to be buffered because the refresh less frequently than the
    * tick interval.
    */
  val isBuffered: Boolean = settings.flushInterval > Kamon.metrics.settings.tickInterval

  /** Listens to and records metrics. */
  val ref = {
    val converter = new SnapshotConverter(settings)
    system.actorOf(PrometheusListener.props(converter), "prometheus-listener")
  }

  /** If the listener needs to listen less frequently than ticks, set up a buffer. */
  private[prometheus] val buffer = {
    if (isBuffered) {
      system.actorOf(TickMetricSnapshotBuffer.props(settings.flushInterval, ref), "prometheus-buffer")
    } else {
      ref
    }
  }

  log.info("Starting the Kamon Prometheus module")
  settings.subscriptions.foreach {case (category, selections) ⇒
    selections.foreach { selection ⇒
      Kamon.metrics.subscribe(category, selection, buffer, permanently = true)
    }
  }
}

object PrometheusExtension {

  sealed trait Command

  /** The [[Command]] to request the current snapshot.  The extension will
    * reply to the sender with a [[SnapshotMessage]].
    */
  case object GetCurrentSnapshot extends Command

  /** The [[Command]] to subscribe to snapshot notifications from the extension.
    * Until an [[Unsubscribe]] is sent from the same sender (or the sender dies),
    * the extension will send [[Snapshot]] messages each time a new tick arrives.
    */
  case object Subscribe extends Command

  /** Notifies the module that the sender no longer wishes to get notifications
    * of new snapshots.
    */
  case object Unsubscribe extends Command

  /** Parent type for all messages indicating information about the current snapshot. */
  sealed trait SnapshotMessage

  /** Used as a reply to [[GetCurrentSnapshot]] indicate that there is no current snapshot. */
  case object NoCurrentSnapshot extends SnapshotMessage

  /** Contains a metrics snapshot, which may occur either as a reply to [[GetCurrentSnapshot]] or as a message
    * to a subscriber indicating there is a new snapshot.
    */
  case class Snapshot(snapshot: Seq[PrometheusMetricFamily]) extends SnapshotMessage
}
