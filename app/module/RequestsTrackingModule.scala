package module

import net.codingwell.scalaguice.ScalaModule
import play.api.{Configuration, Environment}
import services.{IdleRequestTracker, RedisRequestTracker, RequestTracker}


class RedisRequestsTrackingModule(environment: Environment, conf: Configuration) extends ScalaModule {
  override def configure() = {
    bind[RequestTracker].to[RedisRequestTracker]
  }
}

class IdleRequestsTrackingModule(environment: Environment, conf: Configuration) extends ScalaModule {
  override def configure() = {
    bind[RequestTracker].to[IdleRequestTracker]
  }
}