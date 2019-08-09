package forms

import com.impactua.bouncer.commons.utils.FormConstraints._
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

  case class OAuthToken(token: String)
  case class GetOAuthToken(userId: Option[Long], accountId: Option[Long], appId: Option[Long], limit: Option[Int], offset: Option[Int])

}
