package forms

import play.api.data.Form
import play.api.data.Forms.{mapping, nonEmptyText}

/**
  *
  * @author Yaroslav Derman <yaroslav.derman@gmail.com>
  *         created on 16/02/17
  */
object InviteForm {

  val form = Form(
    mapping(
      "email" -> UserForm.email,
      "url" -> nonEmptyText
    )(Invite.apply)(Invite.unapply)
  )

  case class Invite(email: String, url: String) 

}
