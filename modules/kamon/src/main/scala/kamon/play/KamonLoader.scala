package kamon.play

import javax.inject.Inject

import akka.actor.ActorSystem
import kamon.Kamon
import play.api.inject.ApplicationLifecycle
import play.api.{Configuration, Logger}

import scala.concurrent.Future

/**
  *
  * @author Yaroslav Derman <yaroslav.derman@gmail.com>
  *         created on 27/06/17
  */
class KamonLoader @Inject() (lifecycle: ApplicationLifecycle, configuration: Configuration, system: ActorSystem) {
  Logger(classOf[KamonLoader]).debug("Starting Kamon.")

  Kamon.start(configuration.underlying)

  //val subscriber = system.actorOf(Props[TracePrinter], "kamon-log-reporter")
  //Kamon.metrics.subscribe("play-request", "**", subscriber, permanently = true)

  lifecycle.addStopHook { () ⇒
    Future.successful(Kamon.shutdown())
  }
}