package forms

import forms.FormConstraints._
import play.api.data.Form
import play.api.data.Forms._
import utils.Settings

object ClientAppForm {

  val create = Form[CreateApps](
    mapping(
      "name" -> nonEmptyText,
      "description" -> nonEmptyText,
      "logo" -> nonEmptyText,
      "url" -> nonEmptyText,
      "contacts" -> list(nonEmptyText),
      "redirectUrlPatterns" -> list(nonEmptyText)
    )(CreateApps.apply)(CreateApps.unapply)
  )

  val update = Form[UpdateApps](
    mapping(
      "enabled" -> optional(boolean),
      "name" -> optional(nonEmptyText),
      "description" -> optional(nonEmptyText),
      "logo" -> optional(nonEmptyText),
      "url" -> optional(nonEmptyText),
      "contacts" -> optional(list(nonEmptyText)),
      "redirectUrlPatterns" -> optional(list(nonEmptyText))
    )(UpdateApps.apply)(UpdateApps.unapply)
  )

  val code = Form(single("code" -> nonEmptyText))

  case class CreateApps(name: String,
                        description: String,
                        logo: String,
                        url: String,
                        contacts: List[String],
                        redirectUrlPattern: List[String])

  case class UpdateApps(enabled: Option[Boolean],
                        name: Option[String],
                        description: Option[String],
                        logo: Option[String],
                        url: Option[String],
                        contacts: Option[List[String]],
                        redirectUrlPattern: Option[List[String]])
}
