package models

import play.api.libs.json.Json
import org.mongodb.scala.bson.annotations.BsonProperty

case class RolePermissions(@BsonProperty("_id") role: String, permissions: List[String]) {
  val roleStr = role.toString
  val permissionsArr = permissions.map(_.toUpperCase).toArray
}

object RolePermissions {
  final val Collection = "roles"
  implicit val rolesJsonFormat = Json.format[RolePermissions]
}
