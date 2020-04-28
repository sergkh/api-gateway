package utils

import play.core.routing.{DynamicPart, PathPart, PathPattern, StaticPart}

import scala.collection.mutable
import scala.util.matching.Regex

object ProxyRoutesParser {

  def parse(str: String, dynChar: Char = ':'): PathPattern = {

    val sb = new StringBuilder()
    var dynamicPart = false
    val parts = mutable.ArrayBuffer[PathPart]()

    val url = dynChar match {
      case '{' => str.replaceFirst("/", "").replaceAll("}", "")
      case _ => str
    }

    url.foreach {
      case `dynChar` if dynamicPart =>
        throw new IllegalArgumentException("Cannot start Dynamic part inside dynamic part")
      case `dynChar` =>
        dynamicPart = true

        if (sb.nonEmpty) {
          parts += StaticPart(sb.toString())
          sb.clear()
        }
      case '/' if dynamicPart =>
        parts += DynamicPart(sb.toString(), """[^/]+""", true)
        dynamicPart = false
        sb.clear()
        sb.append('/')
      case c: Char =>
        sb.append(c)
    }

    if (sb.nonEmpty) {
      if (dynamicPart) {
        parts += DynamicPart(sb.toString(), """[^/]+""", true)
      } else {
        parts += StaticPart(sb.toString())
      }
    }
    PathPattern(parts.toSeq)
  }

  def toRegex(pathPattern: PathPattern, withSlash: Boolean = true): Regex = {
    val regex = {
      val path = pathPattern.parts map {
        case StaticPart(name) => name
        case DynamicPart(_, const, _) => const
      } mkString ""

      if (withSlash) ("/" + path).r else path.r
    }
    regex
  }

  def buildPath(path: PathPattern, params: Map[String, Either[Throwable, String]]): String = {
    path.parts.map {
      case StaticPart(v) => v
      case DynamicPart(name, _, _) => params(name).right.get
    }.mkString("")
  }
}