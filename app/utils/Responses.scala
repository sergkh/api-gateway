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
}
