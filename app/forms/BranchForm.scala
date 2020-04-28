package forms

import models.Branch
import play.api.data.Form
import play.api.data.Forms._
import FormConstraints._

object BranchForm {
  val createBranch = Form(
    mapping(
      "name" -> name,
      "description" -> optional(description),
      "parent" -> optional(text(6, 6))
    )(CreateBranch.apply)(CreateBranch.unapply)
  )

  case class CreateBranch(name: String, description: Option[String], parent: Option[String]) {
    def parentOrRoot: String = parent.getOrElse(Branch.ROOT)
  }

}
