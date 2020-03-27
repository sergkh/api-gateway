package service.fakes

import akka.actor.{Actor, ActorSystem, Props}
import events.{AppEvent, EventsStream}
import javax.inject.Inject

import scala.concurrent.Future
import scala.reflect.ClassTag

class TestEventsStream @Inject ()(implicit as: ActorSystem) extends EventsStream {
  override def publish(evt: AppEvent): Future[Unit] = {
    as.eventStream.publish(evt)
    Future.unit
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
