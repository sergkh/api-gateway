package modules

import com.mohiva.play.silhouette.api.{Environment, LoginInfo, Silhouette, SilhouetteProvider}
import com.mohiva.play.silhouette.impl.providers.CredentialsProvider
import com.mohiva.play.silhouette.test.FakeEnvironment
import models.{JwtEnv, User}
import net.codingwell.scalaguice.ScalaModule

import scala.concurrent.ExecutionContext.Implicits.global

object TestSilhouette extends ScalaModule {

  implicit val env: Environment[JwtEnv] = new FakeEnvironment[JwtEnv](Seq(
    LoginInfo(CredentialsProvider.ID, "admin@test-mail.com") -> User(
      email = Some("admin@test-mail.com"),
      id = "admin-user"
    )
  ))

  override def configure(): Unit = {
    bind[Silhouette[JwtEnv]].to[SilhouetteProvider[JwtEnv]]
    bind[Environment[JwtEnv]].toInstance(env)
  }
}
