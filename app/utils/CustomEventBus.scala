package utils

import akka.actor.ActorSystem
import com.mohiva.play.silhouette.api.EventBus
import javax.inject.Inject

/**
  *
  * @author Yaroslav Derman <yaroslav.derman@gmail.com>
  *         created on 25/04/17
  */
class CustomEventBus @Inject()(system: ActorSystem) extends EventBus {

  private val eventStream = system.eventStream

  override protected def publish(event: Event, subscriber: Subscriber): Unit = {
    eventStream.publish(event, subscriber)
  }

  /**
    * Publishes the specified Event to this bus
    */
  override def publish(event: Event): Unit = {
    eventStream.publish(event)
  }

  override def unsubscribe(subscriber: Subscriber, from: Classifier): Boolean = {
    eventStream.unsubscribe(subscriber, from)
  }

  /**
    * Attempts to deregister the subscriber from all Classifiers it may be subscribed to
    */
  override def unsubscribe(subscriber: Subscriber): Unit = {
    eventStream.unsubscribe(subscriber)
  }

  override def subscribe(subscriber: Subscriber, to: Classifier): Boolean = {
    eventStream.subscribe(subscriber, to)
  }

}