package services

import akka.actor.{Actor, ActorSystem, Props}
import events._
import javax.inject.{Inject, Singleton}
import zio._
import zio.stream._
import utils.TaskExt._


@Singleton
class ZioEventPublisher extends EventsStream {  
  private val streamQueues = Ref.make(List.empty[Queue[Event]]).unsafeRun
  private val subscribers  = Ref.make(List.empty[Event => UIO[Unit]]).unsafeRun
  
  def publish(evt: Event): Task[Unit] = for {
    queues  <- streamQueues.get
    _       <- UIO.collectAll(queues.map(_.offer(evt)))
    sList   <- subscribers.get
    _       <- UIO.collectAll(sList.map(f => f(evt))).forkDaemon
  } yield ()

  def stream: UIO[Stream[Nothing, Event]] = for {
    queue <- Queue.bounded[Event](1000)
    _     <- streamQueues.update(l => queue :: l)
  } yield Stream.fromQueue(queue)

  def subscribe(callback: Event => UIO[Unit]): UIO[Unit] = for {
    _ <- subscribers.update(list => callback :: list)
  } yield ()
}
