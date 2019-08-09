package services

import models.ExtendedUser
import play.api.libs.json.JsObject

import scala.concurrent.Future

/**
  * @author Yaroslav Derman <yaroslav.derman@gmail.com>.
  *         created on 08.02.2017.
  */
trait ExtendedUserInfoService {

  def create(info: JsObject): Future[JsObject]

  def create(info: ExtendedUser): Future[ExtendedUser]

  def retrieve4user(userId: Long): Future[Option[JsObject]]

  def retrieve(selector: JsObject, projectionKeys: String*): Future[Option[JsObject]]

  def retrieveList(selector: JsObject, limit: Int, offset: Int, projectionKeys: String*): Future[List[JsObject]]

  def count(selector: JsObject): Future[Int]

  def update(selector: JsObject, updateObj: JsObject): Future[Option[JsObject]]

  def delete(selector: JsObject): Future[Option[JsObject]]
}
