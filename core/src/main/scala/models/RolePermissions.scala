package models

import org.mongodb.scala.bson.annotations.BsonProperty
import play.api.libs.json.Json

case class RolePermissions(@BsonProperty("_id") role: String, permissions: List[String]) {
  val roleStr = role.toString
  val permissionsArr = permissions.map(_.toUpperCase).toArray
}

object RolePermissions {
  final val Collection = "roles"
  implicit val rolesJsonFormat = Json.format[RolePermissions]
}
