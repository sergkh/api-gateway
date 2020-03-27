package forms

import forms.FormConstraints._
import models.OAuthAuthorizeRequest
import play.api.data.Form
import play.api.data.Forms._
import utils.Settings
import utils.Settings._

object ClientAppForm {

  val create = Form[CreateApps](
    mapping(
      "name" -> nonEmptyText,
      "description" -> nonEmptyText,
      "logo" -> nonEmptyText,
      "url" -> nonEmptyText,
      "contacts" -> list(nonEmptyText),
      "redirectUrlPatterns" -> list(nonEmptyText)
    )(CreateApps.apply)(CreateApps.unapply)
  )

  val update = Form[UpdateApps](
    mapping(
      "enabled" -> optional(boolean),
      "name" -> optional(nonEmptyText),
      "description" -> optional(nonEmptyText),
      "logo" -> optional(nonEmptyText),
      "url" -> optional(nonEmptyText),
      "contacts" -> optional(list(nonEmptyText)),
      "redirectUrlPatterns" -> optional(list(nonEmptyText))
    )(UpdateApps.apply)(UpdateApps.unapply)
  )

  val authorize = Form[OAuthAuthorizeRequest](
    mapping(
      "clientId" -> nonEmptyText,
      "permissions" -> list(nonEmptyText),
      "responseType" -> enum(OAuthAuthorizeRequest.Type),
      "redirectUrl" -> optional(nonEmptyText)
    )(OAuthAuthorizeRequest.apply)(OAuthAuthorizeRequest.unapply)
  )

  val code = Form(single("code" -> nonEmptyText))

  val filter = Form[GetApps](
    mapping(
      "limit" -> optional(limit),
      "offset" -> optional(offset)
    )(GetApps.apply)(GetApps.unapply)
  )

  case class CreateApps(name: String,
                        description: String,
                        logo: String,
                        url: String,
                        contacts: List[String],
                        redirectUrlPattern: List[String])

  case class UpdateApps(enabled: Option[Boolean],
                        name: Option[String],
                        description: Option[String],
                        logo: Option[String],
                        url: Option[String],
                        contacts: Option[List[String]],
                        redirectUrlPattern: Option[List[String]])

  case class GetApps(private val l: Option[Int], private val o: Option[Int]) {
    val limit = l.getOrElse(Settings.DEFAULT_LIMIT)
    val offset = o.getOrElse(Settings.DEFAULT_OFFSET)
  }

}
