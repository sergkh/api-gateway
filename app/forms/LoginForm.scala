package forms

import play.api.data.Form
import play.api.data.Forms._
import utils.Settings._

/**
 * The form which handles the submission of the credentials.
 */
object LoginForm {

  val form = Form(
    mapping(
      TAG_LOGIN -> nonEmptyText,
      TAG_PASSWORD -> optional(nonEmptyText)
    )(LoginCredentials.apply)(LoginCredentials.unapply)
  )

  case class LoginCredentials(login: String, password: Option[String]) {
    def loginFormatted: String = {
      if (login.startsWith("\\+")) {
        login.substring(1)
      } else {
        login
      }
    }
  }
}

