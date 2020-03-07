package services

import com.mohiva.play.silhouette.api.util.PasswordHasher
import javax.inject.Inject
import models.{RolePermissions, ThirdpartyApplication, User}
import play.api.{Configuration, Logger}
import play.modules.reactivemongo.ReactiveMongoApi
import reactivemongo.play.json.collection.JSONCollection
import utils.RandomStringGenerator

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}
import services.formats.MongoFormats._
import reactivemongo.bson._
import reactivemongo.api.bson.collection._
import services.formats.MongoFormats._
import reactivemongo.api.indexes.{Index, IndexType}

/**
  * Service that provisions database on the first start.
  */
class InitializationService @Inject()(config: Configuration,
                                      reactiveMongoApi: ReactiveMongoApi,
                                      passwordHasher: PasswordHasher)(implicit ec: ExecutionContext) {

  val log = Logger(getClass.getName)

  val AdminRole = "ADMIN"

  val db = reactiveMongoApi.database

  db.flatMap(_.collectionNames).onComplete {
    case Success(cols) if cols.contains("users") =>
      log.debug("Database already provisioned")
    case Success(_) =>
      init()
    case Failure(ex) =>
      log.error("Error obtaining database collections for initialization", ex)
  }

  def init(): Unit = {
    initRoles()
    val admin = initAdminUser()
    initBranches()
    initDefaultOAuthApp(admin)
  }

  def initRoles(): Unit = {
    val rolesCollection = db.map(_.collection[BSONCollection](RolePermissions.Collection))
    val adminPermissions = RolePermissions(AdminRole, config.get[Seq[String]]("app.defaultAdminPermissions").toList)

    rolesCollection.map(_.insert.one(adminPermissions))
  }


  def initAdminUser(): User = {
    implicit val userWriter = User.mongoWriter
    val usersCollection = db.map(_.collection[BSONCollection]("users"))
    
    usersCollection.map(_.indexesManager) map { manager =>
      manager.ensure(index(Seq("email" -> IndexType.Ascending), "email_idx"))
      manager.ensure(index(Seq("phone" -> IndexType.Ascending), "phone_idx"))
    }

    val password = RandomStringGenerator.generateSecret(32)

    val admin = User(
      email = Some(config.get[String]("app.defaultAdmin")),
      passHash = passwordHasher.hash(password).password,
      roles = List(AdminRole)
    )

    log.info(
      s"""
        +=====================================================================
        | Creating ADMIN user with credentials:
        | Email: ${admin.email.get}
        | Password: $password
        +=====================================================================
      """.stripMargin)

    usersCollection.flatMap(_.insert.one(admin))

    admin
  }

  def initBranches() {
    val branchesCollection = db.map(_.collection[BSONCollection]("branches"))

    branchesCollection.map(_.indexesManager) map { manager =>
      manager.ensure(index(Seq("hierarchy" -> IndexType.Ascending), "hierarchy_idx", unique = false))
    }
  }

  def initDefaultOAuthApp(user: User): Unit = {
    val collection = db.map(_.collection[JSONCollection](ThirdpartyApplication.COLLECTION_NAME))

    val appName = config.get[String]("swagger.appName")
    val clientId = config.get[String]("swagger.client_id")
    val clientSecret = config.get[String]("swagger.client_secret")

    collection.map(_.insert(
      ThirdpartyApplication(user.id, appName, "Default application", "", "", "", "", true, clientId, clientSecret)
    ))
  }

  private def index(key: Seq[(String, IndexType)], name: String, unique: Boolean = true, sparse: Boolean = true) = {
    
    Index(BSONSerializationPack)(
      key = key,
      name = Some(name),
      unique = unique,
      background = true,
      dropDups = false,
      sparse = sparse,
      expireAfterSeconds = None,
      storageEngine = None,
      weights = None,
      defaultLanguage = None,
      languageOverride = None,
      textIndexVersion = None,
      sphereIndexVersion = None,
      bits = None,
      min = None,
      max = None,
      bucketSize = None,
      collation = None,
      wildcardProjection = None,
      version = None,
      partialFilter = None,
      options = BSONDocument.empty)    
  }
}
