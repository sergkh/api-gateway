package module

import com.google.inject.AbstractModule
import net.codingwell.scalaguice.ScalaModule
import play.api.libs.concurrent.AkkaGuiceSupport
import play.api.{Configuration, Environment}
import services.RoutingService

/**
  * @author Yaroslav Derman <yaroslav.derman@gmail.com>
  */
class GeneralModule extends AbstractModule with ScalaModule {

  override def configure(): Unit = {
    bind[RoutingService].asEagerSingleton()
  }
}
