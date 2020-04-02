package services

import javax.inject.{Inject, Singleton}
import org.mongodb.scala._
import play.api.Configuration
import akka.http.scaladsl.model.Uri

trait MongoApi {
  def db: MongoDatabase
}

@Singleton
class MongoApiImpl @Inject() (conf: Configuration) extends MongoApi {
  val uri = Uri(conf.get[String]("mongodb.uri"))
  val client = MongoClient(uri.toString)

  def db: MongoDatabase = client.getDatabase(uri.path.toString.stripPrefix("/"))
}