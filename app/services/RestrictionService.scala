package services

import javax.inject.{Inject, Singleton}
import com.impactua.bouncer.commons.models.ResponseCode
import com.impactua.bouncer.commons.models.exceptions.AppException
import com.impactua.bouncer.commons.utils.Logging
import forms.RestrictionForm.CreateRestriction
import models.{Restriction, User}
import play.api.libs.json.{JsObject, JsValue, Json}
import play.modules.reactivemongo.ReactiveMongoApi
import reactivemongo.api.{Cursor, ReadPreference}
import reactivemongo.play.json._
import reactivemongo.play.json.collection.JSONCollection
import utils.MongoErrorHandler

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class RestrictionService @Inject()(mongoApi: ReactiveMongoApi)(implicit ctx: ExecutionContext) extends RegistrationFilter with Logging {

  private def collection = mongoApi.database.map(_.collection[JSONCollection]("restrictions"))

  def save(create: CreateRestriction, user: User): Future[Restriction] = {
    val restriction = Restriction(create.name, user.uuid, create.description, create.pattern)
    collection.flatMap(_.insert(restriction).map(_ => restriction).recover(MongoErrorHandler.processError))
  }

  def update(create: CreateRestriction, user: User): Future[Restriction] = {
    val restriction = Restriction(create.name, user.uuid, create.description, create.pattern)
    collection.flatMap(_.findAndUpdate(Json.obj("_id" -> restriction.name), restriction, true).map(
      _.result[Restriction].getOrElse(throw AppException(ResponseCode.ENTITY_NOT_FOUND, s"Restriction ${create.name} isn't found"))
    ))
  }

  def get(name: String): Future[Restriction] = {
    collection.flatMap(_.find(Json.obj("_id" -> name)).one[Restriction]).map(
      _.getOrElse(throw AppException(ResponseCode.ENTITY_NOT_FOUND, s"Restriction $name isn't found"))
    )
  }

  def list: Future[List[Restriction]] = {
    collection.flatMap(_.find(JsObject(Nil))
      .cursor[Restriction](ReadPreference.secondaryPreferred).collect[List](-1, Cursor.FailOnError[List[Restriction]]()))
  }

  def remove(name: String): Future[Restriction] = {
    collection.flatMap(_.findAndRemove(Json.obj("_id" -> name)).map(
      _.result[Restriction].getOrElse(throw AppException(ResponseCode.ENTITY_NOT_FOUND, s"Restriction $name isn't found"))
    ))
  }

  def validateLogin(optLogin: Option[String]): Future[Unit] = {
    list.map { restrictions =>
      optLogin match {
        case Some(login) if restrictions.exists(restriction => login.matches(restriction.pattern)) =>
          log.info(s"Registration user $login rejected, because its from an untrusted domain or mobile operator")
          throw AppException(ResponseCode.INVALID_IDENTIFIER, s"Login $login is from an untrusted domain or mobile operator")
        case _ => ()
      }
    }
  }

  override def filter(request: JsValue): Future[JsValue] = {
    validateLogin((request \ "login").asOpt[String]).map(_ => request)
  }
}