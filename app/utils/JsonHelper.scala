package utils


import java.util.regex.Pattern

import play.api.Configuration
import play.api.libs.json._

/**
  * Created by Petro.Novak on 13.01.2016.
  *
  * @author Petro Novak
  * @author Yaroslav Derman <yaroslav.dermna@gmail.com>
  *
  */
object JsonHelper {

  val ARRAY_PATTERN = Pattern.compile("(\\w+)(\\[\\d+\\])")

  def getConfiguration(json: JsValue): Configuration = {
    Configuration.from(getAsMap(json))
  }

  def getAsMap(json: JsValue): Map[String, Any] = {
    val keys = getKeys(json)
    val res = for (k <- keys) yield k -> getObject((json \\ k).head)
    res.toMap.filter {
      case (s, Nil) => false
      case _         => true
    }
  }

  private def getKeys(json: JsValue): collection.Set[String] = json match {
    case o: JsObject => o.keys
    case JsArray(as) => as.flatMap(getKeys).toSet
    case _           => Set()
  }

  private def getObject(value: JsValue): Any = value match {
    case JsNumber(n)    => getFromJsNumber(n)
    case JsString(s)    => s
    case JsBoolean(b)   => b
    case JsArray(array) => for (a <- array) yield getObject(a)
    case JsObject(o)    => getAsMap(value)
    case JsNull         => Nil
  }

  private def getFromJsNumber(n: BigDecimal): Any = {
    if (n.isValidLong) {
      n.longValue
    } else if (n.isValidChar) {
      n.charValue
    } else if (n.isDecimalDouble) {
      n.doubleValue
    } else {
      n.intValue
    }
  }

  def toNonemptyJson(fields: (String, Any)*): JsObject = JsObject(
    fields.filter {
      case (k, f: JsUndefined) => false
      case (k, None)           => false
      case (k, null)           => false
      case (k, _)              => true
    }.map { f =>
      val k = f._1

      val v = f._2 match {
        case Some(opt) => opt
        case x: Any    => x
      }

      (k, getJsObject(v))
    }
  )

  private def getJsObject(value: Any): JsValue = value match {
    case v: Int               => JsNumber(v)
    case v: Long              => JsNumber(v)
    case v: Short             => JsNumber(v.toInt)
    case v: Float             => JsNumber(v.toDouble)
    case v: Double            => JsNumber(v)
    case v: String            => JsString(v)
    case v: Boolean           => JsBoolean(v)
    case v: java.util.Date    => JsNumber(v.getTime)
    case v: Seq[Any]          => JsArray(for (s <- v) yield getJsObject(s))
    case v: Array[Any]        => JsArray(for (s <- v) yield getJsObject(s))
    case v: JsValue           => v
    case v: java.util.UUID    => JsString(v.toString)
    case v: Enumeration#Value => JsString(v.toString)
  }

}
