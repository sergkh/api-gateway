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
      "userId" -> optional(nonEmptyText),
      "appId" -> optional(nonEmptyText),
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

  val grantType = Form(single("grant_type" -> nonEmptyText))

  val getAccessTokenByPass = Form(
    mapping(
      "username" -> nonEmptyText,
      "password" -> nonEmptyText,
      "scope" -> optional(nonEmptyText)
    )(AccessTokenByPassword.apply)(AccessTokenByPassword.unapply)
  )

  val getAccessTokenFromRefreshToken = Form(
    mapping(
      "refresh_token" -> nonEmptyText,
    )(AccessTokenByRefreshToken.apply)(AccessTokenByRefreshToken.unapply)
  )

  val getAccessTokenFromAuthCode = Form(
    mapping(
      "code" -> nonEmptyText,
      "redirect_uri" -> nonEmptyText,
      "client_id" -> optional(nonEmptyText)      
    )(AccessTokenByAuthorizationCode.apply)(AccessTokenByAuthorizationCode.unapply)
  )

  case class OAuthToken(token: String)
  case class GetOAuthToken(userId: Option[String], appId: Option[String], limit: Option[Int], offset: Option[Int])
  case class AccessTokenByRefreshToken(refreshToken: String)
  case class AccessTokenByAuthorizationCode(authCode: String, redirectUri: String, clientId: Option[String])
  case class AccessTokenByPassword(username: String, password: String, scope: Option[String])

  case class AuthorizeUser(clientId: String, responseType: String, clientSecret: Option[String], redirectUri: Option[String], state: Option[String])
}
