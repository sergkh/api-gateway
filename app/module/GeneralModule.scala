package module

import com.google.inject.AbstractModule
import events.EventsStream
import net.codingwell.scalaguice.ScalaModule
import services.{AkkaEventPublisher, RoutingService}


/**
  * @author Yaroslav Derman <yaroslav.derman@gmail.com>
  */
class GeneralModule extends AbstractModule with ScalaModule {

  override def configure(): Unit = {
    bind[RoutingService].asEagerSingleton()
    bind[EventsStream].to[AkkaEventPublisher].asEagerSingleton()
  }
}
