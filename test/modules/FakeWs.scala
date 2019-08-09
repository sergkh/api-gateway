package modules

import net.codingwell.scalaguice.ScalaModule
import play.api.libs.ws.WSClient


case class FakeWs(ws: WSClient) extends ScalaModule {

  override def configure() {
    bind[WSClient].toInstance(ws)
  }

}
