package events

import java.util.UUID

/**
  * @author Yaroslav Derman <yaroslav.derman@gmail.com>
  */
trait AppEvent {
  def id: String = UUID.randomUUID().toString.replace("-", "")
  def external: Boolean = false
}

/**
  * Indicates that event obtained from external server (by Kafka).
  */
trait ExternalEvent extends AppEvent {
  override def external = true
}

class BaseAppEvent(val name: String, val key: String) extends AppEvent