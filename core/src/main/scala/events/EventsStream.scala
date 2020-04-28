package events

import zio._
import zio.stream.Stream
import scala.reflect.ClassTag

trait EventsStream {
  def publish(evt: Event): Task[Unit]
  def stream: UIO[Stream[Nothing, Event]]
  def subscribe(callback: Event => UIO[Unit]): UIO[Unit]
}