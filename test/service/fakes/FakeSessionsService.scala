package service.fakes

import models.Session
import services.SessionsService

import scala.concurrent.Future

/**
  *
  * @author Yaroslav Derman <yaroslav.derman@gmail.com>
  *         created on 03/05/17
  */
class FakeSessionsService extends SessionsService {
  override def retrieve(id: String): Future[Option[Session]] = Future.successful(None)

  override def store(session: Session): Future[Session] = Future.successful(session)

  override def updateExpiration(id: String, expiredAt: Long): Future[Unit] = Future.successful({})

  override def finish(id: String): Future[Unit] = Future.successful({})

  override def remove(id: String): Future[Unit] = Future.successful({})
}
