package services.kafka

import akka.actor.{ActorLogging, Props}
import akka.stream.actor.ActorSubscriberMessage.{OnComplete, OnError, OnNext}
import akka.stream.actor.{ActorSubscriber, OneByOneRequestStrategy, RequestStrategy}
import models.AppEvent.{RoleDeleted, RoleUpdated}
import models.User.{emailsCacheName, phonesCacheName, socialCacheName, usersCacheName}
import models.{User, UserModificationEvent}
import play.api.cache.{AsyncCacheApi, NamedCache}
import services.auth.SocialAuthService

import scala.concurrent.ExecutionContext.Implicits.global

class EventSubscriberActor(@NamedCache("dynamic-users-cache") usersCache: AsyncCacheApi,
                           @NamedCache("dynamic-emails-cache") emailsCache: AsyncCacheApi,
                           @NamedCache("dynamic-phones-cache") phonesCache: AsyncCacheApi,
                           @NamedCache("dynamic-social-cache") socialCache: AsyncCacheApi,
                           authService: SocialAuthService) extends ActorSubscriber with ActorLogging {

  override protected def requestStrategy: RequestStrategy = OneByOneRequestStrategy

  override def preStart(): Unit = {
    super.preStart()
  }

  override def receive: Receive = {
    case OnNext(Some(event: UserModificationEvent)) =>
      removeFromCaches(event.user)

    case OnNext(Some(_ : RoleUpdated)) | OnNext(Some(_ : RoleDeleted))=>
      clearUserCaches()

    case OnNext(None) => ()

    case OnError(err: Exception) =>
      log.error(err, "EventSubsciberActor receieved exception {}", err)
      context.stop(self)

    case OnComplete =>
      log.error("Receive OnComplete stream event for EventSubsciberActor " + self.path + " from " + sender())
      context.stop(self)

    case other =>
      log.warning("Receive unsupported event " + other + ", stream activity:" + canceled)
  }

  private def clearUserCaches(): Unit = {
    usersCache.removeAll()
  }

  private def removeFromCaches(u: User) = {
    usersCache.remove(u.uuidStr)
    log.info(s"$u was removed from $usersCacheName on the key ${u.uuidStr}")
    for (email <- u.email) {
      emailsCache.remove(email)
      log.info(s"${u.uuidStr} was removed from $emailsCacheName on the key $email")
    }
    for (phone <- u.phone) {
      phonesCache.remove(phone)
      log.info(s"${u.uuidStr} was removed from $phonesCacheName on the key $phone")
    }
    authService.retrieveAllSocialInfo(u.uuid) map { socialIdLst =>
      for (socialId <- socialIdLst) {
        socialCache.remove(socialId)
        log.info(s"${u.uuidStr} was removed from $socialCacheName on the key $socialId")
      }
    }

  }
}

object EventSubscriberActor {

  def props(usersCache: AsyncCacheApi,
            emailsCache: AsyncCacheApi,
            phonesCache: AsyncCacheApi,
            socialCache: AsyncCacheApi,
            authService: SocialAuthService): Props =
    Props(new EventSubscriberActor(usersCache, emailsCache, phonesCache, socialCache, authService))

}
