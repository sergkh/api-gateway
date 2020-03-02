package utils

//scalastyle:off magic.number
import java.text.SimpleDateFormat
import java.util.Date

/**
  * Collection of useful date and time operations and conversions.
  *
  * @author Yaroslav Derman <yaroslav.derman@gmail.com>
  */
object DateHelpers {

  /** Number of milliseconds in a day.  */
  val MILLIS_IN_DAY: Long = 1000 * 60 * 60 * 24

  /**
    * Reads date in ISO 8601:2004 format with or without milliseconds and UTC.
    * Also accepts timestamp in non ISO format with space instead of 'T'.
    * Returns null if parameter is null.
    *
    * Formats supported:
    * <ul>
    * <li>UNIX TIME in milliseconds</li>
    * <li>yyyy-MM-dd'T'HH:mm:ss.SSS</li>
    * <li>yyyy-MM-dd'T'HH:mm:ss</li>
    * <li>yyyy-MM-dd HH:mm:ss</li>
    * </ul>
    *
    * @param v value to parse
    * @return date read from string or null if parameter is null.
    * @throws IllegalArgumentException when string is not valid.
    */
  def readTimestampIso(v: String): Date = {
    Option(v).map{ dateStr =>
      if (isNumberString(dateStr)) {
        new Date(dateStr.toLong)
      } else {
        var format = "yyyy-MM-dd HH:mm:ss"

        if (dateStr.contains("T")) {
          format = if (dateStr.contains(".")) "yyyy-MM-dd'T'HH:mm:ss.SSS" else "yyyy-MM-dd'T'HH:mm:ss"
        }

        try {
          new SimpleDateFormat(format).parse(dateStr)
        } catch {
          case ex: java.text.ParseException =>
            throw new IllegalArgumentException(ex)
        }
      }
    }.getOrElse(throw new IllegalArgumentException("Invalid date value"))
  }

  private def isNumberString(s: String) = s.forall(_.isDigit)

}