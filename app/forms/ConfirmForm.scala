package forms

import play.api.data.Form
import play.api.data.Forms._
import utils.Settings._
import FormConstraints._

/**
  * The form which handles the sign up process.
  */
object ConfirmForm {

  val confirm = Form(
    mapping(
      "login" -> login,
      "code" -> code
    )(ConfirmData.apply)(ConfirmData.unapply)
  )

  val regenerateCode = Form(single("login" -> login))

  case class ConfirmData(login: String, code: String)
}
