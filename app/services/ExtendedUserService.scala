package services

import com.google.inject.Inject
import models.{ExtendedUser, User}
import models.ExtendedUser._
import play.api.libs.json.{JsNumber, JsObject, Json}
import play.modules.reactivemongo.ReactiveMongoApi
import reactivemongo.api.{Cursor, QueryOpts, ReadPreference}
import reactivemongo.play.json._
import reactivemongo.play.json.collection.JSONCollection

import scala.concurrent.{ExecutionContext, Future}

/**
  * @author Yaroslav Derman <yaroslav.derman@gmail.com>.
  *         created on 08.02.2017.
  */
class ExtendedUserService @Inject()(mongoApi: ReactiveMongoApi)(implicit ctx: ExecutionContext) extends ExtendedUserInfoService {

  private def db = mongoApi.database

  private def coll = db.map(_.collection[JSONCollection](User.EXTENDED_COLLECTION_NAME))

  //TODO: recover mongo db exception
  override def create(info: JsObject): Future[JsObject] = {
    coll.flatMap(_.insert.one(info).map(_ => info))
  }

  override def create(info: ExtendedUser): Future[ExtendedUser] = {
    coll.flatMap(_.insert.one(info).map(_ => info))
  }

  def retrieve4user(userId: String): Future[Option[JsObject]] = {
    coll.flatMap(_.find(Json.obj("_id" -> userId)).one[JsObject])
  }

  def retrieve(selector: JsObject, projectionKeys: String*): Future[Option[JsObject]] = {
    val projection = if (projectionKeys.isEmpty) {
      JsObject(Nil)
    } else {
      JsObject(projectionKeys.map(_ -> JsNumber(1)))
    }

    coll.flatMap(_.find(selector, projection).one[JsObject])
  }

  def retrieveList(selector: JsObject, limit: Int, offset: Int, projectionKeys: String*): Future[List[JsObject]] = {
    val opts = QueryOpts(skipN = offset)
    coll.flatMap(_.find(selector).options(opts).cursor[JsObject](ReadPreference.secondaryPreferred).collect[List](limit, Cursor.ContOnError[List[JsObject]]()))
  }

  override def update(selector: JsObject, updateObj: JsObject): Future[Option[JsObject]] = {
    findAndUpdate(selector, updateObj)
  }

  override def delete(selector: JsObject): Future[Option[JsObject]] = {
    coll.flatMap(_.findAndRemove(selector).map(_.result[JsObject]))
  }

  override def count(selector: JsObject): Future[Int] = coll.flatMap(_.count(Some(selector)))

  private def findAndUpdate(selector: JsObject, update: JsObject) = {
    coll.flatMap(_.findAndUpdate(selector, update, true).map(_.result[JsObject]))
  }

}
