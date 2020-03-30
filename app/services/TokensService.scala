package services

import javax.inject.{Inject, Singleton}
import models.RefreshToken
import play.modules.reactivemongo.ReactiveMongoApi
import reactivemongo.api.collections.bson.BSONCollection
import services.formats.MongoFormats._
import utils.Logging

import scala.concurrent.{ExecutionContext, Future}
import reactivemongo.api.ReadPreference
import reactivemongo.bson.BSONDocument
import reactivemongo.api.Cursor

@Singleton
class TokensService @Inject() (mongoApi: ReactiveMongoApi)(implicit exec: ExecutionContext) extends Logging {

  def store(token: RefreshToken): Future[RefreshToken] = tokens.flatMap(_.insert.one(token)).map(_ => token)

  def get(id: String): Future[Option[RefreshToken]] = tokens.flatMap(_.find(byId(id)).one[RefreshToken])

  def delete(id: String): Future[Unit] = tokens.flatMap(_.delete(true).one(byId(id))).map(_ => ())

  def list(userId: String): Future[List[RefreshToken]] = {
    val query = BSONDocument("userId" -> userId)

    tokens.flatMap(_.find(query, Option.empty[BSONDocument])
      .cursor[RefreshToken](ReadPreference.secondaryPreferred)
      .collect[List](-1, Cursor.ContOnError[List[RefreshToken]]()))

  }

  private def tokens = mongoApi.database.map(_.collection[BSONCollection]("refresh_tokens"))
}