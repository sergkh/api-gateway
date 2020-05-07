package services

import akka.actor.ActorSystem
import javax.inject.{Inject, Singleton}
import models._
import org.mongodb.scala.model.Filters._
import org.mongodb.scala.model.Updates
import play.api.Configuration
import services.MongoApi._
import utils.Logging
import utils.TaskExt._
import zio._

import scala.concurrent.ExecutionContext
import scala.language.implicitConversions

@Singleton
class ClientAppsService @Inject()(userService: UserService,
                                  mongoApi: MongoApi,
                                  refreshTokens: TokensService,
                                  config: Configuration)(implicit exec: ExecutionContext, system: ActorSystem)
  extends ClientAuthenticator with Logging {

  val col = mongoApi.collection[ClientApp](ClientApp.COLLECTION_NAME)

  def authenticateClient(clientId: String, clientSecret: String): Task[Boolean] =
    getApp(clientId).map(_.secret == clientSecret)

  def createApp(app: ClientApp): Task[ClientApp] = col.insertOne(app).toUnitTask.map(_ => app)

  def getApp4user(appId: String, userId: String): Task[ClientApp] = {
    col.find(and(equal("_id", appId), equal("ownerId", userId)))
        .first().toOptionTask.orFail(AppException(ErrorCodes.APPLICATION_NOT_FOUND, s"Application $appId for user $userId not found"))
  }

  def getApp(appId: String): Task[ClientApp] =
    col.find(equal("_id", appId)).first().toOptionTask
       .orFail(AppException(ErrorCodes.APPLICATION_NOT_FOUND, s"Application $appId not found"))

  def getApps(userId: String, limit: Int, offset: Int): Task[Seq[ClientApp]] =
    col.find(equal("ownerId", userId)).skip(offset).limit(limit).toTask

  def countApp(userId: Option[String]): Task[Long] = userId.map { id =>
    col.countDocuments(equal("ownerId", id)).toTask
  }.getOrElse(col.countDocuments().toTask)

  def updateApp(id: String, app: ClientApp): Task[ClientApp] =
    col.findOneAndUpdate(equal("_id", id), Updates.combine(
      Updates.set("name", app.name),
      Updates.set("description", app.description),
      Updates.set("logo", app.logo),
      Updates.set("url", app.url),
      Updates.set("redirectUrlPatterns", app.redirectUrlPatterns),
      Updates.set("secret", app.secret)
    )).toOptionTask
       .orFail(AppException(ErrorCodes.APPLICATION_NOT_FOUND, s"Application $id not found"))

  def removeApp(clientId: String, user: User): Task[Option[ClientApp]] = {
    col.findOneAndDelete(and(equal("_id", clientId), equal("ownerId", user.id))).toOptionTask <<<
      refreshTokens.deleteForClient(clientId)
      // TODO: clean active sessions

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
}
