package services

import java.util.Base64

import akka.actor.ActorSystem
import com.mohiva.play.silhouette.api.Authenticator.Implicits._
import com.mohiva.play.silhouette.api.LoginInfo
import com.mohiva.play.silhouette.api.repositories.AuthenticatorRepository
import com.mohiva.play.silhouette.api.util.JsonFormats._
import com.mohiva.play.silhouette.impl.authenticators.{JWTAuthenticator, JWTAuthenticatorService}
import com.mohiva.play.silhouette.impl.providers.CredentialsProvider
import javax.inject.{Inject, Singleton}
import models.AppEvent._
import models.TokenClaims._
import models._
import org.joda.time.DateTime
import play.api.Configuration
import play.api.libs.functional.syntax._
import play.api.libs.json._
import play.api.mvc.{AnyContent, Request, Results}
import play.modules.reactivemongo.ReactiveMongoApi
import play.mvc.Http
import reactivemongo.api.{Cursor, QueryOpts, ReadPreference}
import reactivemongo.play.json._
import reactivemongo.play.json.collection.JSONCollection
import security.CustomJWTAuthenticatorService
import utils.Responses._
import utils.Settings._
import utils.{JsonHelper, Logging, MongoErrorHandler}

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}
import scala.language.implicitConversions

/**
  * @author Yaroslav Derman <yaroslav.derman@gmail.com>.
  */
