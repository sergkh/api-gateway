package service.fakes

import zio.Task
import akka.actor.{Actor, ActorSystem, Props}
import events._
import javax.inject.Inject

import scala.concurrent.{Future, Promise}
import scala.reflect.ClassTag

class TestEventsStream @Inject ()(implicit as: ActorSystem) extends EventsStream {
  override def publish(evt: Event): Task[Unit] = Task {
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


object EventBusHelpers {

  def catchEvent[T](implicit system: ActorSystem, tag: ClassTag[T]): Future[T] = {

    val promise = Promise[T]()

    val subscriber = system.actorOf(Props { new Actor() {
        override def receive = {
          case t: T => promise.trySuccess(t)
        }
      }
    })

    system.eventStream.subscribe(subscriber, tag.runtimeClass)

    promise.future
  }
}