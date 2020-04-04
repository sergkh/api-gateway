package services

import javax.inject.{Inject, Singleton}
import models.RefreshToken
import org.mongodb.scala.model.Filters._
import services.MongoApi._
import utils.Logging
import zio._

import scala.concurrent.ExecutionContext

@Singleton
class TokensService @Inject() (mongoApi: MongoApi)(implicit exec: ExecutionContext) extends Logging {

  val col = mongoApi.collection[RefreshToken]("refresh_tokens")

  def store(token: RefreshToken): Task[RefreshToken] = col.insertOne(token).toUnitTask.map(_ => token)

  def get(id: String): Task[Option[RefreshToken]] = col.find(equal("_id", id)).first.toOptionTask

  def delete(id: String): Task[Unit] = col.deleteOne(equal("_id", id)).toUnitTask
  
  def list(userId: String): Task[Seq[RefreshToken]] = col.find(equal("userId", userId)).toTask
}