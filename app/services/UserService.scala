package services

import java.util.Date

import akka.http.scaladsl.util.FastFuture
import javax.inject.{Inject, Singleton}
import com.mohiva.play.silhouette.api.util.PasswordInfo
import com.mohiva.play.silhouette.api.{AuthInfo, LoginInfo}
import models.{AppException, ErrorCodes, QueryParams, RolePermissions, User}
import play.api.Configuration
import play.api.cache.{AsyncCacheApi, NamedCache}
import play.api.libs.json._
import play.modules.reactivemongo.ReactiveMongoApi
import reactivemongo.api.{Cursor, QueryOpts, ReadPreference}
import reactivemongo.core.errors.DatabaseException
import reactivemongo.play.json._
import reactivemongo.play.json.collection.JSONCollection
import services.auth.SocialAuthService
import services.social.CustomSocialProfile
import utils.{Logging, UuidGenerator}
import utils.RichJson._

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}

/**
  * Handles actions to users.
  */
@Singleton
class UserService @Inject()(@NamedCache("dynamic-users-cache")   usersCache: AsyncCacheApi,
                            @NamedCache("dynamic-emails-cache") emailsCache: AsyncCacheApi,
                            @NamedCache("dynamic-phones-cache") phonesCache: AsyncCacheApi,
                            @NamedCache("dynamic-social-cache") socialCache: AsyncCacheApi,
                            rolesCache: AsyncCacheApi,
                            reactiveMongoApi: ReactiveMongoApi,
                            authService: SocialAuthService,
                            conf: Configuration)(implicit exec: ExecutionContext) extends UserIdentityService with Logging {

  import ErrorCodes._

  private[this] final val futureNoneUser: Future[Option[User]] = FastFuture.successful(None)

  val cachedTime = conf.get[FiniteDuration]("session.cache")

  private def db = reactiveMongoApi.database

  private def usersCollection = db.map(_.collection[JSONCollection](User.COLLECTION_NAME))

  private def rolesCollection = db.map(_.collection[JSONCollection](RolePermissions.COLLECTION_NAME))

  implicit val userReader = User.mongoReader
  implicit val userWriter = User.mongoWriter

  /**
    * Retrieves a user that matches the specified login info.
    *
    * @param login The login info to retrieve a user.
    * @return The retrieved user or None if no user could be retrieved for the given login info.
    */
  def retrieve(login: LoginInfo): Future[Option[User]] = {
    log.debug(s"Getting user: $login")
    getFromCacheByAnyId(login.providerKey) flatMap {
      case Some(user) =>
        FastFuture.successful(Some(user))
      case None =>
        log.debug("Getting user by from DB: " + login)
        getByAnyIdOpt(login.providerKey) map {
          case Some(u) if u.hasFlag(User.FLAG_BLOCKED) =>
            throw AppException(ErrorCodes.BLOCKED_USER, s"User ${u.identifier} is blocked")
          case Some(u) if u.hasExpiredPassword =>
            throw AppException(ErrorCodes.EXPIRED_PASSWORD, s"User ${u.identifier} has expired password! Please change it.")
          case Some(u) => Some(cacheUser(u))
          case None => None
        }
    }
  }

  private def getFromCacheByAnyId(id: String): Future[Option[User]] = id match {
    case uuid: String if User.checkUuid(uuid) =>
      usersCache.get[User](uuid)
    case email: String if User.checkEmail(email) =>
      emailsCache.get[String](email).flatMap(_.map(usersCache.get[User]).getOrElse(futureNoneUser))
    case phone: String if User.checkPhone(phone) =>
      phonesCache.get[String](phone).flatMap(_.map(usersCache.get[User]).getOrElse(futureNoneUser))
    case socialId: String if User.checkSocialProviderKey(socialId) =>
      socialCache.get[String](socialId).flatMap(_.map(usersCache.get[User]).getOrElse(futureNoneUser))
    case _ => futureNoneUser
  }

  def retrieve(selector: JsObject): Future[Option[JsObject]] = {
    usersCollection.flatMap(_.find(selector).one[JsObject])
  }

  /**
    * Saves a user.
    *
    * @param user The user to save.
    * @return The saved user.
    */
  def save(user: User): Future[User] = {
    removeFromCaches(user)
    usersCollection.flatMap(_.insert(user))
                   .map(_ => user)
                   .recover(processUserDbEx[User](user.uuid))
  }

  def updateFlags(user: User): Future[User] = {
    removeFromCaches(user)
    usersCollection.flatMap(_.update(
      Json.obj("_id" -> user.uuid),
      Json.obj(
        "$set" -> Json.obj("flags" -> user.flags),
        "$inc" -> Json.obj("version" -> 1)
      )
    ).map(_ => user).recover(processUserDbEx[User](user.uuid)))
  }

  /**
    * Saves the social profile for a user.
    *
    * If a user exists for this profile then update the user, otherwise create a new user with the given profile.
    *
    * @param profile The social profile to save.
    * @return The user for whom the profile was saved.
    */
  def save(profile: CustomSocialProfile, authInfo: AuthInfo): Future[User] = {
    val providerKey = profile.loginInfo.providerKey

    (profile.email, profile.phone) match {

      //  if both exists
      case (Some(email), Some(phone)) => getByAnyIdOpt(email) flatMap {
        case Some(user) => updateUserBySocialProfile(user, profile, authInfo)
        case None => getByAnyIdOpt(phone) flatMap {
          case Some(user) => updateUserBySocialProfile(user, profile, authInfo)
          case None => initByProviderKey(providerKey, profile, authInfo)
        }
      }

      //  if only email exist
      case (Some(email), None) => getByAnyIdOpt(email) flatMap {
        case Some(user) => updateUserBySocialProfile(user, profile, authInfo)
        case None => initByProviderKey(providerKey, profile, authInfo)
      }

      //  if only phone exist
      case (None, Some(phone)) => getByAnyIdOpt(phone) flatMap {
        case Some(user) => updateUserBySocialProfile(user, profile, authInfo)
        case None => initByProviderKey(providerKey, profile, authInfo)
      }

      //  if none one exist
      case _ => initByProviderKey(providerKey, profile, authInfo)
    }
  }

  private def initByProviderKey(providerKey: String, profile: CustomSocialProfile, authInfo: => AuthInfo): Future[User] = {
    getByAnyIdOpt(providerKey) flatMap {
      case Some(user) => updateUserBySocialProfile(user, profile, authInfo)
      case None => saveUserBySocialProfile(profile, authInfo)
    }
  }

  def updateUserBySocialProfile(user: User, profile: CustomSocialProfile, authInfo: AuthInfo): Future[User] = {
    val updUser = user.copy(
      firstName = user.firstName orElse profile.firstName,
      lastName = user.lastName orElse profile.lastName,
      email = user.email orElse profile.email,
      phone = user.phone orElse profile.phone
    )

    authService.update(user.uuid, profile.loginInfo, authInfo)

    usersCollection.flatMap { users =>
      users.update(
        Json.obj("_id" -> user.uuid),
        Json.obj(
          "$set" -> transformToDbUser(updUser),
          "$inc" ->  Json.obj("version" -> 1)
        )
      ).map(_ => updUser).recover(processUserDbEx[User](user.uuid))
    }
  }

  def saveUserBySocialProfile(profile: CustomSocialProfile, authInfo: AuthInfo): Future[User] = {
    val user = User(
      uuid = UuidGenerator.generateId,
      email = profile.email,
      phone = profile.phone,
      passHash = "",
      firstName = profile.firstName,
      lastName = profile.lastName
    )

    authService.save(user.uuid, profile.loginInfo, authInfo)

    usersCollection.flatMap(_.insert(transformToDbUser(user)).map(_ => user).recover(processUserDbEx[User](user.uuid)))
  }


  def updatePassHash(login: String, pass: PasswordInfo): Future[Unit] = {
    usersCollection.flatMap { users =>
      val criteria = login match {
        case uuid: String if User.checkUuid(uuid) => Json.obj("_id" -> uuid.toLong)
        case email: String if User.checkEmail(email) => Json.obj("email" -> email.toLowerCase)
        case phone: String if User.checkPhone(phone) => Json.obj("phone" -> phone)
      }

      val obj = Json.obj(
        "$set" -> Json.obj("passHash" -> pass.password, "passUpdated" -> new Date()),
        "$inc" ->  Json.obj("version" -> 1),
        "$pull" -> Json.obj("flags" -> User.FLAG_EXPIRED_PASSWORD) // remove flag if any
      )

      users.update(criteria, obj).map(Function.const(()))
    }
  }

  def updatePassTTL(user: User): Future[User] = {
    usersCollection.flatMap(_.update(
      Json.obj("_id" -> user.uuid),
      Json.obj(
        "$set" -> Json.obj("passTTL" -> user.passTtl, "flags" -> user.flags),
        "$inc" -> Json.obj("version" -> 1)
      )
    ).map(_ => user).recover(processUserDbEx[User](user.uuid)))
  }

  def update(user4update: User, replaceDoc: Boolean = false): Future[User] = {
    usersCollection.flatMap { users =>
      val obj = if (replaceDoc) {
        transformToDbUser(user4update).withFields("passHash" -> JsString(user4update.passHash))
      } else {
        Json.obj("$set" -> transformToDbUser(user4update))
      }

      users.findAndUpdate(versionedSelector(user4update), obj, true).recover(processUserDbEx(user4update.uuid)).map { res =>
        res.result[User].getOrElse {
          log.error(s"Concurrent modification exception occurred on user ${user4update.uuid} updating")
          throw AppException(ErrorCodes.CONCURRENT_MODIFICATION, s"User exists, but version is conflicts")
        }
      }
    }
  }

  def delete(user: User): Future[Unit] = {
    usersCollection.map { users =>

      users.delete(true).one(Json.obj("_id" -> user.uuid))

      removeFromCaches(user)

      authService.removeAll(user.uuid)
    }
  }

  def list(criteria: Option[JsObject], query: QueryParams): Future[Seq[User]] = {
    val opts = QueryOpts(skipN = query.offset)
    val _criteria = criteria.getOrElse(JsObject(Nil))

    usersCollection.flatMap(_.find(_criteria).options(opts)
      .cursor[User](ReadPreference.secondaryPreferred).collect[List](query.limit, errorHandler[User]))
  }

  def count(branch: Option[String] = None): Future[Int] = {
    val selector = branch.map(b => Json.obj("hierarchy" -> b))
    usersCollection.flatMap(_.count(selector))
  }

  def search(criteria: JsObject, query: QueryParams): Future[Seq[User]] = {
    val opts = QueryOpts(skipN = query.offset)
    usersCollection.flatMap(_.find(criteria).options(opts)
      .cursor[User](ReadPreference.secondaryPreferred).collect[List](query.limit, errorHandler[User]))
  }

  def getByAnyId(id: String): Future[User] = getByAnyIdOpt(id).map(_ getOrElse (throw AppException(ErrorCodes.ENTITY_NOT_FOUND, s"User '$id' not found")))

  def getByAnyIdOpt(id: String): Future[Option[User]] = {
    val user = id match {
      case uuid: String if User.checkUuid(uuid) => usersCollection.flatMap(_.find(Json.obj("_id" -> uuid.toLong)).one[User])
      case email: String if User.checkEmail(email) => usersCollection.flatMap(_.find(Json.obj("email" -> email.toLowerCase)).one[User])
      case phone: String if User.checkPhone(phone) => usersCollection.flatMap(_.find(Json.obj("phone" -> phone)).one[User])
      case socialId: String if User.checkSocialProviderKey(socialId) => authService.findUserUuid(socialId) flatMap {
        case Some(uuid) => usersCollection.flatMap(_.find(Json.obj("_id" -> uuid)).one[User])
        case None => Future.successful(None)
      }
      case _ => Future.successful(None)
    }

    user.flatMap { optUser =>
      optUser.map(withPermissions(_).map(Some(_))).getOrElse(Future.successful(None))
    }
  }

  def withPermissions(u: User): Future[User] =
    loadPermissions(u.roles).map(p => u.copy(permissions = p))

  def getRequestedUser(id: String, u: User, permissions: Seq[String] = Seq("users:edit")): Future[User] = id match {
    case "me" => Future.successful(u)
    case alsoMe: String if u.checkId(alsoMe) => Future.successful(u)
    case otherUser: String if permissions.forall(u.hasPermission) => getByAnyId(otherUser)
    case _ => throw AppException(ErrorCodes.ACCESS_DENIED, s"Access denied to another user: $id")
  }

  private def loadPermissions(roles: Seq[String]): Future[Seq[String]] = rolesCache.getOrElseUpdate(roles.mkString(",")) {
    rolesCollection.flatMap(
      _.find(Json.obj("role" -> Json.obj("$in" -> roles)))
        .cursor[RolePermissions](ReadPreference.secondaryPreferred)
        .collect[List](-1, errorHandler[RolePermissions])
        .map(_.flatMap(_.permissions).distinct)
    )
  }

  private def processUserDbEx[T](userId: Long): PartialFunction[Throwable, T] = {
    case ex: DatabaseException if ex.code.exists(c => c == 11000 || c == 11001) =>
      log.error(s"Error occurred on user $userId updating (duplicate identifier) ", ex)
      throw AppException(ErrorCodes.ALREADY_EXISTS, "Email or phone already exists")

    case ex: DatabaseException =>
      log.error(s"Database exception occurred on user $userId updating, code ${ex.code}", ex)
      throw AppException(ErrorCodes.INTERNAL_SERVER_ERROR, "Database error occurred on user updating")

    case ex: Exception =>
      log.error(s"Undefined exception occurred on user $userId updating ", ex)
      throw AppException(ErrorCodes.INTERNAL_SERVER_ERROR, "Internal error occurred on user updating")
  }

  private def transformToDbUser(user: User): JsObject = transformToDbUser(Json.toJson(user).as[JsObject]).withFields("version" -> JsNumber(user.version + 1))

  private def transformToDbUser(userJson: JsObject): JsObject = userJson.without("permissions")

  private def versionedSelector(user4update: User): JsObject = Json.obj(
    "_id" -> user4update.uuid,
    "$or" -> Json.arr(
      Json.obj("version" -> Json.obj("$exists" -> false)),
      Json.obj("version" -> user4update.version)
    )
  )

  private def errorHandler[T] = Cursor.ContOnError[List[T]]((v: List[T], ex: Throwable) => {
    log.warn("Error occurred on users reading", ex)
  })

  private def cacheUser(user: User): User = {
    usersCache.set(user.uuidStr, user, cachedTime)

    for (email <- user.email) {
      emailsCache.set(email, user.uuidStr, cachedTime)
    }

    for (phone <- user.phone) {
      phonesCache.set(phone, user.uuidStr, cachedTime)
    }

    authService.retrieveAllSocialInfo(user.uuid) map { socialIdLst =>
      for (socialId <- socialIdLst) {
        socialCache.set(socialId, user.uuidStr, cachedTime)
      }
    }

    user
  }

  private def removeFromCaches(user: User): Unit = {
    usersCache.remove(user.uuidStr)
    for (email <- user.email) emailsCache.remove(email)
    for (phone <- user.phone) phonesCache.remove(phone)
  }

  def clearUserCaches(): Future[Unit] = {
    for {
     _ <- usersCache.removeAll()
     _ <- emailsCache.removeAll()
     _ <- phonesCache.removeAll()
     _ <- socialCache.removeAll()
     _ <- rolesCache.removeAll()
    } yield ()
  }
}
