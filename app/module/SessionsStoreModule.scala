package module

import net.codingwell.scalaguice.ScalaModule
import play.api.{Configuration, Environment}
import services.{EventBusSessionEventProcessor, SessionEventProcessor}

class SessionsStoreModule(environment: Environment, conf: Configuration) extends ScalaModule {
  override def configure() = {
    if (conf.get[Boolean]("session.store")) {
      bind[SessionEventProcessor].to[EventBusSessionEventProcessor].asEagerSingleton()
    }
  }
}
