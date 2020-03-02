package services

import com.mohiva.play.silhouette.api.util.PasswordHasher
import javax.inject.Inject
import models.{ApiTemplate, RolePermissions, ThirdpartyApplication, User}
import play.api.{Configuration, Logger}
import play.api.libs.json.Json
import play.modules.reactivemongo.ReactiveMongoApi
import reactivemongo.api.indexes.{Index, IndexType}

import reactivemongo.api.indexes.NSIndex
import reactivemongo.play.json.collection.JSONCollection
import reactivemongo.play.json._
import utils.RandomStringGenerator

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

/**
  * Service that provisions database on the first start.
  */
class InitializationService @Inject()(config: Configuration,
                                      reactiveMongoApi: ReactiveMongoApi,
                                      passwordHasher: PasswordHasher)(implicit ctx: ExecutionContext) {

  val log = Logger(getClass.getName)

  val AdminRole = "ADMIN"

  val db = reactiveMongoApi.database

  db.flatMap(_.collectionNames).onComplete {
    case Success(cols) if cols.contains(User.COLLECTION_NAME) =>
      log.debug("Database already provisioned")
    case Success(_) =>
      init()
    case Failure(ex) =>
      log.error("Error obtaining database collections for initialization", ex)
  }

  def init(): Unit = {
    initSwagger()
    initRoles()
    val admin = initAdminUser()
    initDefaultOAuthApp(admin)
  }

  def initRoles(): Unit = {
    val rolesCollection = db.map(_.collection[JSONCollection](RolePermissions.COLLECTION_NAME))
    val adminPermissions = RolePermissions(AdminRole, config.get[Seq[String]]("app.defaultAdminPermissions"))

    rolesCollection.map(_.insert(adminPermissions))
  }


  def initAdminUser(): User = {
    implicit val userWriter = User.mongoWriter
    val usersCollection = db.map(_.collection[JSONCollection](User.COLLECTION_NAME))

    /*

    usersCollection.map(_.indexesManager) map { manager =>
      manager.ensure(Index(Seq("email" -> IndexType.Ascending), background = true, unique = true, sparse = true)).recover {
        case ex: Exception => log.warn("Error while creating email index: ", ex)
      }
      manager.ensure(Index(Seq("phone" -> IndexType.Ascending), background = true, unique = true, sparse = true)).recover {
        case ex: Exception => log.warn("Error while creating phone index: ", ex)
      }
    }
    */
    val password = RandomStringGenerator.generateSecret(32)

    val admin = User(
      email = Some(config.get[String]("app.defaultAdmin")),
      passHash = passwordHasher.hash(password).password,
      roles = Seq(AdminRole)
    )

    log.info(
      s"""
        +=====================================================================
        | Creating ADMIN user with credentials:
        | Email: ${admin.email.get}
        | Password: $password
        +=====================================================================
      """.stripMargin)

    usersCollection.flatMap(_.insert(admin))

    admin
  }

  def initSwagger(): Unit = {
    // Init swagger
    val swaggerCollection = db.map(_.collection[JSONCollection]("swagger"))
    val defaultApi = ApiTemplate("api")

    swaggerCollection.flatMap( col =>
      col.find(Json.obj("_id" -> defaultApi.name)).one[ApiTemplate].map(_.getOrElse(col.insert(defaultApi)))
    )
  }

  def initDefaultOAuthApp(user: User): Unit = {
    val collection = db.map(_.collection[JSONCollection](ThirdpartyApplication.COLLECTION_NAME))

    val appName = config.get[String]("swagger.appName")
    val clientId = config.get[Long]("swagger.client_id")
    val clientSecret = config.get[String]("swagger.client_secret")

    collection.map(_.insert(
      ThirdpartyApplication(user.uuid, appName, "Default application", "", "", "", "", true, clientId, clientSecret)
    ))
  }

}
