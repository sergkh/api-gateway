package forms

import java.util.Calendar
import java.util.regex.Pattern

import play.api.data.Forms._
import play.api.data.validation._

import scala.util.matching.Regex
import play.api.data.Forms
import play.api.data.FormError
import play.api.data.Mapping
import play.api.data.format.Formatter
import java.{util => ju}

object FormConstraints extends Constraints {
  private final val ERROR_PHONE_EMPTY = "error.phone.empty"
  private final val ERROR_PASSWORD_FORMAT = "error.password.format"
  private final val ERROR_FORBIDDEN_CHARACTERS = "error.text.forbidden.format"
  private final val MIN_PASSWORD_LENGTH = 6
  private final val MAX_PASSWORD_LENGTH = 64 // Bcrypt limitation

  val EMAIL_VALIDATION_PATTERN = Pattern.compile("""^[a-zA-Z0-9\.!#$%&'*+/=?^_`{|}~-]+@[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(?:\.[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*$""")
  val PHONE_VALIDATION_PTRN = """^(\+\d{10,15})$""".r
  private val allNumbers = """\d*""".r
  private val allLetters = """[A-Za-z]*""".r
  private val forbiddenChars = "$[]{}<>"
  
  val passwordConstraint: Constraint[String] = Constraint("constraints.password") {
    case allNumbers() => Invalid(Seq(ValidationError(ERROR_PASSWORD_FORMAT)))
    case allLetters() => Invalid(Seq(ValidationError(ERROR_PASSWORD_FORMAT)))
    case _ => Valid
  }

  val textConstraint: Constraint[String] = Constraint("constraints.text") {
    case cleanText if !forbiddenChars.exists(c => cleanText.contains(c.toString())) => Valid
    case _ => Invalid(Seq(ValidationError(ERROR_FORBIDDEN_CHARACTERS)))
  }
  
  val limit = number(min = 1, max = 100)
  val offset = number(min = 0)
  val password = nonEmptyText(MIN_PASSWORD_LENGTH, MAX_PASSWORD_LENGTH).verifying(passwordConstraint)
  
  val login = text(3, 128).verifying(textConstraint)
  val code = text(4, 60)
  val role = text(4, 10).verifying(textConstraint)
  val permission = text(4, 60).verifying(textConstraint)
  val name = text(3, 30).verifying(textConstraint)
  val description = text(3, 2048).verifying(textConstraint)

  def or[T](constraints: Constraint[T]*): Constraint[T] = Constraint("constraint.or") { field: T =>
      val validationResults = constraints.map(_.apply(field))
      validationResults.find(_ == Valid) match {
          case Some(valid) => Valid
          case None => Invalid(validationResults.filterNot(_ == Valid).map(_.asInstanceOf[Invalid]).flatMap(_.errors))
      }
  }

  def phoneNumber: Constraint[String] = Constraint[String]("constraint.phone") { e =>
      Option(e) match {
        case Some(phone) => validateField(phone, PHONE_VALIDATION_PTRN, ERROR_PHONE_EMPTY, "error.phone.invalid")
        case None        => Invalid(ValidationError(ERROR_PHONE_EMPTY))
      }
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

  private def validateField(field: String, regex: Regex, emptyMsg: String, invalidMsg: String) = {
    field.trim match {
      case empty if empty.isEmpty => Invalid(ValidationError(emptyMsg))
      case regex(_*) => Valid
      case _ => Invalid(ValidationError(invalidMsg))
    }
  }
}