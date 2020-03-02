package forms

import java.util.Calendar
import java.util.regex.Pattern

import play.api.data.Forms._
import play.api.data.validation._

import scala.util.matching.Regex

object FormConstraints extends Constraints {
    private final val ERROR_PHONE_EMPTY = "error.phone.empty"
    private final val ERROR_PASSWORD_FORMAT = "error.password.format"
    private final val MIN_PASSWORD_LENGTH = 6

    val EMAIL_VALIDATION_PATTERN = Pattern.compile("""^[a-zA-Z0-9\.!#$%&'*+/=?^_`{|}~-]+@[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(?:\.[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*$""")
    val PHONE_VALIDATION_PTRN = """^(\+\d{10,15})$""".r
    

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

    val password = nonEmptyText(minLength = MIN_PASSWORD_LENGTH).verifying(passwordConstraint)

    private val allNumbers = """\d*""".r
    private val allLetters = """[A-Za-z]*""".r
    private def passwordConstraint: Constraint[String] = Constraint("constraints.password") {
      case allNumbers() => Invalid(Seq(ValidationError(ERROR_PASSWORD_FORMAT)))
      case allLetters() => Invalid(Seq(ValidationError(ERROR_PASSWORD_FORMAT)))
      case _ => Valid
    }

    private def validateField(field: String, regex: Regex, emptyMsg: String, invalidMsg: String) = {
        field.trim match {
          case empty if empty.isEmpty => Invalid(ValidationError(emptyMsg))
          case regex(_*) => Valid
          case _ => Invalid(ValidationError(invalidMsg))
        }
      }
}