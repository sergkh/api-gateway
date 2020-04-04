package services

import com.mohiva.play.silhouette.api.services.IdentityService
import com.mohiva.play.silhouette.api.util.PasswordInfo
import com.mohiva.play.silhouette.api.{AuthInfo, LoginInfo}
import com.mohiva.play.silhouette.impl.providers.CommonSocialProfile
import javax.inject.{Inject, Singleton}
import models._
import org.mongodb.scala.model.Filters.{equal, _}
import org.mongodb.scala.model.Updates
import org.mongodb.scala.model.Updates.{combine, inc, set}
import play.api.Configuration
import play.api.cache.AsyncCacheApi
import services.MongoApi._
import utils.Logging
import utils.TaskExt._
import zio._

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class UserService @Inject()(
                            rolesCache: AsyncCacheApi,
                            rolesService: UsersRolesService,
                            mongoApi: MongoApi,
                            authService: SocialAuthService,
                            conf: Configuration)(implicit ec: ExecutionContext) extends IdentityService[User] with Logging {

  val allowUnconfirmedEmails = conf.get[Boolean]("app.allowUnconfirmedEmails")
  val allowUnconfirmedPhones = conf.get[Boolean]("app.allowUnconfirmedPhones")

  val col = mongoApi.collection[User]("users")

  /**
    * Retrieves a user that matches the specified login info.
    *
    * @param login The login info to retrieve a user.
    * @return The retrieved user or None if no user could be retrieved for the given login info.
    */
  def retrieve(login: LoginInfo): Future[Option[User]] = {
    log.debug("Getting user by from DB: " + login)

    getByAnyIdOpt(login.providerKey).flatMap {
      case Some(u) if u.hasFlag(User.FLAG_BLOCKED) =>
        Task.fail(AppException(ErrorCodes.BLOCKED_USER, s"User ${u.identifier} is blocked"))
      case Some(u) if u.hasFlag(User.FLAG_PASSWORD_EXP) =>
        Task.fail(AppException(ErrorCodes.EXPIRED_PASSWORD, s"User ${u.identifier} has expired password! Please change it."))
      case Some(u) if u.hasFlag(User.FLAG_EMAIL_NOT_CONFIRMED) && !allowUnconfirmedEmails =>
        Task.fail(AppException(ErrorCodes.EMAIL_NOT_CONFIRMED, s"User email is not confirmed"))
      case Some(u) if u.hasFlag(User.FLAG_PHONE_NOT_CONFIRMED) && !allowUnconfirmedPhones =>
        Task.fail(AppException(ErrorCodes.PHONE_NOT_CONFIRMED, s"User phone is not confirmed"))
      case userOpt: Any => Task.succeed(userOpt)
    }.toUnsafeFuture
  }

  def exists(login: String): Task[Boolean] = getByAnyIdOpt(login).map(_.nonEmpty)

  def save(user: User): Task[User] = col.insertOne(user).toUnitTask.map(_ => user)

  def updateFlags(user: User): Task[User] = {
    import Updates._
    col.updateOne(equal("_id", user.id),
      combine(set("flags", user.flags), inc("version", 1))
    ).toUnitTask.map(_ => user)
  }

  /**
    * Saves the social profile for a user.
    *
    * If a user exists for this profile then update the user, otherwise create a new user with the given profile.
    *
    * @param profile The social profile to save.
    * @return The user for whom the profile was saved.
    */
  def findOrCreateSocialUser(profile: CommonSocialProfile, authInfo: AuthInfo): Task[User] = {
    val providerKey = profile.loginInfo.providerKey

    for {
      userOpt <- profile.email.map(getByAnyIdOpt) getOrElse Task.none
      _       <- Task(log.info(s"User $userOpt"))
      user    <- userOpt.map(Task.succeed(_)).getOrElse(createUserFromSocialProfile(providerKey, profile, authInfo))
    } yield user
  }

  def updateUserBySocialProfile(user: User, profile: CommonSocialProfile, authInfo: AuthInfo): Task[User] = {
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

      for {
        _ <- Task.fromFuture(ec => authService.update(user.id, profile.loginInfo, authInfo))
        _ <-  col.updateOne(equal("_id", user.id),
            combine(
              set("firstName", updUser.firstName),
              set("lastName", updUser.lastName),
              set("email", updUser.email),
              inc("version", 1))
          ).toUnitTask.map(_ => user)
      } yield user
    } else {
      log.info(s"Skipping user update by social profile $user")
      Task.succeed(user)
    }
  }

  def updatePassHash(login: String, pass: PasswordInfo): Task[Unit] = {
    val criteria = login match {
      case id: String if User.checkUuid(id) => equal("_id", id)
      case email: String if User.checkEmail(email) => equal("email", email.toLowerCase)
      case phone: String if User.checkPhone(phone) => equal("phone", phone)
    }

    col.updateOne(criteria,
      combine(
        set("password", pass.password),
        inc("version", 1))
    ).toUnitTask
  }

  def update(user: User): Task[Option[User]] = {
    col.findOneAndUpdate(and(equal("_id", user.id), equal("version", user.version)),
      combine(
        set("email", user.email),
        set("phone", user.phone),
        set("flags", user.flags),
        set("roles", user.roles),
        set("hierarchy", user.hierarchy),
        set("firstName", user.firstName),
        set("lastName", user.lastName),
        inc("version", 1))
    ).toOptionTask
  }

  def delete(user: User): Task[Unit] = {
    for {
      _ <- col.deleteOne(equal("_id", user.id)).toUnitTask
      _ <- Task.fromFuture(ec => authService.removeAll(user.id))
    } yield ()
  }

  def list(branch: Option[String] = None, limit: Int, offset: Int): Task[Seq[User]] = {
    val list = (branch.map { b => col.find(equal("hierarchy", b)) } getOrElse col.find())
    list.skip(offset).limit(limit).toTask
  }

  def count(branch: Option[String] = None): Task[Long] =
    (branch.map { b => col.countDocuments(equal("hierarchy", b)) } getOrElse col.countDocuments()).toTask

  def getByAnyId(id: String): Task[User] = getByAnyIdOpt(id).map(_ getOrElse (throw AppException(ErrorCodes.ENTITY_NOT_FOUND, s"User '$id' not found")))

  def getByAnyIdOpt(id: String): Task[Option[User]] = {
    val user = id match {
      case uuid: String if User.checkUuid(uuid)    => col.find(equal("_id", uuid)).first.toOptionTask
      case email: String if User.checkEmail(email) => col.find(equal("email", email.toLowerCase)).first.toOptionTask
      case phone: String if User.checkPhone(phone) => col.find(equal("phone", phone.toLowerCase)).first.toOptionTask
      case socialId: String if User.checkSocialProviderKey(socialId) => Task.fromFuture(ec => authService.findUserUuid(socialId)) flatMap {
        case Some(uuid) => col.find(equal("_id", uuid)).first.toOptionTask
        case None => Task.none
      }
      case _ => Task.none
    }

    user.flatMap { optUser =>
      optUser.map(withPermissions(_).map(Some(_))).getOrElse(Task.none)
    }
  }

  def updateHierarchy(hierarchy: List[String], newHierarchy: List[String]): Task[Unit] = {
    val selector = all("hierarchy", hierarchy)
    for {
      _ <- col.updateMany(selector, Updates.pushEach("hierarchy", hierarchy.drop(1))).toUnitTask
      _ <- col.updateMany(selector, Updates.pullAll("hierarchy", newHierarchy)).toUnitTask
    } yield ()
  }

  def withPermissions(u: User): Task[User] = loadPermissions(u.roles).map(p => u.copy(permissions = Some(p)))

  def getRequestedUser(id: String, u: User, permissions: Seq[String] = Seq("users:edit")): Task[User] = id match {
    case "me" => Task.succeed(u)
    case alsoMe: String if u.checkId(alsoMe) => Task.succeed(u)
    case otherUser: String if permissions.forall(u.hasPermission) => getByAnyId(otherUser)
    case _ => Task.fail(AppException(ErrorCodes.ACCESS_DENIED, s"Access denied to another user: $id"))
  }

  private def loadPermissions(roles: List[String]): Task[List[String]] = Task.fromFuture(ec => rolesCache.getOrElseUpdate(roles.mkString(",")) {
    rolesService.get(roles).map(_.flatMap(_.permissions).distinct).toUnsafeFuture
  })

  private def createUserFromSocialProfile(providerKey: String, profile: CommonSocialProfile, authInfo: => AuthInfo): Task[User] = {

    val user = User(
      email = profile.email.map(_.toLowerCase()),
      firstName = profile.firstName,
      lastName = profile.lastName
    )

    for {
      _ <- Task(log.info(s"Creating a new user for a social profile: $user"))
      _ <- Task.fromFuture(ec => authService.save(user.id, profile.loginInfo, authInfo))
      _ <- save(user)
    } yield user
  }
}
