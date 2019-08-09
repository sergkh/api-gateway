package models

//scalastyle:off magic.number
import play.api.libs.json.Json
import utils.StringHelpers

/**
  * @author Yaroslav Derman <yaroslav.derman@gmail.com>.
  */
case class TokenClaims(
                        userId: Long,
                        userEmail: Option[String],
                        userPhone: Option[String],
                        clientId: Long,
                        permissions: List[String]
                      )

object TokenClaims {

  implicit val fmr = Json.format[TokenClaims]

  def apply(
             user: User,
             clientId: Long,
             permissions: List[String]
           ): TokenClaims = new TokenClaims(user.uuid, user.email, user.phone, clientId, permissions)

  type Type = Type.Value

  object Type extends Enumeration {
    val OAUTH_TEMPORARY = Value(1, "OAUTH_TEMPORARY")
    val OAUTH_ETERNAL = Value(2, "OAUTH_ETERNAL")
  }

  def checkId(id: String): Boolean = StringHelpers.isNumberString(id) && id.length == 16

}

case class OAuthAuthorizeRequest(clientId: Long, permissions: List[String], responseType: OAuthAuthorizeRequest.Type, redirectUrl: Option[String] = None)

object OAuthAuthorizeRequest {
  type Type = Type.Value

  object Type extends Enumeration {
    final val CODE = Value(1, "CODE")
    final val TOKEN = Value(2, "TOKEN")
  }

}
