package models

import play.api.libs.functional.syntax.unlift
import play.api.libs.json.{JsPath, OFormat}
import play.api.libs.functional.syntax._

case class Restriction(name: String,
                       createdBy: Long,
                       description: Option[String] = None,
                       pattern: String)

object Restriction {

  implicit val mongoFormat: OFormat[Restriction] = (
      (JsPath \ "_id").format[String] and
      (JsPath \ "createdBy").format[Long] and
      (JsPath \ "description").formatNullable[String] and
      (JsPath \ "pattern").format[String]
    )(Restriction.apply, unlift(Restriction.unapply))

}