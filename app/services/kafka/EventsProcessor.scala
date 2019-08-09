package services.kafka

import akka.actor.ActorSystem
import com.google.inject.Inject
import com.impactua.bouncer.commons.utils.Logging
import com.impactua.kafka.consumer.RestartableConsumer
import com.impactua.kafka.producer.RestartableProducer
import com.typesafe.config.Config
import events.BaseAppEvent
import models.AppEvent.OAuthAppEvent
import play.api.Configuration
import play.api.cache.{AsyncCacheApi, CacheApi, NamedCache}
import services.auth.SocialAuthService

import scala.concurrent.ExecutionContext

/**
  * Class used for logging user events into kafka and back.
  *
  * @author Sergey Khruschak <sergey.khruschak@gmail.com>
  * @author Yaroslav Derman <yaroslav.derman@gmail.com>
  */
class EventsProcessor @Inject()(conf: Configuration,
                                authService: SocialAuthService,
                                @NamedCache("dynamic-users-cache") usersCache: AsyncCacheApi,
                                @NamedCache("dynamic-emails-cache") emailsCache: AsyncCacheApi,
                                @NamedCache("dynamic-phones-cache") phonesCache: AsyncCacheApi,
                                @NamedCache("dynamic-social-cache") socialCache: AsyncCacheApi
                               )(implicit ctx: ExecutionContext,
                                          actorSystem: ActorSystem) extends Logging {

  implicit val eventsWriter = new EventWriter[BaseAppEvent]()
  implicit val oauthEventsWriter = new EventWriter[OAuthAppEvent]()

  implicit val eventsReader = new EventReader()

  conf.getOptional[Configuration]("akka.kafka") match {
    case Some(kafka) =>
      val usersTopic = kafka.getOptional[String]("producer.usersTopic").getOrElse("users")
      val oauthAppsTopic = kafka.getOptional[String]("producer.oAuthAppsTopic").getOrElse("oauth_apps")

      val producer = new RestartableProducer(actorSystem)

      producer.create[BaseAppEvent](usersTopic, ! _.external)
      producer.create[OAuthAppEvent](oauthAppsTopic, ! _.external)

      val consumer = new RestartableConsumer(actorSystem)

      consumer.create(
        kafka.get[Config]("consumer.usersTopic"),
        EventSubscriberActor.props(usersCache, emailsCache, phonesCache, socialCache, authService)
      )

    case None =>
      log.warn("Kafka settings key under `akka.kafka` is not found. Kafka publishing and consuming will be disabled")
  }

}
