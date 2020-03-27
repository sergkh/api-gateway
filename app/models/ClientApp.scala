package models

import play.api.libs.json._
import reactivemongo.bson.Macros.Annotations.Key
import services.CodeGenerator
import utils.UuidGenerator

case class ClientApp(
  ownerId: String,
  name: String,
  description: String,
  logo: String,
  url: String,
  contacts: List[String] = Nil,
  redirectUrlPatterns: List[String] = Nil,
  enabled: Boolean = true,
  @Key("_id") id: String = UuidGenerator.generateId,
  secret: String = CodeGenerator.generateSecret(ClientApp.SECRET_LENGTH)) {

  def update(appEnabled: Option[Boolean],
             appName: Option[String],
             appDescription: Option[String],
             appLogo: Option[String],
             appUrl: Option[String],
             appContacts: Option[List[String]],
             appRedirectUrlPatterns: Option[List[String]]): ClientApp = {

    copy(name = appName.getOrElse(name),
      description = appDescription.getOrElse(description),
      logo = appLogo.getOrElse(logo),
      url = appUrl.getOrElse(url),
      contacts = appContacts.getOrElse(contacts),
      redirectUrlPatterns = appRedirectUrlPatterns.getOrElse(redirectUrlPatterns),
      enabled = appEnabled.getOrElse(enabled)
    )
  }

  def matchRedirect(url: String): Boolean = redirectUrlPatterns.contains(url)

  def checkSecret(otherSecret: String): Unit = {
    if (secret != otherSecret) {
      throw AppException(ErrorCodes.INVALID_REQUEST, s"Application secret don't match")
    }
  }

}

object ClientApp {

  private final val SECRET_LENGTH = 32
  final val COLLECTION_NAME = "clients"

  implicit val appFormat: OFormat[ClientApp] = Json.format[ClientApp]
}
