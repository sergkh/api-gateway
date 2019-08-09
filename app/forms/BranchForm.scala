package forms

import models.Branch
import play.api.data.Form
import play.api.data.Forms._
import utils.Settings._

object BranchForm {
  val createBranch = Form(
    mapping(
      TAG_NAME -> text(3, 1024),
      TAG_DESCRIPTION -> optional(text(3, 4096)),
      "parent" -> optional(text(6, 6))
    )(CreateBranch.apply)(CreateBranch.unapply)
  )

  case class CreateBranch(name: String, description: Option[String], parent: Option[String]) {
    def parentOrRoot: String = parent.getOrElse(Branch.ROOT)
  }

}
