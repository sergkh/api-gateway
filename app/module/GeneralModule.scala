package module

import com.google.inject.AbstractModule
import events.EventsStream
import net.codingwell.scalaguice.ScalaModule
import services.{ZioEventPublisher, ServicesManager}


/**
  * @author Yaroslav Derman <yaroslav.derman@gmail.com>
  */
class GeneralModule extends AbstractModule with ScalaModule {

  override def configure(): Unit = {
    bind[ServicesManager].asEagerSingleton()
    bind[EventsStream].to[ZioEventPublisher].asEagerSingleton()
  }
}
