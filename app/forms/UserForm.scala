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
      "login" -> optional(login)
    )(UpdatePass.apply)(UpdatePass.unapply)
  )

  val createUser = Form(
    mapping(
      "email" -> optional(email.verifying(_.contains("@")).transform(_.toLowerCase, (a: String) => a)),
      "phone" -> optional(phone),
      "firstName" -> optional(name),
      "lastName" -> optional(name),
      "password" -> optional(password),
      "flags" -> optional(list(role)),
      "roles" -> optional(list(role)),
      "branch" -> optional(text(Branch.BranchIdSize, Branch.BranchIdSize)),
      "extra" -> optional(fieldMap(extraField))
    )(CreateUser.apply)(CreateUser.unapply)
  )

  val updateUser = Form(
    mapping(
      "email" -> optional(email.transform(_.toLowerCase, (a: String) => a)),
      "phone" -> optional(phone),
      "firstName" -> optional(name),
      "lastName" -> optional(name),
      "flags" -> default(list(role), Nil),
      "roles" -> default(list(role), Nil),
      "branch" -> optional(text(Branch.BranchIdSize, Branch.BranchIdSize)),
      "extra" -> optional(fieldMap(extraField)),
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
                        branch: Option[String],
                        extra: Option[Map[String, String]])

  case class UpdatePass(password: Option[String], newPassword: String, login: Option[String] = None)

  case class UpdateUser(email: Option[String],
                        phone: Option[String],
                        firstName: Option[String],
                        lastName: Option[String],
                        flags: List[String],
                        roles: List[String],
                        branch: Option[String],
                        extra: Option[Map[String, String]],
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
        extra = origin.extra ++ extra.getOrElse(Map.empty),
        version = version
      )
    }
  }

  case class SearchUser(q: String, limit: Option[Int])
  case class PasswordTTL(passTTL: Option[Long], expireOnce: Option[Boolean])

}