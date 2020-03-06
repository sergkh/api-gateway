package models

import java.util.Date

import com.mohiva.play.silhouette.api.Identity
import com.mohiva.play.silhouette.impl.providers.{OAuth1Info, OAuth2Info}
import play.api.libs.functional.syntax._
import play.api.libs.json._
import utils.UuidGenerator
import utils.StringHelpers._

/**
  *
  * @author Yaroslav Derman <yaroslav.derman@gmail.com>
  *         created on 03/02/17
  */
case class User(email: Option[String] = None,
                phone: Option[String] = None,
                passHash: String,
                flags: Seq[String] = Nil,
                roles: Seq[String] = Nil,
                permissions: Seq[String] = Nil,
                hierarchy: Seq[String] = Nil,
                firstName: Option[String] = None,
                lastName: Option[String] = None,
                id: String = UuidGenerator.generateId,
                version: Int = 0) extends Identity {

  def fullName: Option[String] = {
    for {
      first <- firstName
      last <- lastName
    } yield first + " " + last
  }

  val uuidStr = id

  def identifier: String = email getOrElse (phone getOrElse id).toString

  def checkId(anyId: String): Boolean = email.contains(anyId) || phone.contains(anyId) || id == anyId

  def hasRole(r: String): Boolean = roles.contains(r)

  def hasAnyRole(r: String*): Boolean = roles.exists(hasRole)

  def hasPermission(p: String): Boolean = permissions.contains(p)

  def hasAllPermission(p: String*): Boolean = p.forall(hasPermission)

  def hasAnyPermission(p: String*): Boolean = p.exists(hasPermission)

  def withFlags(addFlags: String*): User = copy(flags = (flags ++ addFlags).distinct)

  def withoutFlags(removeFlags: String*): User = copy(flags = flags diff removeFlags)

  def hasFlag(flag: String): Boolean = flags.contains(flag)

  def branch: Option[String] = hierarchy.headOption

  override def toString =
    s"u:$id;e:${email.getOrElse("")};p:${phone.getOrElse("")};n:$fullName;fl:${flags.mkString(",")};br:${hierarchy.mkString(",")}"

}

object User {

  implicit val oAuth1InfoFmt = Json.format[OAuth1Info]
  implicit val oAuth2InfoFmt = Json.format[OAuth2Info]

  val usersCacheName = "dynamic-users-cache"
  val emailsCacheName = "dynamic-emails-cache"
  val phonesCacheName = "dynamic-phones-cache"
  val socialCacheName = "dynamic-social-cache"

  val COLLECTION_NAME = "users"
  val EXTENDED_COLLECTION_NAME = "extended-users"

  val FLAG_PASSWORD_EXP = "pass_exp"
  val FLAG_BLOCKED = "blocked"
  val FLAG_2FACTOR = "2factor"

  def checkAnyId(anyId: String): Boolean = checkUuid(anyId) || checkEmail(anyId) || checkPhone(anyId)

  def checkUuid(id: String): Boolean = isNumberString(id) && id.length == 16

  def checkPhone(id: String): Boolean = isValidPhone(id)

  def checkEmail(id: String): Boolean = isValidEmail(id)

  // TODO: specify rules
  def checkSocialProviderKey(id: String): Boolean = isNumberString(id)

  implicit val reader: Reads[User] = (      
      (JsPath \ "email").readNullable[String] and
      (JsPath \ "phone").readNullable[String] and
      (JsPath \ "passHash").read[String].orElse(Reads.pure("")) and
      (JsPath \ "flags").read[Seq[String]].orElse(Reads.pure(Nil)) and
      (JsPath \ "roles").read[Seq[String]].orElse(Reads.pure(Nil)) and
      (JsPath \ "permissions").read[Seq[String]].orElse(Reads.pure(Nil)) and
      (JsPath \ "hierarchy").read[Seq[String]].orElse(Reads.pure(Nil)) and
      (JsPath \ "firstName").readNullable[String] and
      (JsPath \ "lastName").readNullable[String] and
      (JsPath \ "id").read[String] and
      (JsPath \ "version").read[Int].orElse(Reads.pure(0))
    ) (User.apply _)

  implicit val oWriter = new OWrites[User] {
    def writes(u: User): JsObject = Json.obj(
      "id" -> u.id,
      "email" -> u.email,
      "phone" -> u.phone,
      "flags" -> Option(u.flags).filterNot(_.isEmpty),
      "roles" -> Option(u.roles).filterNot(_.isEmpty),
      "permissions" -> Option(u.permissions).filterNot(_.isEmpty),
      "branch" -> u.branch,
      "hierarchy" -> Option(u.hierarchy).filterNot(_.isEmpty),
      "firstName" -> u.firstName,
      "lastName" -> u.lastName,
      "version" -> u.version
    ).filterNulls
  }

  val mongoReader: Reads[User] = (    
    (JsPath \ "email").readNullable[String] and
    (JsPath \ "phone").readNullable[String] and
    (JsPath \ "passHash").read[String].orElse(Reads.pure("")) and
    (JsPath \ "flags").read[Seq[String]].orElse(Reads.pure(Nil)) and
    (JsPath \ "roles").read[Seq[String]].orElse(Reads.pure(Nil)) and
    Reads.pure(Nil) and
    (JsPath \ "hierarchy").read[Seq[String]].orElse(Reads.pure(Nil)) and
    (JsPath \ "firstName").readNullable[String] and
    (JsPath \ "lastName").readNullable[String] and
    (JsPath \ "_id").read[String] and
    (JsPath \ "version").read[Int].orElse(Reads.pure(0))
  ) (User.apply _)

  val mongoWriter: OWrites[User] = new OWrites[User] {
    def writes(u: User): JsObject = {
      Json.obj(
        "_id" -> u.id,
        "email" -> u.email,
        "phone" -> u.phone,
        "passHash" -> u.passHash,
        "flags" -> u.flags,
        "roles" -> u.roles,
        "hierarchy" -> u.hierarchy,
        "firstName" -> u.firstName,
        "lastName" -> u.lastName,
        "version" -> u.version
      ).filterNulls
    }
  }

  val shortUserWriter = new OWrites[User] {
    def writes(u: User): JsObject = Json.obj(
      "id" -> u.id,
      "em" -> u.email,
      "ph" -> u.phone,
      "fn" -> u.firstName,
      "ln" -> u.lastName,
      "flg" -> Option(u.flags).filterNot(_.isEmpty),
      "rol" -> Option(u.roles).filterNot(_.isEmpty),
      "prm" -> Option(u.permissions).filterNot(_.isEmpty),
      "hrc" -> Option(u.hierarchy).filterNot(_.isEmpty),
    ).filterNulls
  }

  implicit class ExtJson(val js: JsObject) extends AnyVal {
    def filterNulls: JsObject = JsObject(js.fields.filter {
      case (_, JsNull) => false
      case (_, _) => true
    })
  }

}
