package models

import play.api.libs.json._
import com.mohiva.play.silhouette.api.util.PasswordInfo

/**
  *
  * @author Yaroslav Derman <yaroslav.derman@gmail.com>
  *         created on 29/05/17
  */
trait RegistrationData {

  def ttl: Option[Int]

  def format: String

  def login: String

  def password: Option[PasswordInfo]

  def optEmail: Option[String] = if (login.contains("@")) Some(login) else None

  def optPhone: Option[String] = if (!login.contains("@")) Some(loginFormatted) else None

  def loginFormatted: String = if (login.startsWith("\\+")) login.substring(1).trim else login.trim

}

case class OpenRegistrationData(login: String, password: Option[PasswordInfo], ttl: Option[Int]) extends RegistrationData {
  override def format: String = "open"
}


object RegistrationData {

  implicit val passInfo: OFormat[PasswordInfo] = Json.format[PasswordInfo]
  implicit val openData: OFormat[OpenRegistrationData] = Json.format[OpenRegistrationData]

  implicit val registrationData = new Reads[RegistrationData] {
    override def reads(json: JsValue): JsResult[RegistrationData] = {
      (json \ "type").as[String] match {
        case "open" => openData.reads(json)
      }
    }
  }

  implicit val writer = new Writes[RegistrationData] {
    override def writes(o: RegistrationData): JsValue = o match {
      case o: OpenRegistrationData => Json.obj("type" -> o.format) ++ openData.writes(o)
    }
  }

}
