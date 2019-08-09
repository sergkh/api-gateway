package forms

import com.impactua.bouncer.commons.utils.FormConstraints._
import play.api.data.Form
import play.api.data.Forms._
import utils.Settings._

/**
 * The form which handles the submission of the login.
 */
object ResetPasswordForm {

  val form = Form(
    mapping(
      TAG_LOGIN -> nonEmptyText.verifying(or(emailAddress, phoneNumber))
    )(ResetPassword.apply)(ResetPassword.unapply)
  )

  val confirm = Form(
    mapping(
      TAG_LOGIN -> nonEmptyText.verifying(emailAddress, phoneNumber),
      TAG_CODE -> nonEmptyText,
      TAG_PASSWORD -> password
    )(SetNewPassword.apply)(SetNewPassword.unapply)
  )

  case class ResetPassword(login: String)
  case class SetNewPassword(login: String, code: String, password: String)

}

