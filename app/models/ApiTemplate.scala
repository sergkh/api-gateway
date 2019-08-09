package models

import play.api.libs.json._
import play.api.libs.functional.syntax._

/**
  * @author Vasyl Zalizetskyi
  */
case class ApiTemplateFilter(include: Option[Seq[String]] = Some(Seq(".*")), exclude: Option[Seq[String]] = None) {
  def matches(str: String): Boolean = {
    include.fold(true)(_.exists(i => str.matches(i))) && !exclude.fold(false)(_.exists(i => str.matches(i)))
  }
}

object ApiTemplateFilter {
  implicit val format: OFormat[ApiTemplateFilter] = Json.format[ApiTemplateFilter]
}

case class ApiTemplate(name: String, paths: ApiTemplateFilter = ApiTemplateFilter(), tags: ApiTemplateFilter = ApiTemplateFilter())

object ApiTemplate {
  implicit val format: OFormat[ApiTemplate] = (
    (__ \ "_id").format[String] and
    (__ \ "paths").formatWithDefault[ApiTemplateFilter](ApiTemplateFilter()) and
    (__ \ "tags").formatWithDefault[ApiTemplateFilter](ApiTemplateFilter())
    )(ApiTemplate.apply, unlift(ApiTemplate.unapply))
}