package forms

import forms.FormConstraints._
import models.{Branch, User}
import play.api.data.Form
import play.api.data.Forms._

object UserForm {

  val updatePass = Form(
    mapping(
      "password" -> optional(password),
      "newPassword" -> password,
      "login" -> optional(nonEmptyText)
    )(UpdatePass.apply)(UpdatePass.unapply)
  )

  val createUser = Form(
    mapping(
      "email" -> optional(nonEmptyText.verifying(_.contains("@")).transform(_.toLowerCase, (a: String) => a)),
      "phone" -> optional(nonEmptyText.verifying(phoneNumber)),
      "firstName" -> optional(nonEmptyText),
      "lastName" -> optional(nonEmptyText),
      "password" -> optional(password),
      "flags" -> optional(list(nonEmptyText)),
      "roles" -> optional(list(nonEmptyText)),
      "branch" -> optional(text(Branch.BranchIdSize, Branch.BranchIdSize))
    )(CreateUser.apply)(CreateUser.unapply)
  )

  val updateUser = Form(
    mapping(
      "email" -> optional(nonEmptyText.verifying(_.contains("@")).transform(_.toLowerCase, (a: String) => a)),
      "phone" -> optional(nonEmptyText.verifying(phoneNumber)),
      "firstName" -> optional(nonEmptyText),
      "lastName" -> optional(nonEmptyText),
      "flags" -> default(list(nonEmptyText), Nil),
      "roles" -> default(list(nonEmptyText), Nil),
      "branch" -> optional(text(Branch.BranchIdSize, Branch.BranchIdSize)),
      "version" -> default(number(min = 0), 0)
    )(UpdateUser.apply)(UpdateUser.unapply)
  )

  val blockUser = Form(single("block" -> boolean))

  case class CreateUser(email: Option[String],
                        phone: Option[String],
                        firstName: Option[String],
                        lastName: Option[String],
                        password: Option[String],
                        flags: Option[List[String]],
                        roles: Option[List[String]],
                        branch: Option[String])

  case class UpdatePass(password: Option[String], newPassword: String, login: Option[String] = None)

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
  case class PasswordTTL(passTTL: Option[Long], expireOnce: Option[Boolean])

}