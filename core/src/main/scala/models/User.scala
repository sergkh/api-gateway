package models

import com.mohiva.play.silhouette.api.Identity
import com.mohiva.play.silhouette.api.util.PasswordInfo
import com.mohiva.play.silhouette.impl.providers.{OAuth1Info, OAuth2Info}
import org.mongodb.scala.bson.annotations.BsonProperty
import play.api.libs.json.{JsObject, Json, OWrites}
import utils.RandomStringGenerator
import utils.RichJson._
import utils.StringHelpers._

case class User(email: Option[String] = None,
                phone: Option[String] = None,
                password: Option[PasswordInfo] = None,
                flags: List[String] = Nil,
                roles: List[String] = Nil,
                hierarchy: List[String] = Nil,
                firstName: Option[String] = None,
                lastName: Option[String] = None,
                permissions: Option[List[String]] = None,
                extra: Map[String, String] = Map.empty,
                @BsonProperty("_id") id: String = RandomStringGenerator.generateId(),
                version: Int = 0) extends Identity {

  def fullName: Option[String] = {
    for {
      first <- firstName
      last <- lastName
    } yield first + " " + last
  }

  def identifier: String = email getOrElse (phone getOrElse id).toString

  def checkId(anyId: String): Boolean = email.contains(anyId) || phone.contains(anyId) || id == anyId

  def hasRole(r: String): Boolean = roles.contains(r)

  def hasAnyRole(r: String*): Boolean = roles.exists(hasRole)

  def hasPermission(p: String): Boolean = permissions.exists(_.contains(p)) || User.SelfManagePermissions.contains(p)

  def hasAllPermission(p: String*): Boolean = p.forall(hasPermission)

  def hasAnyPermission(p: String*): Boolean = p.exists(hasPermission)

  def withFlags(addFlags: String*): User = copy(flags = (flags ++ addFlags).distinct)

  def withoutFlags(removeFlags: String*): User = copy(flags = flags diff removeFlags)

  def hasFlag(flag: String): Boolean = flags.contains(flag)

  def branch: Option[String] = hierarchy.headOption

  def info: String = s"id:$id${email.map(e => ",email=" + e).getOrElse("")}"

  override def toString =
    s"u:$id;e:${email.getOrElse("")};p:${phone.getOrElse("")};n:$fullName;r:${roles.mkString(",")};fl:${flags.mkString(",")};br:${hierarchy.mkString(",")}"

}

object User {
  /** Internal scopes that are required for tokens, but not stored as user permissions */
  val SelfManagePermissions = List("user:update", "offline_access", "profile", "email")

  implicit val oAuth1InfoFmt = Json.format[OAuth1Info]
  implicit val oAuth2InfoFmt = Json.format[OAuth2Info]

  val FLAG_EMAIL_NOT_CONFIRMED = "unconfirmed_email"
  val FLAG_PHONE_NOT_CONFIRMED = "unconfirmed_phone"
  val FLAG_PASSWORD_EXP = "pass_exp"
  val FLAG_BLOCKED = "blocked"
  val FLAG_2FACTOR = "2factor"

  def checkAnyId(anyId: String): Boolean = checkEmail(anyId) || checkPhone(anyId)

  def checkPhone(id: String): Boolean = isValidPhone(id)

  def checkEmail(id: String): Boolean = isValidEmail(id)

  /**
    * Returns list of errors if any required field is missing
    */
  def validateNewUser(user: User, requiredIdentifiers: List[String], requirePassword: Boolean): List[String] = {
    requiredIdentifiers.foldLeft(List.empty[String]) {
      case (errors, "email") if user.email.isEmpty => "Email is required" :: errors
      case (errors, "phone") if user.phone.isEmpty => "Phone is required" :: errors
      case (errors, "any") if user.email.isEmpty && user.phone.isEmpty => "Phone or email is required" :: errors
      case (errors, _) => errors
    } ++ (if (requirePassword && user.password.isEmpty) List("Password is required") else Nil)
  }

  // TODO: specify rules
  def checkSocialProviderKey(id: String): Boolean = isNumberString(id)

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
      "extra" -> Option(u.extra).filterNot(_.isEmpty),
      "version" -> u.version
    ).filterNull
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
      "extra" -> u.extra
    ).filterNull
  }

}
