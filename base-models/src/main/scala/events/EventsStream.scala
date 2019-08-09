package events

import scala.concurrent.Future
import scala.reflect.ClassTag

trait EventsStream {
  def publish(evt: AppEvent): Future[Unit]
  def subscribe[T](subscriber: T => Unit)(implicit classEv: ClassTag[T])
}
