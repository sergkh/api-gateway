package services

import events.EventsStream
import javax.inject.Inject
import events._
import models.Session
import org.mongodb.scala.model.Filters._
import org.mongodb.scala.model.Updates
import play.api.libs.json.{Json, OFormat}
import play.api.mvc.RequestHeader
import services.MongoApi._
import zio._

import scala.compat.Platform
import scala.concurrent.ExecutionContext

/**
  * @author Yaroslav Derman <yaroslav.derman@gmail.com>
  */
trait SessionsService {

  def retrieve(id: String): Task[Option[Session]]

  def store(session: Session): Task[Unit]

  def updateExpiration(id: String, expiredAt: Long): Task[Unit]

  def finish(id: String): Task[Unit]

}

class MongoSessionsService @Inject()(mongoApi: MongoApi) extends SessionsService {

  val col = mongoApi.collection[Session]("sessions")

  def retrieve(id: String): Task[Option[Session]] = col.find(equal("_id", id)).first.toOptionTask

  def store(session: Session): Task[Unit] = col.insertOne(session).toUnitTask

  def updateExpiration(id: String, expiredAt: Long): Task[Unit] =
    col.updateOne(equal("_id", id), Updates.set("expiredAt", expiredAt)).toUnitTask

  def finish(id: String): Task[Unit] = updateExpiration(id, Platform.currentTime)

}

trait SessionEventProcessor

case class EventBusSessionEventProcessor @Inject() (sessionsService: SessionsService, eventsStream: EventsStream) extends SessionEventProcessor {
  val HTTP_IP_HEADERS = Seq(
    "X-Forwarded-For", "X-Real-IP", "Proxy-Client-IP", "HTTP_CLIENT_IP", "HTTP_X_FORWARDED_FOR", "WL-Proxy-Client-IP"
  )
  
  //TODO:  
  // eventsStream.subscribe[Login](login => sessionsService.store(
  //   Session(login.sessionId, login.user.id, login.expirationTime, login.request.userAgent, login.request.ip)
  // ))

  // eventsStream.subscribe[Logout](logout => sessionsService.finish(logout.sessionId))

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