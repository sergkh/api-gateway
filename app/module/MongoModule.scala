package module

import com.google.inject.AbstractModule
import events.EventsStream
import net.codingwell.scalaguice.ScalaModule
import services.RoutingService
import services.kafka.AkkaEventPublisher
import services.MongoApi
import services.MongoApiImpl

class MongoModule extends AbstractModule with ScalaModule {

  override def configure(): Unit = {
    bind[MongoApi].to[MongoApiImpl].asEagerSingleton()
  }
  
}
