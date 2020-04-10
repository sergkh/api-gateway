package events

import zio.Task
import scala.reflect.ClassTag

trait EventsStream {
  def publish(evt: AppEvent): Task[Unit]
  def subscribe[T](subscriber: T => Unit)(implicit classEv: ClassTag[T])
}