@Singleton
class OAuthService @Inject()(userService: UserService,
                             mongo: ReactiveMongoApi,
                             config: Configuration,
                             sessionsService: SessionsService)(implicit exec: ExecutionContext, system: ActorSystem)
  extends AuthenticatorRepository[JWTAuthenticator] with Logging {

  val eventBus = system.eventStream

  //Tokens section

  override def find(id: String): Future[Option[JWTAuthenticator]] = {
    tokensCollection.flatMap(_.find(byId(id)).one[JWTAuthenticator])
  }

  override def update(authenticator: JWTAuthenticator): Future[JWTAuthenticator] = {
    tokensCollection.flatMap(_.findAndUpdate(byId(authenticator.id), authenticator, true).map(
      _.result[JWTAuthenticator].getOrElse(throw AppException(ErrorCodes.ENTITY_NOT_FOUND, s"Authenticator ${authenticator.id} not found in mongo"))
    ))
  }

  override def remove(id: String): Future[Unit] = {
    sessionsService.finish(id)
    tokensCollection.flatMap(_.remove(byId(id)).map { _ => })
  }

  override def add(authenticator: JWTAuthenticator): Future[JWTAuthenticator] = {
    tokensCollection.flatMap(_.insert(authenticator).map(_ => authenticator))
  }

  def list(userId: Option[Long], accountId: Option[Long], appId: Option[Long], limit: Int,
           offset: Int): Future[List[JWTAuthenticator]] = {

    val opts = QueryOpts(batchSizeN = limit, skipN = offset)
    tokensCollection.flatMap(
      _.find(JsonHelper.toNonemptyJson("appId" -> appId, TAG_USER_ID -> userId, TAG_ACCOUNT_ID -> accountId))
        .options(opts)
        .cursor[JWTAuthenticator](ReadPreference.secondaryPreferred).collect[List](-1, errorHandler[JWTAuthenticator])
    )
  }

  def authorize(authReq: OAuthAuthorizeRequest, user: User, authService: JWTAuthenticatorService)
               (implicit request: Request[_]): Future[JsObject] = {

    getApp(authReq.clientId).flatMap { app =>

      val tokenType = if (authReq.responseType == OAuthAuthorizeRequest.Type.TOKEN) {
        TokenClaims.Type.OAUTH_ETERNAL
      } else {
        TokenClaims.Type.OAUTH_TEMPORARY
      }

      val tokenClaims = TokenClaims(user, app.id, authReq.permissions.filter(user.hasPermission))

      val (ttl, loginInfo) = authReq.responseType match {
        case OAuthAuthorizeRequest.Type.CODE =>
          (DEFAULT_TEMP_TOKEN_TTL, LoginInfo(CredentialsProvider.ID, IVALID_LOGIN_INFO_PROVIDER))

        case OAuthAuthorizeRequest.Type.TOKEN =>
          (ETERNAL_TOKEN_TTL, LoginInfo(CredentialsProvider.ID, user.id))

        case unknown: Any =>
          throw AppException(ErrorCodes.INVALID_REQUEST, "Unknown response type: " + unknown)
      }

      oauthAuthenticator(authService, loginInfo, tokenClaims, ttl).flatMap { authenticator =>

        authService.init(authenticator).flatMap { oauthToken =>
          if (tokenType == TokenClaims.Type.OAUTH_ETERNAL) {
            eventBus.publish(Login(user.id, oauthToken, request, authenticator.id, authenticator.expirationDateTime.getMillis))
            eventBus.publish(OauthTokenCreated(user.id, authenticator.id, oauthToken, request))
            Json.obj("accessToken" -> oauthToken, "expiresIn" -> ttl.toSeconds)
          } else {
            Json.obj("code" -> oauthToken)
          }
        }
      }
    }
  }

  def createToken(code: String, authenticatorService: CustomJWTAuthenticatorService)(implicit req: Request[_]): Future[JsObject] = {
    authenticatorService.retrieveByValue(code).flatMap {
      case Some(jwtAuthenticator) => jwtAuthenticator.customClaims match {
        case Some(json) =>
          val claims = try { json.as[TokenClaims] } catch {
            case _: Exception =>
              authenticatorService.discard(jwtAuthenticator, Results.Forbidden)
              log.info("Invalid token claims for token: " + json)
              throw AppException(ErrorCodes.INVALID_TOKEN_CLAIMS, "Invalid external data for token ")
          }

          val ttl = ETERNAL_TOKEN_TTL
          val expire = authenticatorService.clock.now + ttl
          val Array(appId, secret) = new String(Base64.getDecoder.decode(req.headers(Http.HeaderNames.AUTHORIZATION).replaceFirst("Basic ", ""))).split(":")

          getApp(appId).flatMap { app =>
            app.checkSecret(secret)

            val loginInfo = LoginInfo(CredentialsProvider.ID, app.userId.toString)

            authenticatorService.renew(jwtAuthenticator.copy(
              expirationDateTime = expire,
              loginInfo = loginInfo
            )).flatMap { oauthToken =>
                eventBus.publish(Login(claims.userId.toString, oauthToken, req, jwtAuthenticator.id, jwtAuthenticator.expirationDateTime.getMillis))
                eventBus.publish(OauthTokenCreated(claims.userId.toString, jwtAuthenticator.id, oauthToken, req))

                log.info("Creating permanent oauth token: '" + oauthToken + "' for user: " + jwtAuthenticator.loginInfo.providerKey +
                  ", exp date: " + expire + ", permissions: " + claims.permissions.mkString(",") +
                  ", from: " + code + ", for: " + claims.clientId)

                Json.obj("accessToken" -> oauthToken, "expiresIn" -> ttl.toSeconds)
            }
          }

        case None =>
          log.info("Oauth token doesn't have required claims")
          throw AppException(ErrorCodes.ENTITY_NOT_FOUND, "Temporary token claims not found")
      }

      case None =>
        log.info(s"Temporary oauth token $code doesn't found")
        throw AppException(ErrorCodes.ENTITY_NOT_FOUND, "Temporary token not found")
    }
  }

  private def tokensCollection = mongo.database.map(_.collection[JSONCollection]("oauth_tokens"))

  private def byId(id: String) = Json.obj("_id" -> id)

  implicit val dateTimeWriter: Writes[DateTime] = JodaWrites.JodaDateTimeNumberWrites
  implicit val dateTimeJsReader = JodaReads.jodaDateReads("")

  implicit val format: OFormat[JWTAuthenticator] = (
    (JsPath \ "_id").format[String] and
      (JsPath \ "loginInfo").format[LoginInfo] and
      (JsPath \ "lastUsedDateTime").format[DateTime] and
      (JsPath \ "expirationDateTime").format[DateTime] and
      (JsPath \ "idleTimeout").formatNullable[FiniteDuration] and
      (JsPath \ "customClaims").formatNullable[JsObject]
    ) (JWTAuthenticator.apply, unlift(JWTAuthenticator.unapply))

  final val IVALID_LOGIN_INFO_PROVIDER = "oauth.application"

  private val ETERNAL_TOKEN_TTL = config.get[FiniteDuration]("oauth.ttl")

  private def oauthAuthenticator(
                                  authenticatorService: JWTAuthenticatorService,
                                  loginInfo: LoginInfo,
                                  tokenClaims: TokenClaims,
                                  ttl: FiniteDuration)(implicit request: Request[_]) = {
    authenticatorService.create(loginInfo).map { jwt =>
      jwt.copy(
        idleTimeout = None,
        expirationDateTime = jwt.lastUsedDateTime + ttl,
        customClaims = Some(Json.toJson(tokenClaims).as[JsObject])
      )
    }
  }

  //Third Applications section

  def createApp(app: ThirdpartyApplication): Future[ThirdpartyApplication] =
    appsCollection.flatMap(_.insert(app).map(_ => app).recover(MongoErrorHandler.processError[ThirdpartyApplication]))

  def getApp4user(appId: String, userId: String): Future[ThirdpartyApplication] = {
    val selector = Json.obj("_id" -> appId, "userId" -> userId)
    appsCollection.flatMap(_.find(selector).one[ThirdpartyApplication])
      .map(_.getOrElse(throw AppException(ErrorCodes.APPLICATION_NOT_FOUND, s"Application $appId for user $userId not found")))
  }

  def getApp(appId: String): Future[ThirdpartyApplication] = appsCollection.flatMap(_.find(Json.obj("_id" -> appId)).one[ThirdpartyApplication])
    .map(_.getOrElse(throw AppException(ErrorCodes.APPLICATION_NOT_FOUND, s"Application $appId not found")))

  def getApps(userId: String, limit: Int, offset: Int): Future[List[ThirdpartyApplication]] = {
    val criteria = Json.obj("userId" -> userId)
    val opts = QueryOpts(skipN = offset)

    appsCollection.flatMap(_.find(criteria).options(opts)
      .cursor[ThirdpartyApplication](ReadPreference.secondaryPreferred).collect[List](limit, errorHandler[ThirdpartyApplication]))
  }

  def countApp(userId: Option[String]): Future[Int] = {
    val criteria = userId.map(u => Json.obj("userId" -> u))

    appsCollection.flatMap(_.count(criteria))
  }

  def updateApp(id: String, app: ThirdpartyApplication): Future[ThirdpartyApplication] = {
    findAndUpdate(Json.obj("_id" -> id), Json.toJson(app).as[JsObject], id)
  }

  def removeApp(appId: String, user: User, jwtAuthService: CustomJWTAuthenticatorService)
               (implicit req: Request[AnyContent]): Unit = {

    appsCollection.flatMap(_.update(
      Json.obj("_id" -> appId, "userId" -> user.id),
      Json.obj("$set" -> Json.obj("enabled" -> false))
    ))

    tokensCollection.flatMap { tokens =>
      tokens.find(Json.obj("customClaims.clientId" -> appId, "customClaims.userId" -> user.id))
        .cursor[JWTAuthenticator](ReadPreference.secondaryPreferred).collect[List](-1, errorHandler[JWTAuthenticator]).map { ts =>
        ts.map(_.id).foreach { sessionId =>
          sessionsService.finish(sessionId)
          tokens.remove(Json.obj("_id" -> sessionId))
          sessionsService.finish(sessionId)
        }
      }
    }
  }

  private def appsCollection = mongo.database.map(_.collection[JSONCollection](ThirdpartyApplication.COLLECTION_NAME))

  private def findAndUpdate(selector: JsObject, update: JsObject, id: String): Future[ThirdpartyApplication] = {
    appsCollection.flatMap(_.findAndUpdate(selector, update, true).map(
      _.result[ThirdpartyApplication].getOrElse(throw AppException(ErrorCodes.ENTITY_NOT_FOUND, s"Thirdparty application $id not found"))
    ))
  }

  private def errorHandler[T] = Cursor.FailOnError[List[T]]()

}
