package services

import com.mohiva.play.silhouette.api.services.IdentityService
import models.User
import play.api.libs.json.JsObject

import scala.concurrent.Future

/**
  *
  * @author Yaroslav Derman <yaroslav.derman@gmail.com>
  *         created on 07/02/17
  */
trait UserIdentityService extends IdentityService[User] {

  def save(user: User): Future[User]

  def retrieve(selector: JsObject): Future[Option[JsObject]]

  def updateFlags(user: User): Future[User]

}
