package services

import com.google.inject.Inject
import models.RolePermissions
import play.modules.reactivemongo.ReactiveMongoApi
import reactivemongo.api.collections.bson.BSONCollection
import reactivemongo.api.{Cursor, ReadPreference}
import reactivemongo.bson.BSONDocument
import services.formats.MongoFormats._
import utils.Logging
import utils.MongoErrorHandler._

import scala.concurrent.{ExecutionContext, Future}

class UsersRolesService @Inject()(reactiveMongoApi: ReactiveMongoApi)(implicit ex: ExecutionContext) extends Logging {

  def save(rolePermission: RolePermissions): Future[RolePermissions] =
    collection
      .flatMap(_.insert.one(rolePermission))
      .map(_ => rolePermission)
      .recover(processError)

  def get(role: String): Future[Option[RolePermissions]] =
    collection.flatMap(_.find(byId(role.toUpperCase), Option.empty[BSONDocument]).one[RolePermissions])

  def get(roles: List[String]): Future[List[RolePermissions]] =
    collection.flatMap(
      _.find(BSONDocument("role" -> BSONDocument("$in" -> roles.map(_.toUpperCase))), Option.empty[BSONDocument])
        .cursor[RolePermissions](ReadPreference.secondaryPreferred)
        .collect[List](-1, errorHandler[RolePermissions])
    )


  def update(rolePermission: RolePermissions): Future[Unit] = {
    val criteria = byId(rolePermission.role.toUpperCase)
    val updObj = BSONDocument("$set" -> BSONDocument("permissions" -> rolePermission.permissions))
    collection.flatMap(_.update.one(criteria, updObj)).recover(processError).map(_ => ())
  }

  def remove(role: String): Future[Option[RolePermissions]] = {
    collection.flatMap(_.findAndRemove(byId(role)).map(_.result[RolePermissions])
    )
  }

  def getAvailableRoles: Future[Seq[String]] = {
    collection.flatMap(
      _.find(BSONDocument.empty, BSONDocument("_id" -> 1))
        .cursor[BSONDocument](ReadPreference.secondaryPreferred)
        .collect[List](-1, errorHandler[BSONDocument])
        .map { jsList => jsList.map(bson => bson.getAsTry[String]("_id").get) }
    )
  }

  private def collection = reactiveMongoApi.database.map(_.collection[BSONCollection](RolePermissions.Collection))
  private def errorHandler[T] = Cursor.FailOnError[List[T]]()
}
