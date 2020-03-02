package services

import javax.inject.Inject
import models.{ApiTemplate, AppException, ErrorCodes, QueryParams}
import play.api.Logger
import play.api.libs.json.{JsObject, Json}
import play.modules.reactivemongo.ReactiveMongoApi
import reactivemongo.api.{Cursor, QueryOpts, ReadPreference}
import reactivemongo.play.json._
import reactivemongo.play.json.collection.JSONCollection
import utils.MongoErrorHandler
import ErrorCodes._
import scala.concurrent.{ExecutionContext, Future}

/**
  * @author Vasyl Zalizetskyi
  */

class ApiTemplateService @Inject()(reactiveMongoApi: ReactiveMongoApi)(implicit ctx: ExecutionContext) {

  val log = Logger(getClass.getName)

  private[this] val db = reactiveMongoApi.database

  private[this] final val id = "_id"

  private[this] def apiCollection = db.map(_.collection[JSONCollection]("swagger"))

  def retrieve(name: String): Future[ApiTemplate] = {
    apiCollection.flatMap(
      _.find(Json.obj(id -> name)).one[ApiTemplate]
        .map(_.getOrElse(throw AppException(ErrorCodes.ENTITY_NOT_FOUND, s"Api specs '$name' not found in db")))
    )
  }

  def save(apiTemplate: ApiTemplate): Future[ApiTemplate] = {
    apiCollection.flatMap(
      _.insert(apiTemplate).map(_ => apiTemplate)
        .recover(MongoErrorHandler.processError)
    )
  }

  def update(name: String, newTemplate: ApiTemplate): Future[ApiTemplate] = {
    apiCollection.flatMap(
      _.findAndUpdate(Json.obj(id -> name), newTemplate, true)
        .map(res => res.result[ApiTemplate].getOrElse(throw AppException(ErrorCodes.ENTITY_NOT_FOUND, s"Api specs '$name' not found in db")))
    )
  }

  def remove(name: String): Future[ApiTemplate] = {
    apiCollection.flatMap(
      _.findAndRemove(Json.obj(id -> name))
        .map(res => res.result[ApiTemplate].getOrElse(throw AppException(ErrorCodes.ENTITY_NOT_FOUND, s"Api specs '$name' not found in db")))
    )
  }

  def list(criteria: Option[JsObject], query: QueryParams): Future[Seq[ApiTemplate]] = {
    val opts = QueryOpts(skipN = query.offset)
    val _criteria = criteria.getOrElse(JsObject(Nil))

    apiCollection.flatMap(_.find(_criteria).options(opts)
      .cursor[ApiTemplate](ReadPreference.secondaryPreferred).collect[List](query.limit, errorHandler[ApiTemplate]))
  }

  def count(criteria: Option[JsObject]): Future[Int] = {
    apiCollection.flatMap(_.count(criteria))
  }

  private def errorHandler[T] = Cursor.ContOnError[List[T]]((v: List[T], ex: Throwable) => {
    log.warn("Error occurred on documents reading", ex)
  })
}
