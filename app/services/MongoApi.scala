package services

import zio._
import javax.inject.{Inject, Singleton}
import org.mongodb.scala._
import play.api.Configuration
import akka.http.scaladsl.model.Uri
import models.MongoFormats
import scala.reflect.ClassTag

trait MongoApi {
  def db: MongoDatabase
  def collection[T: ClassTag](name: String): MongoCollection[T] = db.getCollection[T](name)
}

object MongoApi {
  implicit class SingleObservableExtensions[T](val o: SingleObservable[T]) extends AnyVal {
    def toTask: Task[T] = Task.fromFuture(_ => o.head())
    def toOptionTask: Task[Option[T]] = Task.fromFuture(_ => o.headOption())
    def toUnitTask: Task[Unit] = Task.fromFuture(_ => o.head()).map(_ => ())
  }

  implicit class ObservableExtensions[T](val o: Observable[T]) extends AnyVal {
    def toTask: Task[Seq[T]] = Task.fromFuture(_ => o.toFuture())
  }
}

@Singleton
class MongoApiImpl @Inject() (conf: Configuration) extends MongoApi {
  val uri = Uri(conf.get[String]("mongodb.uri"))
  val client = MongoClient(uri.toString)

  val db: MongoDatabase = client.getDatabase(uri.path.toString.stripPrefix("/")).withCodecRegistry(MongoFormats.registry)
}