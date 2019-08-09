package models

import com.impactua.bouncer.commons.utils.RandomStringGenerator
import play.api.libs.json._
import play.api.libs.functional.syntax._

case class Branch(name: String,
                  createdBy: Long,
                  description: Option[String] = None,
                  hierarchy: List[String] = Nil,
                  id: String = Branch.nextId) {

  def belongs(otherId: String): Boolean = otherId == id || hierarchy.contains(otherId)
}

object Branch {

  final val ROOT = "root"

  final val BranchIdSize = 6

  // Does this code generate certainly unique IDS? No
  // But they all are in the same database, so we can do retries and save some bytes in names
  // Also we do not expect many too branches to exists
  def nextId: String = RandomStringGenerator.generateId(BranchIdSize)

  implicit val format = Json.format[Branch]

  val mongoFormat: OFormat[Branch] = (
    (JsPath \ "name").format[String] and
    (JsPath \ "createdBy").format[Long] and
    (JsPath \ "description").formatNullable[String] and
    (JsPath \ "hierarchy").format[List[String]] and
    (JsPath \ "_id").format[String]
    )(Branch.apply, unlift(Branch.unapply))

}