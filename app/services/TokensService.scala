package services

import javax.inject.{Inject, Singleton}
import models.{ AccessToken, RefreshToken }
import scala.concurrent.Future
import java.security.PrivateKey
import com.mohiva.play.silhouette.api.Silhouette
import models.JwtEnv
import scala.concurrent.ExecutionContext
import play.modules.reactivemongo.ReactiveMongoApi
import utils.Logging
import reactivemongo.bson._
import services.formats.MongoFormats._
import reactivemongo.api.bson.collection._

@Singleton
class TokensService @Inject() (mongoApi: ReactiveMongoApi)(implicit exec: ExecutionContext) extends Logging {
  import RefreshToken._

  def store(token: RefreshToken): Future[RefreshToken] = tokens.flatMap(_.insert.one(token)).map(_ => token)

  def get(id: String): Future[Option[RefreshToken]] = tokens.flatMap(_.find(byId(id)).one[RefreshToken])

  def delete(id: String): Future[Unit] = tokens.flatMap(_.delete(true).one(byId(id))).map(_ => ())

  private def tokens = mongoApi.database.map(_.collection[BSONCollection]("refresh_tokens"))
}