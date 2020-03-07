package forms

import models.RolePermissions
import play.api.data.Forms._
import play.api.data._

object UserPermissionForm {

  val createForm = Form(
    mapping("role" -> text, "permissions" -> list(text))(RolePermissions.apply)(RolePermissions.unapply)
  )

  val updateForm = Form(single("permissions" -> list(text)))

}