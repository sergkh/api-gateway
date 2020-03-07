package models

import java.security.BasicPermission
import play.api.libs.functional.syntax._
import play.api.libs.json.{JsObject, JsPath, OWrites, Reads}
import utils.JsonHelper
import reactivemongo.bson.Macros.Annotations.Key

case class UserPermission(name: String) extends BasicPermission(name.toUpperCase)

case class UserDbPermission(name: String, id: Int = 0) {
  val upperName = name.toUpperCase
}

case class RolePermissions(@Key("_id") role: String, permissions: List[String]) {
  val roleStr = role.toString
  val permissionsArr = permissions.map(_.toUpperCase).toArray
}

object RolePermissions {
  final val Collection = "role_permissions"

  implicit val reader: Reads[RolePermissions] = (
      (JsPath \ "role").read[String] and
      (JsPath \ "permissions").read[List[String]].orElse(Reads.pure(Nil))
    )(RolePermissions.apply _)

  implicit val writer = new OWrites[RolePermissions] {
    def writes(u: RolePermissions): JsObject = JsonHelper.toNonemptyJson(
      "role" -> u.roleStr,
      "permissions" -> u.permissions
    )
  }
}
