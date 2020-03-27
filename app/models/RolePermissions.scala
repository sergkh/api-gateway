package models

import play.api.libs.json.Json
import reactivemongo.bson.Macros.Annotations.Key

case class RolePermissions(@Key("_id") role: String, permissions: List[String]) {
  val roleStr = role.toString
  val permissionsArr = permissions.map(_.toUpperCase).toArray
}

object RolePermissions {
  final val Collection = "roles"
  implicit val rolesJsonFormat = Json.format[RolePermissions]
}
