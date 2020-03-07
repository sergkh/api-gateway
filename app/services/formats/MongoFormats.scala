package services.formats

import reactivemongo.bson._
import models._

object MongoFormats {
  implicit val userBsonReader: BSONDocumentReader[User] = BSONDocumentReader[User] { doc =>
    User(
      email = doc.getAs[String]("email"),
      phone = doc.getAs[String]("phone"),
      passHash = doc.getAs[String]("passHash").get,
      flags = doc.getAs[List[String]]("flags").getOrElse(Nil),
      roles = doc.getAs[List[String]]("roles").getOrElse(Nil),
      hierarchy = doc.getAs[List[String]]("hierarchy").getOrElse(Nil),
      firstName = doc.getAs[String]("firstName"),
      lastName = doc.getAs[String]("lastName"),
      id = doc.getAs[String]("_id").get,
      version = doc.getAs[Int]("version").get
    )
  }

  implicit val userBsonWriter: BSONDocumentWriter[User] = Macros.writer[User]

  implicit val roleBsonPermissionsReader: BSONDocumentReader[RolePermissions] = Macros.reader[RolePermissions]
  implicit val roleBsonPermissionsWriter: BSONDocumentWriter[RolePermissions] = Macros.writer[RolePermissions]

  @inline
  def byField[T](name: String, v: String): BSONDocument = BSONDocument(name -> v)

  implicit class AnyToBSON[T](val v: T) extends AnyVal {
    def toBson(implicit w: BSONDocumentWriter[T]): BSONDocument = w.write(v)
  }
}