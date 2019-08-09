package forms

import play.api.data.Form
import play.api.data.Forms._
import utils.Settings._

/**
  * The form which handles the sign up process.
  */
object ConfirmForm {

  val confirm = Form(
    mapping(
      TAG_LOGIN -> nonEmptyText,
      TAG_CODE -> nonEmptyText
    )(ConfirmData.apply)(ConfirmData.unapply)
  )

  val reConfirm = Form(
    mapping(
      TAG_LOGIN -> nonEmptyText
    )(ReConfirm.apply)(ReConfirm.unapply)
  )

  case class ConfirmData(login: String, code: String)
  case class ReConfirm(login: String)

}
