package models

import com.impactua.bouncer.commons.models.ResponseCode
import com.impactua.bouncer.commons.models.exceptions.AppException
import play.api.libs.functional.syntax._
import play.api.libs.json.{JsPath, OWrites, Reads}
import services.CodeGenerator
import utils.UuidGenerator

/**
  * Describes new Thirdarty application that can access user data via OAuth2 tokens.
  *
  * @author Sergey Khruschak <sergey.khruschak@gmail.com>
  * @author Yaroslav Derman <yaroslav.derman@gmail.com>.
  *         Created on 1/22/16.
  */
case class ThirdpartyApplication(
                                  userId: Long,
                                  name: String,
                                  description: String,
                                  logo: String,
                                  url: String,
                                  contacts: String,
                                  redirectUrlPattern: String,
                                  enabled: Boolean = true,
                                  id: Long = UuidGenerator.generateId,
                                  secret: String = CodeGenerator.generateSecret(ThirdpartyApplication.SECRET_LENGTH)) {

  def toNonEmptyApplication(appEnabled: Option[Boolean],
                            appName: Option[String],
                            appDescription: Option[String],
                            appLogo: Option[String],
                            appUrl: Option[String],
                            appContacts: Option[String],
                            appRedirectUrlPattern: Option[String]): ThirdpartyApplication = {

    copy(name = appName.getOrElse(name),
      description = appDescription.getOrElse(description),
      logo = appLogo.getOrElse(logo),
      url = appUrl.getOrElse(url),
      contacts = appContacts.getOrElse(contacts),
      redirectUrlPattern = appRedirectUrlPattern.getOrElse(redirectUrlPattern),
      enabled = appEnabled.getOrElse(enabled)
    )
  }

  def checkSecret(otherSecret: String): Unit = {
    if (secret != otherSecret) {
      throw AppException(ResponseCode.INVALID_APPLICATION_SECRET, s"Application secret don't match")
    }
  }

}

object ThirdpartyApplication {

  private final val SECRET_LENGTH = 32
  final val COLLECTION_NAME = "thirdparty_apps"

  implicit val appReads: Reads[ThirdpartyApplication] = (
    (JsPath \ "userId").read[Long] and
      (JsPath \ "name").read[String] and
      (JsPath \ "description").read[String] and
      (JsPath \ "logo").read[String] and
      (JsPath \ "url").read[String] and
      (JsPath \ "contacts").read[String] and
      (JsPath \ "redirectUrlPattern").read[String] and
      (JsPath \ "enabled").read[Boolean] and
      (JsPath \ "_id").read[Long] and
      (JsPath \ "secret").read[String]
    ) (ThirdpartyApplication.apply _)

  implicit val appWrites: OWrites[ThirdpartyApplication] = (
    (JsPath \ "userId").write[Long] and
      (JsPath \ "name").write[String] and
      (JsPath \ "description").write[String] and
      (JsPath \ "logo").write[String] and
      (JsPath \ "url").write[String] and
      (JsPath \ "contacts").write[String] and
      (JsPath \ "redirectUrlPattern").write[String] and
      (JsPath \ "enabled").write[Boolean] and
      (JsPath \ "_id").write[Long] and
      (JsPath \ "secret").write[String]
    ) (unlift(ThirdpartyApplication.unapply))

}
