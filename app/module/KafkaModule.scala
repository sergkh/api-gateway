package module

import com.google.inject.AbstractModule
import events.EventsStream
import net.codingwell.scalaguice.ScalaModule
import play.api.{Configuration, Environment}
import services.kafka.{AkkaEventPublisher, EventsProcessor}

class KafkaModule(environment: Environment, conf: Configuration) extends AbstractModule with ScalaModule {
  override def configure() {
    bind[EventsStream].to[AkkaEventPublisher]

    if (conf.getOptional[Boolean]("akka.kafka.enabled").getOrElse(false)) {
      bind[EventsProcessor].asEagerSingleton()
    }
  }
}
