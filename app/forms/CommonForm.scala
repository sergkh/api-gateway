package forms

import forms.FormConstraints.{limit, offset}
import play.api.data.Form
import play.api.data.Forms.{mapping, optional}
import utils.Settings

object CommonForm {
  val paginated = Form[Page](
    mapping(
      "limit" -> optional(limit),
      "offset" -> optional(offset)
    )(Page.apply)(Page.unapply)
  )

  case class Page(private val l: Option[Int], private val o: Option[Int]) {
    val limit = l.getOrElse(Settings.DEFAULT_LIMIT)
    val offset = o.getOrElse(Settings.DEFAULT_OFFSET)
  }
}
