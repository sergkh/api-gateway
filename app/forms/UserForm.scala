package forms

import com.impactua.bouncer.commons.utils.FormConstraints._
import com.impactua.bouncer.commons.utils.FormMapping._
import play.api.data.Form
import play.api.data.Forms._
import play.api.data.validation.{Constraint, Invalid, Valid, ValidationError}
import utils.Settings._
import models.{Branch, QueryParams, User}

object UserForm {

  val updatePass = Form(
    mapping(
      TAG_PASS -> optional(password),
      TAG_NEW_PASS -> password,
      TAG_LOGIN -> optional(nonEmptyText.verifying(or(emailAddress, phoneNumber)))
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
      TAG_EMAIL -> optional(nonEmptyText.verifying(emailAddress).transform(_.toLowerCase, (a: String) => a)),
      TAG_PHONE -> optional(nonEmptyText.verifying(phoneNumber)),
      TAG_FIRST_NAME -> optional(nonEmptyText),
      TAG_LAST_NAME -> optional(nonEmptyText),
      TAG_FLAGS -> default(seq(nonEmptyText), Nil),
      "roles" -> default(seq(nonEmptyText), Nil),
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
                        flags: Seq[String],
                        roles: Seq[String],
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
        hierarchy = if (origin.branch == branch) origin.hierarchy else branch.toSeq,
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

}