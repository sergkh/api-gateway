package services.formats

import java.time.{Instant, LocalDateTime, ZoneOffset}

import models._
import reactivemongo.bson._
import com.mohiva.play.silhouette.api.util.PasswordInfo

object MongoFormats {
  implicit object LocalDateTimeWriter extends BSONWriter[LocalDateTime, BSONDateTime] {
    def write(dt: LocalDateTime) : BSONDateTime = BSONDateTime(dt.toInstant(ZoneOffset.UTC).getEpochSecond)
  }

  implicit object LocalDateTimeReader extends BSONReader[BSONDateTime, LocalDateTime] {
    def read(dt:BSONDateTime): LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(dt.value), ZoneOffset.UTC)
  }

  implicit val passwordInfoReader: BSONReader[BSONString, PasswordInfo] = BSONReader[BSONString, PasswordInfo] { str =>
    str.value.split("$") match {
      case Array(hasher, pass, salt) => PasswordInfo(hasher, pass, Some(salt))
      case Array(hasher, pass) => PasswordInfo(hasher, pass, None)
    }
  }

  implicit val passwordInfoWriter: BSONWriter[PasswordInfo, BSONString] = BSONWriter[PasswordInfo, BSONString] { info =>
    BSONString(info.hasher + "$" + info.password + info.salt.map(salt => "$" + salt).getOrElse(""))    
  }

  implicit val userBsonReader: BSONDocumentReader[User] = BSONDocumentReader[User] { doc =>
    User(
      email = doc.getAs[String]("email"),
      phone = doc.getAs[String]("phone"),
      password = doc.getAs[PasswordInfo]("password"),
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

  implicit val refreshTokenFormat = Macros.handler[RefreshToken]
  implicit val sessionFormat = Macros.handler[Session]

  implicit val clientAppFormat = Macros.handler[ClientApp]

  implicit val branchFormat = Macros.handler[Branch]

  @inline def byField(name: String, v: String): BSONDocument = BSONDocument(name -> v)
  @inline def byId[T](v: String): BSONDocument = BSONDocument("_id" -> v)

  implicit class AnyToBSON[T](val v: T) extends AnyVal {
    def toBson(implicit w: BSONDocumentWriter[T]): BSONDocument = w.write(v)
  }
}