package utils

import java.util.regex.Pattern

object StringHelpers {

  val EMAIL_VALIDATION_PATTERN = Pattern.compile("""^[a-zA-Z0-9\.!#$%&'*+/=?^_`{|}~-]+@[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(?:\.[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*$""")
  val PHONE_VALIDATION_PTRN = """^(\+\d{10,15})$""".r

  def isNumberString(s: String): Boolean = Option(s).exists(_.forall(_.isDigit))

  /**
    * Email validation by reg expression.
    *
    * @param email string containing email to validate
    * @return true if email is valid.
    */
  def isValidEmail(email: String): Boolean = {
    Option(email).exists { e =>
      val len = e.length
      len > 4 && len < 512 && EMAIL_VALIDATION_PATTERN.matcher(e.toLowerCase).matches
    }
  }

  def isValidPhone(phone: String): Boolean = phone match {
    case PHONE_VALIDATION_PTRN(number) => true
    case _ => false
  }
}
