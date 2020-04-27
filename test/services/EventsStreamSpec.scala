package services

import zio._
import org.scalatest.wordspec.AsyncWordSpec
import org.scalatest.matchers.should.Matchers
import events.{Signup, RequestInfo}
import models.User

import utils.TaskExt._
import events.Event
import scala.util.Success


class EventsStreamSpec extends AsyncWordSpec with Matchers {
  val event = Signup(User(), RequestInfo("127.0.0.1", "Test"))

  "A events service" should {

    "return an event added after subscription" in {
      val eventsService = new ZioEventPublisher        
      val promise = scala.concurrent.Promise[Event]()
      eventsService.subscribe { evt => UIO(promise.success(evt))}.unsafeRun
      eventsService.publish(event).unsafeRun
      promise.future.map(_ shouldEqual event)
    }

    "broadcast events" in {
      val eventsService = new ZioEventPublisher        
      val promise = scala.concurrent.Promise[Event]()
      val promise2 = scala.concurrent.Promise[Event]()

      eventsService.subscribe { evt => 
        println("Promise1")
        UIO(promise.success(evt))}.unsafeRun

      eventsService.subscribe { evt => 
        println("Promise2")
        UIO(promise2.success(evt))}.unsafeRun

      eventsService.publish(event).unsafeRun
      
      for {
        e1 <- promise.future
        e2 <- promise2.future
      } yield {
        e1 shouldEqual event
        e2 shouldEqual event
      }
    }
  }
}