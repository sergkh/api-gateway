package models
import scala.util.matching.Regex

case class Service(name: String, prefix: String = "", secret: String = "", basePath: String = "", swaggerUrl: String = "") {
  lazy val prefixPattern: Regex = (prefix + "(.*)").r

  def makeUrl(path: String): Option[String] = path match {
    case prefixPattern(significantPart) =>
      Some(s"$basePath/${significantPart.stripPrefix("/")}")
    case _ => None
  }

  override def toString = s"Service($name($basePath) /$prefix)"
}