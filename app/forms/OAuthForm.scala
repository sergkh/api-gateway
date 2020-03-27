package forms

import forms.FormConstraints._
import play.api.data.Form
import play.api.data.Forms._
import play.api.mvc.Flash

/**
  *
  * @author Yaroslav Derman <yaroslav.derman@gmail.com>
  */
object OAuthForm {

  val token = Form(
    mapping(
      "token" -> nonEmptyText
    )(OAuthToken.apply)(OAuthToken.unapply)
  )

  val authorize = Form(
    mapping(
      "client_id" -> nonEmptyText,
      "response_type" -> enum(ResponseType),
      "scope" -> optional(nonEmptyText),
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
  case class AccessTokenByRefreshToken(refreshToken: String)
  case class AccessTokenByAuthorizationCode(authCode: String, redirectUri: String, clientId: Option[String])
  case class AccessTokenByPassword(username: String, password: String, scope: Option[String])

  case class AuthorizeUsingProvider(clientId: String, 
                           responseType: ResponseType, 
                           scope: Option[String], 
                           redirectUri: Option[String], 
                           state: Option[String], 
                           audience: Option[String]) {
    def scopesList: List[String] = scope.map(_.split(" ").toList).getOrElse(Nil)

    def flash: Flash = Flash(Map(
      "c" -> clientId,
      "t" -> responseType.toString,
      "sc" -> scope.getOrElse(""),
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
      } yield AuthorizeUsingProvider(
          clientId, 
          ResponseType.withName(responseType),
          f.get("sc").filter(_.isEmpty), 
          f.get("r").filter(_.isEmpty),
          f.get("st").filter(_.isEmpty),
          f.get("a").filter(_.isEmpty)
        )
    }
  }

  type ResponseType = ResponseType.Value
  object ResponseType extends Enumeration {    
    val Code = Value(1, "code")
    val Token = Value(2, "token")
  }

}
