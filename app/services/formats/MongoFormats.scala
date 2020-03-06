package services.formats

import reactivemongo.bson._
import models._

object MongoFormats {
  implicit val userReader: BSONDocumentReader[User] = Macros.reader[User]
  implicit val userWriter: BSONDocumentWriter[User] = Macros.writer[User]

  implicit val rolePermissionsReader: BSONDocumentReader[RolePermissions] = Macros.reader[RolePermissions]
  implicit val rolePermissionsWriter: BSONDocumentWriter[RolePermissions] = Macros.writer[RolePermissions]


  @inline
  def byField[T](name: String, v: String): BSONDocument = BSONDocument(name -> v)
}