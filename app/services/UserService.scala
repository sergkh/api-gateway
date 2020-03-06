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
import utils.Logging
import utils.RichJson._

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}
import com.mohiva.play.silhouette.impl.providers.CommonSocialProfile
import scala.util.Try

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
          case Some(u) if u.hasFlag(User.FLAG_PASSWORD_EXP) =>
            throw AppException(ErrorCodes.EXPIRED_PASSWORD, s"User ${u.identifier} has expired password! Please change it.")
          case Some(u) => Some(cacheUser(u))
          case None => None
        }
    }
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
    usersCollection.flatMap(_.insert.one(user))
                   .map(_ => user)
                   .recover(processUserDbEx[User](user.id))
  }

  def updateFlags(user: User): Future[User] = {
    removeFromCaches(user)
    usersCollection.flatMap(_.update.one(
      Json.obj("_id" -> user.id),
      Json.obj(
        "$set" -> Json.obj("flags" -> user.flags),
        "$inc" -> Json.obj("version" -> 1)
      )
    ).map(_ => user).recover(processUserDbEx[User](user.id)))
  }

  /**
    * Saves the social profile for a user.
    *
    * If a user exists for this profile then update the user, otherwise create a new user with the given profile.
    *
    * @param profile The social profile to save.
    * @return The user for whom the profile was saved.
    */
  def findOrCreateSocialUser(profile: CommonSocialProfile, authInfo: AuthInfo): Future[User] = {
    val providerKey = profile.loginInfo.providerKey

    for {
      userOpt <- profile.email.map(getByAnyIdOpt) getOrElse Future.successful(None)
      _       <- Future.successful(log.info(s"User $userOpt"))
      user    <- userOpt.map(Future.successful).getOrElse(createUserFromSocialProfile(providerKey, profile, authInfo))
    } yield user
  }

  private def createUserFromSocialProfile(providerKey: String, profile: CommonSocialProfile, authInfo: => AuthInfo): Future[User] = {
      
    val user = User(
      email = profile.email.map(_.toLowerCase()),
      passHash = "",
      firstName = profile.firstName,
      lastName = profile.lastName
    )

    log.info(s"Creating a new user for a social profile: $user")

    for {
      _ <- Future.successful(log.info("Saving social profile"))
      _ <- authService.save(user.id, profile.loginInfo, authInfo)
      _ <- Future.successful(log.info("Saved social profile"))
      _ <- usersCollection.flatMap(_.insert.one(transformToDbUser(user)).recover(processUserDbEx[User](user.id)))
    } yield user
  }

  def updateUserBySocialProfile(user: User, profile: CommonSocialProfile, authInfo: AuthInfo): Future[User] = {
    val needUpdate = (user.firstName.isEmpty && profile.firstName.nonEmpty) || 
                     (user.lastName.isEmpty && profile.lastName.nonEmpty) || 
                     (user.email.isEmpty && profile.email.nonEmpty)

    if (needUpdate) {
      log.info(s"Updating user by social profile: $user, $profile, $authInfo")

      val updUser = user.copy(
        firstName = user.firstName orElse profile.firstName,
        lastName = user.lastName orElse profile.lastName,
        email = user.email orElse profile.email.map(_.toLowerCase())
      )

      log.info(s"Updating user on auth service")

      authService.update(user.id, profile.loginInfo, authInfo)

      log.info(s"Updating users collection")

      usersCollection.flatMap { users =>
        users.update.one(
          Json.obj("_id" -> user.id),
          Json.obj(
            "$set" -> Json.obj("firstName" -> updUser.firstName, "lastName" -> updUser.lastName, "email" -> updUser.email),
            "$inc" ->  Json.obj("version" -> 1)
          )
        ).recover(processUserDbEx[User](user.id))
      }.map(_ => updUser)
    } else {
      log.info(s"Skipping user update by social profile $user")
      Future.successful(user)
    }
  }

  def updatePassHash(login: String, pass: PasswordInfo): Future[Unit] = {
    usersCollection.flatMap { users =>
      val criteria = login match {
        case uuid: String if User.checkUuid(uuid) => Json.obj("_id" -> uuid.toLong)
        case email: String if User.checkEmail(email) => Json.obj("email" -> email.toLowerCase)
        case phone: String if User.checkPhone(phone) => Json.obj("phone" -> phone)
      }

      val obj = Json.obj(
        "$set" -> Json.obj("passHash" -> pass.password),
        "$inc" ->  Json.obj("version" -> 1)
      )

      users.update(criteria, obj).map(Function.const(()))
    }
  }

  def update(user4update: User, replaceDoc: Boolean = false): Future[User] = {
    usersCollection.flatMap { users =>
      val obj = if (replaceDoc) {
        transformToDbUser(user4update).withFields("passHash" -> JsString(user4update.passHash))
      } else {
        Json.obj("$set" -> transformToDbUser(user4update))
      }

      users.findAndUpdate(versionedSelector(user4update), obj, true).recover(processUserDbEx(user4update.id)).map { res =>
        res.result[User].getOrElse {
          log.error(s"Concurrent modification exception occurred on user ${user4update.id} updating")
          throw AppException(ErrorCodes.CONCURRENT_MODIFICATION, s"User exists, but version is conflicts")
        }
      }
    }
  }

  def delete(user: User): Future[Unit] = {
    usersCollection.map { users =>

      users.delete(true).one(Json.obj("_id" -> user.id))

      removeFromCaches(user)

      authService.removeAll(user.id)
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
      case uuid: String if User.checkUuid(uuid)    => usersCollection.flatMap(_.find(Json.obj("_id" -> uuid)).one[User])
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

  private def processUserDbEx[T](userId: String): PartialFunction[Throwable, T] = {
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
    "_id" -> user4update.id,
    "$or" -> Json.arr(
      Json.obj("version" -> Json.obj("$exists" -> false)),
      Json.obj("version" -> user4update.version)
    )
  )

  private def errorHandler[T] = Cursor.ContOnError[List[T]]((v: List[T], ex: Throwable) => {
    log.warn("Error occurred on users reading", ex)
  })

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

  private def cacheUser(user: User): User = {
    usersCache.set(user.uuidStr, user, cachedTime)

    for (email <- user.email) {
      emailsCache.set(email, user.uuidStr, cachedTime)
    }

    for (phone <- user.phone) {
      phonesCache.set(phone, user.uuidStr, cachedTime)
    }

    authService.retrieveAllSocialInfo(user.id) map { socialIdLst =>
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
