package forms

import forms.FormConstraints._
import play.api.data.Form
import play.api.data.Forms._
import utils.Settings

object ClientAppForm {

  val create = Form[CreateApps](
    mapping(
      "name" -> name,
      "description" -> description,
      "logo" -> url,
      "url" -> url,
      "redirectUrlPatterns" -> list(url)
    )(CreateApps.apply)(CreateApps.unapply)
  )

  val update = Form[UpdateApps](
    mapping(
      "name" -> optional(name),
      "description" -> optional(description),
      "logo" -> optional(url),
      "url" -> optional(url),
      "redirectUrlPatterns" -> optional(list(url))
    )(UpdateApps.apply)(UpdateApps.unapply)
  )

  val code = Form(single("code" -> FormConstraints.code))

  case class CreateApps(name: String,
                        description: String,
                        logo: String,
                        url: String,
                        redirectUrlPattern: List[String])

  case class UpdateApps(name: Option[String],
                        description: Option[String],
                        logo: Option[String],
                        url: Option[String],
                        redirectUrlPattern: Option[List[String]])
}
