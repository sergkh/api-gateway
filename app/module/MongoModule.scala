package module

import com.google.inject.AbstractModule
import events.EventsStream
import net.codingwell.scalaguice.ScalaModule
import services.{AkkaEventPublisher, MongoApi, MongoApiImpl, RoutingService}

class MongoModule extends AbstractModule with ScalaModule {

  override def configure(): Unit = {
    bind[MongoApi].to[MongoApiImpl].asEagerSingleton()
  }
  
}
