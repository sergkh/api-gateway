package services

import events.EventsStream
import javax.inject.Inject
import models.AppEvent._
import models.Session
import play.api.libs.json.{Json, OFormat}
import play.api.mvc.RequestHeader
import play.modules.reactivemongo.ReactiveMongoApi
import reactivemongo.play.json._
import reactivemongo.play.json.collection.JSONCollection
import utils.MongoErrorHandler

import scala.compat.Platform
import scala.concurrent.{ExecutionContext, Future}

/**
  *
  * @author Yaroslav Derman <yaroslav.derman@gmail.com>
  *         created on 17/02/17
  */
trait SessionsService {

  def retrieve(id: String): Future[Option[Session]]

  def store(session: Session): Future[Session]

  def updateExpiration(id: String, expiredAt: Long): Future[Unit]

  def finish(id: String): Future[Unit]

  def remove(id: String): Future[Unit]

}

class MongoSessionsService @Inject()(reactiveMongoApi: ReactiveMongoApi)
                                    (implicit exec: ExecutionContext) extends SessionsService {

  import SessionJson._

  val COLLECTION_NAME = "sessions"

  private def db = reactiveMongoApi.database

  private def sessionCollection = db.map(_.collection[JSONCollection](COLLECTION_NAME))

  def retrieve(id: String): Future[Option[Session]] = {
    val selector = Json.obj("_id" -> id)
    sessionCollection.flatMap(_.find(selector).one[Session])
  }

  def store(session: Session): Future[Session] = {
    sessionCollection.flatMap(_.insert(session).map(_ => session).recover(MongoErrorHandler.processError[Session]))
  }

  def updateExpiration(id: String, expiredAt: Long): Future[Unit] = {
    sessionCollection.flatMap(_.update(
      Json.obj("_id" -> id),
      Json.obj("$set" -> Json.obj("expiredAt" -> expiredAt))
    ).map(_ => ()).recover(MongoErrorHandler.processError[Unit]))
  }

  def finish(id: String): Future[Unit] = {
    sessionCollection.flatMap(_.update(
      Json.obj("_id" -> id),
      Json.obj("$set" -> Json.obj("expiredAt" -> Platform.currentTime))
    ).map(_ => ()).recover(MongoErrorHandler.processError[Unit]))
  }

  def remove(id: String): Future[Unit] = {
    sessionCollection.flatMap(_.remove(
      Json.obj("_id" -> id)
    ).map(_ => ()).recover(MongoErrorHandler.processError[Unit]))
  }

}

trait SessionEventProcessor

case class EventBusSessionEventProcessor @Inject() (sessionsService: SessionsService, eventsStream: EventsStream) extends SessionEventProcessor {
  val HTTP_IP_HEADERS = Seq(
    "X-Forwarded-For", "X-Real-IP", "Proxy-Client-IP", "HTTP_CLIENT_IP", "HTTP_X_FORWARDED_FOR", "WL-Proxy-Client-IP"
  )

  eventsStream.subscribe[Login](login => sessionsService.store(
    Session(login.sessionId, login.userId.toLong, login.expirationTime, login.token, clientAgent(login.request), clientIp(login.request))
  ))

  eventsStream.subscribe[Logout](logout => sessionsService.finish(logout.sessionId))

  def clientIp(r: RequestHeader): String = {
    val header = HTTP_IP_HEADERS.find(name => r.headers.get(name).exists(h => h.nonEmpty && !h.equalsIgnoreCase("unknown")))

    header match {
      case Some(name) =>
        val header = r.headers(name)
        if (header.contains(",")) header.split(",").head else header
      case None       => r.remoteAddress
    }
  }

  def clientAgent(r: RequestHeader): String = r.headers.get("User-Agent").getOrElse("not set")
}

object SessionJson {
  implicit val sessionFmt: OFormat[Session] = Json.format[Session]
}