package forms

import forms.FormConstraints._
import models.{Branch, User}
import play.api.data.Form
import play.api.data.Forms._

object UserForm {

  val updatePass = Form(
    mapping(
      "pass" -> optional(password),
      "newPass" -> password,
      "login" -> optional(nonEmptyText)
    )(UpdatePass.apply)(UpdatePass.unapply)
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

  val searchUser = Form(
    mapping(
      "q" -> nonEmptyText(3),
      "limit" -> optional(limit)
    )(SearchUser.apply)(SearchUser.unapply)
  )

  val blockUser = Form(
    mapping(
      "block" -> boolean
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

}