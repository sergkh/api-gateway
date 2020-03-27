package forms

import forms.FormConstraints._
import play.api.data.Form
import play.api.data.Forms._
import play.api.mvc.Flash
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
      "scope" -> nonEmptyText,      
      "redirect_uri" -> optional(nonEmptyText),
      "state" -> optional(nonEmptyText),
      "audience" -> optional(nonEmptyText)
    )(AuthorizeUsingProvider.apply)(AuthorizeUsingProvider.unapply)
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

  case class AuthorizeUsingProvider(clientId: String, 
                           responseType: String, 
                           scope: String, 
                           redirectUri: Option[String], 
                           state: Option[String], 
                           audience: Option[String]) {
    def flash: Flash = Flash(Map(
      "c" -> clientId,
      "t" -> responseType,
      "sc" -> scope,
      "r" -> redirectUri.getOrElse(""),      
      "st" -> state.getOrElse(""),
      "a" -> audience.getOrElse("")
    ))
  }

  object AuthorizeUsingProvider {
    def fromFlash(f: Flash): Option[AuthorizeUsingProvider] = {
      for {
        clientId     <- f.get("c")
        responseType <- f.get("t")
        scope        <- f.get("sc")
      } yield AuthorizeUsingProvider(
          clientId, 
          responseType, 
          scope, 
          f.get("r").filter(_.isEmpty),
          f.get("st").filter(_.isEmpty),
          f.get("a").filter(_.isEmpty)
        )
    }
  }
}
