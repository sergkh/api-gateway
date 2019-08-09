package forms

import models.{ApiTemplate, ApiTemplateFilter}
import play.api.data.Form
import play.api.data.Forms._

object ApiTemplateForm {

  def newApi(fixedName: Option[String] = None) = Form(
    mapping(
      "name" -> fixedName.map(ignored).getOrElse(nonEmptyText),
      "paths" -> mapping(
        "include" -> optional(seq(text)),
        "exclude" -> optional(seq(text))
      )(ApiTemplateFilter.apply)(ApiTemplateFilter.unapply),
      "tags" -> mapping(
        "include" -> optional(seq(text)),
        "exclude" -> optional(seq(text))
      ) (ApiTemplateFilter.apply)(ApiTemplateFilter.unapply)
    )(ApiTemplate.apply)(ApiTemplate.unapply)
  )
}