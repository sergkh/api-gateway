package modules

import javax.inject.Inject
import com.mohiva.play.silhouette.api.util.PasswordHasherRegistry
import com.mohiva.play.silhouette.api.{Environment, LoginInfo, Silhouette, SilhouetteProvider}
import com.mohiva.play.silhouette.impl.providers.CredentialsProvider
import com.mohiva.play.silhouette.test.FakeEnvironment
import models.{JwtEnv, User}
import net.codingwell.scalaguice.ScalaModule
import services._
import utils.TaskExt._
import models.ClientApp
import utils.Logging


object TestInitializationModule extends ScalaModule {
  override def configure(): Unit = {
    bind[InitializationService].to[TestInitializationService].asEagerSingleton()
  }
}

class TestInitializationService @Inject() (users: UserService, 
                                clients: ClientAppsService,
                                passRegistry: PasswordHasherRegistry) extends InitializationService with Logging {

  log.info("Starting DB initialization")

  val task = for {
    _ <- users.save(new User(
        email = Some("admin@mail.test"),
        password = Some(passRegistry.current.hash("admin-password")),
        permissions = Some(List("user:write", "user:read")),
        id = "admin"
      ))
    _ <- users.save(new User(
        email = Some("user@mail.test"),
        password = Some(passRegistry.current.hash("user-password")),
        id = "user"
      ))
    _ <- clients.createApp(ClientApp("admin", "Test client", "", "", "", 
        id = TestEnv.KnownClientId,
        secret = "test"
    ))
    } yield ()

    task.unsafeRun

    log.info("DB initialized")
}
