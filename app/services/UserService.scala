package services

import akka.http.scaladsl.util.FastFuture
import com.mohiva.play.silhouette.api.services.IdentityService
import com.mohiva.play.silhouette.api.util.PasswordInfo
import com.mohiva.play.silhouette.api.{AuthInfo, LoginInfo}
import com.mohiva.play.silhouette.impl.providers.{CommonSocialProfile, CredentialsProvider}
import javax.inject.{Inject, Singleton}
import models._
import utils.Logging
import play.api.Configuration
import play.api.cache.{AsyncCacheApi, NamedCache}
import play.api.libs.json._
import play.modules.reactivemongo.ReactiveMongoApi
import reactivemongo.api.collections.bson.BSONCollection
import reactivemongo.bson._
import reactivemongo.api.{Cursor, QueryOpts, ReadPreference}
import reactivemongo.core.errors.DatabaseException
import reactivemongo.play.json._
import services.auth.SocialAuthService
import services.formats.MongoFormats._

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}
/**
  * Handles actions to users.
  */
@Singleton
class UserService @Inject()(
                            rolesCache: AsyncCacheApi,
                            rolesService: UsersRolesService,
                            reactiveMongoApi: ReactiveMongoApi,
                            authService: SocialAuthService,
                            conf: Configuration)(implicit ec: ExecutionContext) extends IdentityService[User] with UserExistenceService with Logging {

  val cachedTime = conf.get[FiniteDuration]("session.cache")

  private def db = reactiveMongoApi.database

  private def usersCollection = db.map(_.collection[BSONCollection]("users"))

  /**
    * Retrieves a user that matches the specified login info.
    *
    * @param login The login info to retrieve a user.
    * @return The retrieved user or None if no user could be retrieved for the given login info.
    */
  def retrieve(login: LoginInfo): Future[Option[User]] = {
    log.debug("Getting user by from DB: " + login)

    getByAnyIdOpt(login.providerKey) map {
      case Some(u) if u.hasFlag(User.FLAG_BLOCKED) =>
        throw AppException(ErrorCodes.BLOCKED_USER, s"User ${u.identifier} is blocked")
      case Some(u) if u.hasFlag(User.FLAG_PASSWORD_EXP) =>
        throw AppException(ErrorCodes.EXPIRED_PASSWORD, s"User ${u.identifier} has expired password! Please change it.")
      case userOpt: Any => userOpt
    }
  }

  def exists(login: String): Future[Boolean] = retrieve(LoginInfo(CredentialsProvider.ID, login)).map(_.nonEmpty)

  /**
    * Saves a user.
    *
    * @param user The user to save.
    * @return The saved user.
    */
  def save(user: User): Future[User] = {
    usersCollection.flatMap(_.insert.one(user))
                   .map(_ => user)
                   .recover(processUserDbEx[User](user.id))
  }

  def updateFlags(user: User): Future[User] = {
    usersCollection.flatMap(_.update.one(
      byId(user.id),
      BSONDocument(
        "$set" -> BSONDocument("flags" -> user.flags),
        "$inc" -> BSONDocument("version" -> 1)
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
          byField("_id", user.id),
          BSONDocument(
            "$set" -> BSONDocument("firstName" -> updUser.firstName, "lastName" -> updUser.lastName, "email" -> updUser.email),
            "$inc" -> BSONDocument("version" -> 1)
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
        case id: String if User.checkUuid(id) => byId(id)
        case email: String if User.checkEmail(email) => byField("email", email.toLowerCase)
        case phone: String if User.checkPhone(phone) => byField("phone", phone)
      }

      val obj = BSONDocument(
        "$set" -> BSONDocument("passHash" -> pass.password),
        "$inc" -> BSONDocument("version" -> 1)
      )

      users.update.one(criteria, obj).map(Function.const(()))
    }
  }

  def update(update: User, replaceDoc: Boolean = false): Future[User] = {
    usersCollection.flatMap { users =>
      val user = update.copy(version = update.version + 1)
      val obj = if (replaceDoc) user.toBson else BSONDocument("$set" -> user)

      users.findAndUpdate(versionedSelector(update), obj, true).recover(processUserDbEx(user.id)).map { res =>
        res.result[User].getOrElse {
          log.error(s"Concurrent modification exception occurred on user ${user.id} updating")
          throw AppException(ErrorCodes.CONCURRENT_MODIFICATION, s"User exists, but version is conflicts")
        }
      }
    }
  }

  def delete(user: User): Future[Unit] = {
    for {
      _ <- usersCollection.flatMap(_.delete(true).one(byField("_id", user.id)))
      _ <- authService.removeAll(user.id)
    } yield ()
  }

  def list(criteria: Option[JsObject], limit: Int, offset: Int): Future[Seq[User]] = {
    val opts = QueryOpts(skipN = offset)
    val _criteria = criteria.getOrElse(JsObject(Nil))

    usersCollection.flatMap(_.find(_criteria).options(opts)
      .cursor[User](ReadPreference.secondaryPreferred).collect[List](limit, errorHandler[User]))
  }

  def count(branch: Option[String] = None): Future[Int] = {
    val selector = branch.map(b => BSONDocument("hierarchy" -> b))
    usersCollection.flatMap(_.count(selector))
  }

  def search(criteria: JsObject, limit: Int, offset: Int): Future[Seq[User]] = {
    val opts = QueryOpts(skipN = offset)
    usersCollection.flatMap(_.find(criteria).options(opts)
      .cursor[User](ReadPreference.secondaryPreferred).collect[List](limit, errorHandler[User]))
  }

  def getByAnyId(id: String): Future[User] = getByAnyIdOpt(id).map(_ getOrElse (throw AppException(ErrorCodes.ENTITY_NOT_FOUND, s"User '$id' not found")))

  def getByAnyIdOpt(id: String): Future[Option[User]] = {
    val user = id match {
      case uuid: String if User.checkUuid(uuid)    => usersCollection.flatMap(_.find(byId(uuid)).one[User])
      case email: String if User.checkEmail(email) => usersCollection.flatMap(_.find(byField("email", email.toLowerCase)).one[User])
      case phone: String if User.checkPhone(phone) => usersCollection.flatMap(_.find(byField("phone", phone)).one[User]) 
      case socialId: String if User.checkSocialProviderKey(socialId) => authService.findUserUuid(socialId) flatMap {
        case Some(uuid) => usersCollection.flatMap(_.find(byField("_id", uuid)).one[User])
        case None => Future.successful(None)
      }
      case _ => Future.successful(None)
    }

    user.flatMap { optUser =>
      optUser.map(withPermissions(_).map(Some(_))).getOrElse(Future.successful(None))
    }
  }

  def updateHierarchy(hierarchy: List[String], newHierarchy: List[String]): Future[Unit] = for {
    col <- usersCollection
    selector = Json.obj("hierarchy" -> Json.obj("$all" -> hierarchy))
    push = Json.obj("$push" -> Json.obj("hierarchy" -> Json.obj("$each" -> hierarchy.drop(1))))
    pull = Json.obj("$pullAll" -> Json.obj("hierarchy" -> newHierarchy))
    _ <- col.update.one(selector, push, multi = true)
    _ <- col.update.one(selector, pull, multi = true)
  } yield ()

  def withPermissions(u: User): Future[User] =
    loadPermissions(u.roles).map(p => u.copy(permissions = p))

  def getRequestedUser(id: String, u: User, permissions: Seq[String] = Seq("users:edit")): Future[User] = id match {
    case "me" => Future.successful(u)
    case alsoMe: String if u.checkId(alsoMe) => Future.successful(u)
    case otherUser: String if permissions.forall(u.hasPermission) => getByAnyId(otherUser)
    case _ => throw AppException(ErrorCodes.ACCESS_DENIED, s"Access denied to another user: $id")
  }

  private def loadPermissions(roles: List[String]): Future[List[String]] = rolesCache.getOrElseUpdate(roles.mkString(",")) {
    rolesService.get(roles).map(_.flatMap(_.permissions).distinct)
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

  private def versionedSelector(user: User): BSONDocument = BSONDocument("_id" -> user.id, "version" -> user.version)

  private def errorHandler[T] = Cursor.ContOnError[List[T]]((v: List[T], ex: Throwable) => {
    log.warn("Error occurred on users reading", ex)
  })

  private def createUserFromSocialProfile(providerKey: String, profile: CommonSocialProfile, authInfo: => AuthInfo): Future[User] = {

    val user = User(
      email = profile.email.map(_.toLowerCase()),
      firstName = profile.firstName,
      lastName = profile.lastName
    )

    log.info(s"Creating a new user for a social profile: $user")

    for {
      _ <- Future.successful(log.info("Saving social profile"))
      _ <- authService.save(user.id, profile.loginInfo, authInfo)
      _ <- Future.successful(log.info("Saved social profile"))
      _ <- usersCollection.flatMap(_.insert.one(user).recover(processUserDbEx[User](user.id)))
    } yield user
  }
}
