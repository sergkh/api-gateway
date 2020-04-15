package forms

import models.RolePermissions
import play.api.data.Forms._
import play.api.data._
import FormConstraints._

object UserPermissionForm {

  val createForm = Form(
    mapping(
      "role" -> role, 
      "permissions" -> list(permission)
    )(RolePermissions.apply)(RolePermissions.unapply)
  )

  val updateForm = Form(single("permissions" -> list(permission)))
}