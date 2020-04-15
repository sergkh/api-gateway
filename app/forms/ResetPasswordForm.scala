package forms

import forms.FormConstraints._
import play.api.data.Form
import play.api.data.Forms._

/**
 * The form which handles the submission of the login.
 */
object ResetPasswordForm {

  val initReset = Form(single("login" -> login))

  val confirm = Form(
    mapping(
      "login" -> login,
      "code" -> code,
      "password" -> password
    )(SetNewPassword.apply)(SetNewPassword.unapply)
  )
  
  case class SetNewPassword(login: String, code: String, password: String)
}

