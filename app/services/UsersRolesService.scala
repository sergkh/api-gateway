package services

import com.google.inject.Inject
import models.RolePermissions
import org.mongodb.scala.model.Filters._
import org.mongodb.scala.model.Updates
import services.MongoApi._
import utils.Logging
import zio._

class UsersRolesService @Inject()(mongoApi: MongoApi) extends Logging {
  val col = mongoApi.collection[RolePermissions](RolePermissions.Collection)

  def save(rolePermission: RolePermissions): Task[Unit] =
    col.insertOne(rolePermission).toUnitTask

  def get(role: String): Task[Option[RolePermissions]] =
    col.find(equal("_id", role.toUpperCase)).first.toOptionTask

  def get(roles: List[String]): Task[List[RolePermissions]] =
    col.find(in("_id", roles.map(_.toUpperCase):_*)).toTask.map(_.toList)

  def update(role: RolePermissions): Task[Unit] =
    col.updateOne(equal("_id", role.role.toUpperCase), Updates.set("permissions", role.permissions)).toUnitTask

  def remove(role: String): Task[Option[RolePermissions]] =
    col.findOneAndDelete(equal("_id", role.toUpperCase)).toOptionTask
  

  // TODO: we can get only projection of ids here: .projection(Projections.include("_id"))
  def getAvailableRoles: Task[Seq[String]] = col.find().toTask.map(_.map(_.role))
}
