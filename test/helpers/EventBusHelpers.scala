package helpers

import akka.actor.{Actor, ActorSystem, Props}
import com.mohiva.play.silhouette.api.EventBus

import scala.concurrent.{Future, Promise}
import scala.reflect.ClassTag

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
