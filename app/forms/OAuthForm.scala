package forms

import forms.FormConstraints._
import play.api.data.Form
import play.api.data.Forms._
/**
  *
  * @author Yaroslav Derman <yaroslav.derman@gmail.com>
  *         created on 16/03/16
  */
object OAuthForm {

  val token = Form(
    mapping(
      "token" -> nonEmptyText
    )(OAuthToken.apply)(OAuthToken.unapply)
  )

  val getTokens = Form(
    mapping(
      "userId" -> optional(longUuid),
      "accountId" -> optional(longUuid),
      "appId" -> optional(longUuid),
      "limit" -> optional(limit),
      "offset" -> optional(offset)
    )(GetOAuthToken.apply)(GetOAuthToken.unapply)
  )

  val authorize = Form(
    mapping(
      "client_id" -> nonEmptyText,
      "response_type" -> nonEmptyText,
      "client_secret" -> optional(nonEmptyText),
      "redirect_uri" -> optional(nonEmptyText),
      "state" -> optional(nonEmptyText)
    )(AuthorizeUser.apply)(AuthorizeUser.unapply)
  )

  val refreshToken = Form(
    mapping(
      "grant_type" -> nonEmptyText,
      "refresh_token" -> nonEmptyText,
      "client_id" -> nonEmptyText,
      "client_secret" -> nonEmptyText,
    )(RefreshToken.apply)(RefreshToken.unapply)
  )

  case class OAuthToken(token: String)
  case class GetOAuthToken(userId: Option[Long], accountId: Option[Long], appId: Option[Long], limit: Option[Int], offset: Option[Int])
  case class RefreshToken(grantType: String, refreshToken: String, clientId: String, clientSecret: String)
  case class AuthorizeUser(clientId: String, responseType: String, clientSecret: Option[String], redirectUri: Option[String], state: Option[String])
}
