package models

import org.mongodb.scala.bson.annotations.BsonProperty
import play.api.libs.json._
import utils.RandomStringGenerator

case class Branch(name: String,
                  createdBy: String,
                  description: Option[String] = None,
                  hierarchy: List[String] = Nil,
                  @BsonProperty("_id") id: String = Branch.nextId) {

  def belongs(otherId: String): Boolean = otherId == id || hierarchy.contains(otherId)
}

object Branch {

  final val ROOT = "root"

  final val BranchIdSize = 6

  // Does this code generate certainly unique IDS? No
  // But they all are in the same database, so we can do retries and save some bytes in names
  def nextId: String = RandomStringGenerator.generateId(BranchIdSize)

  implicit val format = Json.format[Branch]
}