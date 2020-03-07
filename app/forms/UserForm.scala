package forms

import java.util.Date

import play.api.data.Form
import play.api.data.Forms._
import play.api.data.validation.{Invalid, Valid, ValidationError}
import utils.Settings._
import models.{Branch, QueryParams, User}
import play.api.data.format.Formatter
import play.api.data.validation.Constraint
import play.api.data.{FormError, Forms, Mapping}
import utils.DateHelpers

import scala.util.matching.Regex

object UserForm {
  private final val PHONE_VALIDATION_PTRN = """^(\+\d{10,15})$""".r
  private final val MIN_PASSWORD_LENGTH = 6
  private final val ERROR_PASSWORD_FORMAT = "error.password.format"
  private final val ERROR_PHONE_EMPTY = "error.phone.empty"

  val limit = number(min = 1, max = 100)
  val offset = number(min = 0)
  val longUuid = longNumber
  val email = nonEmptyText.verifying(_.contains("@"))
  val password = nonEmptyText(minLength = MIN_PASSWORD_LENGTH).verifying(passwordConstraint)
  
  val updatePass = Form(
    mapping(
      TAG_PASS -> optional(password),
      TAG_NEW_PASS -> password,
      TAG_LOGIN -> optional(nonEmptyText)
    )(UpdatePass.apply)(UpdatePass.unapply)
  )

  val passwordTTL = Form(
    mapping(
      "passTtl" -> optional(longNumber),
      "expireOnce" -> optional(boolean),
    )(PasswordTTL.apply)(PasswordTTL.unapply)
  )

  val updateUser = Form(
    mapping(
      TAG_EMAIL -> optional(nonEmptyText.verifying(_.contains("@")).transform(_.toLowerCase, (a: String) => a)),
      TAG_PHONE -> optional(nonEmptyText.verifying(phoneNumber)),
      TAG_FIRST_NAME -> optional(nonEmptyText),
      TAG_LAST_NAME -> optional(nonEmptyText),
      TAG_FLAGS -> default(list(nonEmptyText), Nil),
      "roles" -> default(list(nonEmptyText), Nil),
      "branch" -> optional(text(Branch.BranchIdSize, Branch.BranchIdSize)),
      "version" -> default(number(min = 0), 0)
    )(UpdateUser.apply)(UpdateUser.unapply)
  )

  val searchUser = Form(
    mapping(
      TAG_Q -> nonEmptyText(3),
      TAG_LIMIT -> optional(limit)
    )(SearchUser.apply)(SearchUser.unapply)
  )

  val queryUser = Form(
    mapping(
      TAG_SINCE -> optional(isoDate),
      TAG_UNTIL -> optional(isoDate),
      TAG_LIMIT -> optional(limit),
      TAG_OFFSET -> optional(offset)
    )(QueryParams.apply)(QueryParams.unapply)
  )

  val blockUser = Form(
    mapping(
      TAG_BLOCK -> boolean
    )(BlockUser.apply)(BlockUser.unapply)
  )

  case class UpdatePass(pass: Option[String], newPass: String, login: Option[String] = None)

  case class UpdateUser(email: Option[String],
                        phone: Option[String],
                        firstName: Option[String],
                        lastName: Option[String],
                        flags: List[String],
                        roles: List[String],
                        branch: Option[String],
                        version: Int) {

    def update(origin: User): User = {
      origin.copy(
        email = email,
        phone = phone,
        firstName = firstName,
        lastName = lastName,
        flags = flags,
        roles = roles,
        hierarchy = if (origin.branch == branch) origin.hierarchy else branch.toList,
        version = version
      )
    }
  }

  case class SearchUser(q: String, limit: Option[Int])
  case class BlockUser(block: Boolean)
  case class PasswordTTL(passTTL: Option[Long], expireOnce: Option[Boolean])

  def genderConstraint: Constraint[Int] = Constraint[Int]("constraint.gender") { gender =>
    if (gender == 0 || gender == 1 || gender == 2) Valid else Invalid(ValidationError("Invalid gender value"))
  }

  def phoneNumber: Constraint[String] = Constraint[String]("constraint.phone") { e =>
    Option(e) match {
      case Some(phone) => validateField(phone, PHONE_VALIDATION_PTRN, ERROR_PHONE_EMPTY, "error.phone.invalid")
      case None        => Invalid(ValidationError(ERROR_PHONE_EMPTY))
    }
  }

  private val allNumbers = """\d*""".r
  private val allLetters = """[A-Za-z]*""".r
  private def passwordConstraint: Constraint[String] = Constraint("constraints.password") {
    case allNumbers() => Invalid(Seq(ValidationError(ERROR_PASSWORD_FORMAT)))
    case allLetters() => Invalid(Seq(ValidationError(ERROR_PASSWORD_FORMAT)))
    case _ => Valid
  }

  /**
    * Constructs a simple mapping for a text field (mapped as `scala.Enumeration`)
    * thanks to Leon Radley (https://github.com/leon)
    *
    * For example:
    * {{{
    *   Form("status" -> enum(Status))
    * }}}
    *
    * @param enum the Enumeration#Value
    */
  def enum[E <: Enumeration](enum: E, lowercase: Boolean = false): Mapping[E#Value] = {
    def enumFormat[E <: Enumeration](enum: E, lowercase: Boolean = false): Formatter[E#Value] = new Formatter[E#Value] {
      def bind(key: String, data: Map[String, String]) = {
        play.api.data.format.Formats.stringFormat.bind(key, data).right.flatMap { s =>
          scala.util.control.Exception.allCatch[E#Value]
            .either(enum.values.find(_.toString.equalsIgnoreCase(s)).getOrElse(
              throw new NoSuchElementException(s"No value found for '$s'"))
            )
            .left.map(_ => Seq(FormError(key, "error.enum", Seq(enum.values.iterator.mkString(",")))))
        }
      }

      def unbind(key: String, value: E#Value) = Map(key -> (if(lowercase) value.toString.toLowerCase else value.toString))
    }

    Forms.of(enumFormat(enum, lowercase))
  }

  def isoDate: Mapping[Date] = {
    def isoDateFormat: Formatter[Date] = new Formatter[Date] {
      def bind(key: String, data: Map[String, String]) = {
        play.api.data.format.Formats.stringFormat.bind(key, data).right.flatMap { s =>
          scala.util.control.Exception.allCatch[Date]
            .either(DateHelpers.readTimestampIso(s))
            .left.map(e => Seq(FormError(key, "error.date", Nil)))
        }
      }

      def unbind(key: String, value: Date) = Map(key -> value.getTime.toString)
    }

    Forms.of(isoDateFormat)
  }

  private def validateField(field: String, regex: Regex, emptyMsg: String, invalidMsg: String) = {
    field.trim match {
      case empty if empty.isEmpty => Invalid(ValidationError(emptyMsg))
      case regex(_*) => Valid
      case _ => Invalid(ValidationError(invalidMsg))
    }
  }
}