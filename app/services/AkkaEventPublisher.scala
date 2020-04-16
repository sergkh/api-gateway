package services

import akka.actor.{Actor, ActorSystem, Props}
import events._
import javax.inject.{Inject, Singleton}
import zio.Task

import scala.reflect.ClassTag

@Singleton
class AkkaEventPublisher @Inject() (implicit as: ActorSystem) extends EventsStream {

  override def publish(evt: AppEvent): Task[Unit] = Task {
    as.eventStream.publish(evt)
  }

  override def subscribe[T](subscriber: T => Unit)(implicit classEv: ClassTag[T]): Unit = {
    val actor = as.actorOf(Props(new SubscriberActor[T](subscriber)))
    as.eventStream.subscribe(actor, classEv.runtimeClass)
  }

  case class SubscriberActor[T](processor: T => Unit) extends Actor {
    override def receive = {
      case t: T => processor(t)
    }
  }
}
