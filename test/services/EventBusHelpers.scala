package services

import zio._
import zio.stream._
import akka.actor.{Actor, ActorSystem, Props}
import events._
import javax.inject.Inject
import utils.TaskExt._
import scala.concurrent.{Future, Promise}
import scala.reflect.ClassTag
import scala.concurrent.Await

object EventBusHelpers {

  def expectEvent[T](events: EventsStream)(implicit tag: ClassTag[T]): Future[T] = {

    val promise = Promise[T]()

    events.subscribe{
      case e: T => UIO(promise.success(e))
      case _ => UIO.unit
    }.unsafeRun

    promise.future
  }
}