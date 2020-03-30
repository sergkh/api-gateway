package forms

import play.api.data.Form
import play.api.data.Forms._
import FormConstraints._

/** The form which handles the sign up process. */
object RegisterForm {

  val openForm = Form(
    mapping(
      "login" -> nonEmptyText.verifying(or(emailAddress, phoneNumber)),
      "password" -> optional(password)
    )(OpenFormData.apply)(OpenFormData.unapply)
  )

}

case class OpenFormData(login: String, password: Option[String]) extends RegisterOptData

sealed trait RegisterOptData {

  def login: String

  def optEmail: Option[String] = if (login.contains("@")) Some(login) else None

  def optPhone: Option[String] = if (!login.contains("@")) Some(loginFormatted) else None

  def optSocial: Option[String] = if (!login.contains("@") && !login.contains("+") && login.length == 15) Some(login) else None

  // Lowercase for email
  def loginFormatted: String = (if (login.startsWith("\\+")) login.substring(1) else login).toLowerCase
}

