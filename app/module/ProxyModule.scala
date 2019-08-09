package module

import com.google.inject.AbstractModule
import net.codingwell.scalaguice.ScalaModule
import services.{ProxyService, WsProxyService}

/**
  *
  * @author Yaroslav Derman <yaroslav.derman@gmail.com>
  *         created on 20/05/16
  */
class ProxyModule extends AbstractModule with ScalaModule {

  override def configure(): Unit = {
    bind[ProxyService].to[WsProxyService]
  }
}
