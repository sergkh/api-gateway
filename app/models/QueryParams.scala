package models

import java.util.Date

import com.impactua.bouncer.commons.models.ResponseCode
import com.impactua.bouncer.commons.models.exceptions.AppException
import utils.Settings

/**
  *
  * @author Yaroslav Derman <yaroslav.derman@gmail.com>
  *         created on 14/02/17
  */
case class QueryParams(private val from: Option[Date] = None,
                       private val to: Option[Date] = None,
                       private val _limit: Option[Int] = None,
                       private val _offset: Option[Int] = None) {

  lazy val (since, until) = {
    for (f <- from; t <- to if f.after(t))
      throw AppException(ResponseCode.INVALID_REQUEST, "Date periods are invalid")

    // machines can have time synchronization issues, but I think that a day is enough
    val now = new Date(System.currentTimeMillis + QueryParams.MILLIS_IN_DAY)

    for (t <- to if t.after(now))
      AppException(ResponseCode.INVALID_REQUEST, "Date periods are invalid")

    val validTo = to.getOrElse(now)
    val validFrom = from.getOrElse(new Date(validTo.getTime - QueryParams.MILLIS_IN_DAY * 30)) // 30 days before to date

    (validFrom, validTo)
  }

  lazy val limit = _limit.getOrElse(Settings.DEFAULT_LIMIT)
  lazy val offset = _offset.getOrElse(Settings.DEFAULT_OFFSET)

  override def toString = " offset:" + offset + ", limit:" + limit

}

object QueryParams {
  private val MILLIS_IN_DAY: Long = 1000 * 60 * 60 * 24
}
