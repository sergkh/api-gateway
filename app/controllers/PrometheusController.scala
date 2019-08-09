package controllers

//scalastyle:off public.methods.have.type

import javax.inject.{Inject, Singleton}

import akka.actor.{Actor, ActorSystem, Props}
import akka.pattern._
import akka.stream.scaladsl.Source
import akka.util.{ByteString, Timeout}
import com.impactua.bouncer.commons.utils.Logging
import controllers.PrometheusController.MetricsActor
import kamon.prometheus.Prometheus
import kamon.prometheus.PrometheusExtension.{GetCurrentSnapshot, Snapshot, Subscribe}
import kamon.prometheus.metric.{PrometheusMetricFamily, TextFormat}
import play.api.Configuration
import play.api.http.HttpEntity
import play.api.mvc.{Controller, ResponseHeader, Result}
import security.Basic

import scala.concurrent.duration._

/**
  * @author Yaroslav Derman <yaroslav.derman@gmail.com>
  */
@Singleton
// TODO: move into kamon module
class PrometheusController @Inject()(
                                      actorSystem: ActorSystem,
                                      conf: Configuration,
                                      basic: Basic) extends Controller with Logging {

  implicit val ctx = actorSystem.dispatcher

  val prometheusLogin = conf.get[String]("prometheus.login")
  val prometheusPass = conf.get[String]("prometheus.password")

  implicit val timeout = Timeout(1.minute)

  implicit private val metricActor = actorSystem.actorOf(Props[MetricsActor], "prometheus-actor")

  Prometheus.kamonInstance.map { ext ⇒
    ext.ref ! Subscribe
  }

  def metrics = basic.secured(prometheusLogin, prometheusPass).async {
    (metricActor ? GetCurrentSnapshot).mapTo[Snapshot].map {
      case Snapshot(metrics) =>
        val statistic = TextFormat.format(metrics)
        Result(
          ResponseHeader(OK),
          HttpEntity.Streamed(Source.single(ByteString(statistic)), Some(statistic.length), Some("text/plain;version=0.0.4"))
        )
    }
  }

}

object PrometheusController {
  class MetricsActor extends Actor {

    override def preStart(): Unit = {
      super.preStart()
      context.become(snapshot(Nil))
    }

    override def receive: Receive = Actor.emptyBehavior

    def snapshot(currentMetrics: Seq[PrometheusMetricFamily]): Receive = {
      case Snapshot(newMetrics) =>
        context.become(snapshot(currentMetrics ++ newMetrics))
      case GetCurrentSnapshot =>
        sender() ! Snapshot(currentMetrics)
        context.become(snapshot(Nil))
    }
  }
}
