package forms

import models.RolePermissions
import play.api.data.Forms._
import play.api.data._

/**
  * Created by faiaz on 19.10.16.
  */
object UserPermissionForm {

  val createForm = Form(
    mapping(
      "role" -> text,
      "permissions" -> seq(text)
    )(RolePermissions.apply)(RolePermissions.unapply)
  )

  val updateForm = Form(
    single(
      "permissions" -> seq(text)
    )
  )

}