package module

import com.google.inject.AbstractModule
import net.codingwell.scalaguice.ScalaModule
import services.{DefaultInitializationService, InitializationService}

class InitializationModule extends AbstractModule with ScalaModule {

  override def configure(): Unit = {
    bind[InitializationService].to[DefaultInitializationService].asEagerSingleton()
  }

}
