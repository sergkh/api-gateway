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
import reactivemongo.api.collections.bson.BSONCollection
import services.formats.MongoFormats._
import reactivemongo.bson.BSONDocument

class UsersRolePermissionsService @Inject()(reactiveMongoApi: ReactiveMongoApi)(implicit ex: ExecutionContext) extends Logging {

  private def collection = reactiveMongoApi.database.map(_.collection[BSONCollection](RolePermissions.Collection))

  def save(rolePermission: RolePermissions): Future[RolePermissions] = {
    collection
      .flatMap(_.insert(rolePermission))
      .map(_ => rolePermission)
      .recover(processError)
  }

  def get(role: String): Future[Option[RolePermissions]] = {
    val criteria = BSONDocument("_id" -> role.toUpperCase)
    collection.flatMap(_.find(criteria).one[RolePermissions])
  }

  def update(rolePermission: RolePermissions): Future[UpdateWriteResult] = {
    val criteria = BSONDocument("_id" -> rolePermission.role.toUpperCase)
    val updObj = BSONDocument("$set" -> Json.obj("permissions" -> rolePermission.permissions))
    collection.flatMap(_.update(criteria, updObj))
      .recover(processError)
  }

  def remove(role: String): Future[Option[RolePermissions]] = {
    collection.flatMap(_.findAndRemove(BSONDocument("_id" -> role)).map(res => res.result[RolePermissions])
    )
  }

  def getAvailableRoles: Future[Seq[String]] = {
    collection.flatMap(
      _.find(BSONDocument.empty, Json.obj("_id" -> 1))
        .cursor[BSONDocument](ReadPreference.secondaryPreferred)
        .collect[List](-1, errorHandler[BSONDocument])
        .map { jsList => jsList.map(bson => bson.getAsTry[String]("_id").get) }
    )
  }

  private def errorHandler[T] = Cursor.FailOnError[List[T]]()
}
