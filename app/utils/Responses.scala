/*
 * Copyright (C) BTC - All Rights Reserved.
 *
 * This file is part of BTC system.
 *
 * Unauthorized copying of this file, via any medium is strictly prohibited.
 * Proprietary and confidential.
 *
 * For further details mail to <sergey.khruschak@gmail.com>.
 */
package utils

import java.util.Date

import com.mohiva.play.silhouette.impl.authenticators.JWTAuthenticator
import models.{TokenClaims, User}
import org.joda.time.DateTime

import scala.concurrent.Future
import scala.language.implicitConversions

/**
  * Util object containing predefined common server responses and response builders.
  * @author Sergey Khruschak
  */
object Responses {
  implicit def toFuture[Status](status: Status): Future[Status] = Future.successful(status)
  implicit def toDate(time: DateTime): Date = time.toDate

  implicit class RichAuthenticator(jwt: JWTAuthenticator) {
    def isOauth: Boolean = jwt.customClaims.exists(c => c.asOpt[TokenClaims].isDefined)

    def notOauth: Boolean = !isOauth

    def oauthPermissions: List[String] = jwt.customClaims.map(_.as[TokenClaims].permissions).getOrElse(Nil)

    def hasAnyOauthPermission(permissions: String*): Boolean = oauthPermissions.exists(permissions.contains)

    def oauthUser: Option[User] = jwt.customClaims.map(_.as[TokenClaims]).map(t => User(t.userId, t.userEmail, t.userPhone, null))

    def checkOauthUser(anyId: String): Boolean = oauthUser.exists(_.checkId(anyId))
  }
}
