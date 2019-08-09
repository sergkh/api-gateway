package forms

import play.api.data.Form
import play.api.data.Forms._
import utils.Settings.{TAG_DESCRIPTION, TAG_NAME}

object RestrictionForm {

  val createRestriction = Form(
    mapping(
      TAG_NAME -> text(3, 1024),
      TAG_DESCRIPTION -> optional(text(3, 4096)),
      "pattern" -> nonEmptyText,
    )(CreateRestriction.apply)(CreateRestriction.unapply)
  )

  case class CreateRestriction(name: String, description: Option[String], pattern: String)

}