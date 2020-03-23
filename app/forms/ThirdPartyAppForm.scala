package forms

import forms.FormConstraints._
import models.OAuthAuthorizeRequest
import play.api.data.Form
import play.api.data.Forms._
import utils.Settings
import utils.Settings._

/**
  *
  * @author Yaroslav Derman <yaroslav.derman@gmail.com>.
  *         Created on 1/22/16.
  */
object ThirdPartyAppForm {

  val create = Form[CreateApps](
    mapping(
      TAG_NAME -> nonEmptyText,
      TAG_DESCRIPTION -> nonEmptyText,
      TAG_LOGO -> nonEmptyText,
      TAG_URL -> nonEmptyText,
      TAG_CONTACTS -> nonEmptyText,
      TAG_REDIRECT_URL_PATTERN -> nonEmptyText
    )(CreateApps.apply)(CreateApps.unapply)
  )

  val update = Form[UpdateApps](
    mapping(
      TAG_ENABLED -> optional(boolean),
      TAG_NAME -> optional(nonEmptyText),
      TAG_DESCRIPTION -> optional(nonEmptyText),
      TAG_LOGO -> optional(nonEmptyText),
      TAG_URL -> optional(nonEmptyText),
      TAG_CONTACTS -> optional(nonEmptyText),
      TAG_REDIRECT_URL_PATTERN -> optional(nonEmptyText)
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

  val code = Form(
    mapping(
      TAG_CODE -> nonEmptyText
    )(Code.apply)(Code.unapply)
  )

  val filter = Form[GetApps](
    mapping(
      TAG_LIMIT -> optional(limit),
      TAG_OFFSET -> optional(offset)
    )(GetApps.apply)(GetApps.unapply)
  )

  case class CreateApps(name: String,
                        description: String,
                        logo: String,
                        url: String,
                        contacts: String,
                        redirectUrlPattern: String)

  case class UpdateApps(enabled: Option[Boolean],
                        name: Option[String],
                        description: Option[String],
                        logo: Option[String],
                        url: Option[String],
                        contacts: Option[String],
                        redirectUrlPattern: Option[String])

  case class Code(code: String)

  case class GetApps(private val l: Option[Int], private val o: Option[Int]) {
    val limit = l.getOrElse(Settings.DEFAULT_LIMIT)
    val offset = o.getOrElse(Settings.DEFAULT_OFFSET)
  }

}
