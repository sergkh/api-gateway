package events

import models.User
import play.api.mvc.RequestHeader

case class Signup(user: User, request: RequestHeader) extends BaseAppEvent("signup", user.id)
case class ReferralRegistrationEvent(user: User, inviter: Long) extends BaseAppEvent("referral_registration", user.id)
