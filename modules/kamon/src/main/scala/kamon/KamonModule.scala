package kamon

import kamon.play.KamonLoader
import net.codingwell.scalaguice.ScalaModule

/**
  * @author Yaroslav Derman <yaroslav.derman@gmail.com>
  */
class KamonModule extends ScalaModule {

  override def configure(): Unit = {
    bind[KamonLoader].asEagerSingleton()
  }
}
