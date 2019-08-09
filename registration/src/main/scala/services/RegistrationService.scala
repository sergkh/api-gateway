package services

import com.impactua.bouncer.commons.utils.Logging
import models.{RegistrationData, User}
import play.api.mvc.{Request, RequestHeader}

import scala.concurrent.Future

/**
  * @author Yaroslav Derman <yaroslav.derman@gmail.com>.
  *         created on 04.08.2016.
  */
trait RegistrationService extends Logging {

  def userRegistrationRequest(req: Request[_]): Future[RegistrationData]

  def getUserByLogin(login: String): Future[Option[User]]

  def confirmUserRegistrationRequest(login: String)(implicit requestHeader: RequestHeader): Future[User]

}
