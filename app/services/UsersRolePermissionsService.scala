package services

import com.google.inject.Inject
import models.RolePermissions
import play.api.libs.json._
import play.modules.reactivemongo.ReactiveMongoApi
import reactivemongo.api.commands.UpdateWriteResult
import reactivemongo.play.json._
import reactivemongo.api.{Cursor, ReadPreference}
import reactivemongo.play.json.collection.JSONCollection
import utils.Logging
import utils.MongoErrorHandler._

import scala.concurrent.{ExecutionContext, Future}

class UsersRolePermissionsService @Inject()(reactiveMongoApi: ReactiveMongoApi)(implicit ex: ExecutionContext) extends Logging {

  private def collection = reactiveMongoApi.database.map(_.collection[JSONCollection](RolePermissions.Collection))

  def save(rolePermission: RolePermissions): Future[RolePermissions] = {
    collection
      .flatMap(_.insert(rolePermission))
      .map(_ => rolePermission)
      .recover(processError)
  }

  def get(role: String): Future[Option[RolePermissions]] = {
    val criteria = Json.obj("role" -> role.toUpperCase)
    collection.flatMap(
      _.find(criteria).one[RolePermissions])
  }

  def update(rolePermission: RolePermissions): Future[UpdateWriteResult] = {
    val criteria = Json.obj("role" -> rolePermission.role.toUpperCase)
    val updObj = Json.obj("$set" -> Json.obj("permissions" -> rolePermission.permissions))
    collection.flatMap(_.update(criteria, updObj))
      .recover(processError)
  }

  def remove(role: String): Future[Option[RolePermissions]] = {
    collection.flatMap(
      _.findAndRemove(Json.obj("role" -> role)).map(res => res.result[RolePermissions])
    )
  }

  def getAvailableRoles: Future[Seq[String]] = {
    collection.flatMap(
      _.find(JsObject(Nil), Json.obj("role" -> 1, "_id" -> 0))
        .cursor[JsValue](ReadPreference.secondaryPreferred)
        .collect[List](-1, errorHandler[JsValue])
        .map { jsList => jsList.map(js => (js \ "role").as[String]) }
    )
  }

  private def errorHandler[T] = Cursor.FailOnError[List[T]]()
}
