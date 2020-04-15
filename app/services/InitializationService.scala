package services

import com.mohiva.play.silhouette.api.util.PasswordHasherRegistry
import com.mongodb.client.model.IndexOptions
import javax.inject.Inject
import models.{ClientApp, RolePermissions, User}
import org.mongodb.scala.model.Indexes
import play.api.{Configuration, Logger}
import services.MongoApi._
import utils.RandomStringGenerator
import zio._
import utils.TaskExt._

import scala.concurrent.ExecutionContext
import scala.util.Failure

/**
  * Service that provisions database on the first start.
  */
class InitializationService @Inject()(config: Configuration,
                                      rolesService: UsersRolesService,
                                      userService: UserService,
                                      clientAppsService: ClientAppsService,
                                      branchesService: MongoBranchesService,
                                      mongoApi: MongoApi,
                                      passwordHasher: PasswordHasherRegistry)(implicit ec: ExecutionContext) {
  val log = Logger(getClass.getName)

  val AdminRole = "ADMIN"
  val uniqueSparseIndexOpts = new IndexOptions().unique(true).sparse(true)

  val db = mongoApi.db

  mongoApi.db.listCollectionNames().toTask.flatMap {
    case cols if cols.contains("users") =>
      Task { log.debug("Database already provisioned") }
    case _ =>
      init()
  }.toUnsafeFuture.onComplete{
    case Failure(ex) =>
      log.info("Failed to initialize database: ", ex)
    case _ =>
      log.info("Database initialization finished")
  }

  def init(): Task[Unit] = for {
    admin <- initAdminUser()
    _     <- initRoles()
    _     <- initBranches()
    _     <- initDefaultOAuthApp(admin)
  } yield ()

  def initRoles(): Task[Unit] =
    rolesService.save(RolePermissions(AdminRole, config.get[Seq[String]]("app.defaultAdminPermissions").toList))

  def initAdminUser(): Task[User] = for {
    _ <- userService.col.createIndex(Indexes.ascending("email"), uniqueSparseIndexOpts).toUnitTask
    _ <- userService.col.createIndex(Indexes.ascending("phone"), uniqueSparseIndexOpts).toUnitTask
    password = RandomStringGenerator.generateSecret(32)
    admin = User(
      email = Some(config.get[String]("app.defaultAdmin")),
      password = Some(passwordHasher.current.hash(password)),
      roles = List(AdminRole)
    )
    _ <- userService.save(admin)
    _ <- Task {
          log.info(
            s"""
            +=====================================================================
               | Creating ADMIN user with credentials:
               | Email: ${admin.email.get}
               | Password: $password
            +=====================================================================
          """.stripMargin)
        }
  } yield admin

  def initBranches(): Task[Unit] = branchesService.col.createIndex(Indexes.ascending("hierarchy")).toUnitTask

  def initDefaultOAuthApp(user: User): Task[Unit] = {
    val appName = config.get[String]("swagger.appName")
    val client = ClientApp(user.id, appName, "Default application", "", "", Nil)

    log.info(
      s"""
        +=====================================================================
        | Creating default OAuth client credentials:
        | ClientId: ${client.id}
        | Secret: ${client.secret}
        +=====================================================================
      """.stripMargin)
    for {
      _ <- clientAppsService.col.createIndex(Indexes.ascending("ownerId")).toUnitTask
      _ <- clientAppsService.createApp(client)
    } yield ()
  }
}
