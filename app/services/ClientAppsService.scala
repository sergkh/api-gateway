package services

import akka.actor.ActorSystem
import javax.inject.{Inject, Singleton}
import models._
import play.api.Configuration
import play.modules.reactivemongo.ReactiveMongoApi
import reactivemongo.api.bson._
import reactivemongo.api.bson.collection.BSONCollection
import reactivemongo.api.{Cursor, QueryOpts, ReadPreference}
import services.formats.MongoFormats._
import utils.{Logging, MongoErrorHandler}

import scala.concurrent.{ExecutionContext, Future}
import scala.language.implicitConversions

/**
  * @author Yaroslav Derman <yaroslav.derman@gmail.com>.
  */
@Singleton
class ClientAppsService @Inject()(userService: UserService,
                                  mongo: ReactiveMongoApi,
                                  config: Configuration,
                                  sessionsService: SessionsService)(implicit exec: ExecutionContext, system: ActorSystem)
  extends Logging {

  def createApp(app: ClientApp): Future[ClientApp] =
    appsCollection.flatMap(_.insert.one(app).map(_ => app).recover(MongoErrorHandler.processError[ClientApp]))

  def getApp4user(appId: String, userId: String): Future[ClientApp] = {
    val selector = BSONDocument("_id" -> appId, "ownerId" -> userId)

    appsCollection.flatMap(_.find(selector).one[ClientApp])
      .map(_.getOrElse(throw AppException(ErrorCodes.APPLICATION_NOT_FOUND, s"Application $appId for user $userId not found")))
  }

  def getApp(appId: String): Future[ClientApp] = appsCollection.flatMap(_.find(byId(appId)).one[ClientApp])
    .map(_.getOrElse(throw AppException(ErrorCodes.APPLICATION_NOT_FOUND, s"Application $appId not found")))

  def getApps(userId: String, limit: Int, offset: Int): Future[List[ClientApp]] = {
    val criteria = BSONDocument("ownerId" -> userId)
    val opts = QueryOpts(skipN = offset)

    appsCollection.flatMap(_.find(criteria).options(opts)
      .cursor[ClientApp](ReadPreference.secondaryPreferred).collect[List](limit, errorHandler[ClientApp]))
  }

  def countApp(userId: Option[String]): Future[Int] = {
    appsCollection.flatMap(_.count(userId.map(u => BSONDocument("ownerId" -> u))))
  }

  def updateApp(id: String, app: ClientApp): Future[ClientApp] = findAndUpdate(byId(id), app, id)

  def removeApp(appId: String, user: User): Unit = {

    appsCollection.flatMap(_.delete.one(BSONDocument("_id" -> appId, "ownerId" -> user.id)))

    // TODO: clean application tokens
    // private def tokensCollection = mongo.database.map(_.collection[BSONCollection]("oauth_tokens"))
    // tokensCollection.flatMap { tokens =>
    //   tokens.find(BSONDocument("customClaims.clientId" -> appId, "customClaims.userId" -> user.id))
    //     .cursor[JWTAuthenticator](ReadPreference.secondaryPreferred).collect[List](-1, errorHandler[JWTAuthenticator]).map { ts =>
    //     ts.map(_.id).foreach { sessionId =>
    //       sessionsService.finish(sessionId)
    //       tokens.remove(Json.obj("_id" -> sessionId))
    //       sessionsService.finish(sessionId)
    //     }
    //   }
    // }
  }

  private def findAndUpdate[T: BSONDocumentWriter](selector: BSONDocument, update: T, id: String): Future[ClientApp] = {
    appsCollection.flatMap(_.findAndUpdate(selector, update, true).map(
      _.result[ClientApp].getOrElse(throw AppException(ErrorCodes.ENTITY_NOT_FOUND, s"Thirdparty application $id not found"))
    ))
  }

  private def appsCollection = mongo.database.map(_.collection[BSONCollection](ClientApp.COLLECTION_NAME))

  private def errorHandler[T] = Cursor.FailOnError[List[T]]()

}
