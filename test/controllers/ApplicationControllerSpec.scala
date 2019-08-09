package controllers

import com.mohiva.play.silhouette.api.{EventBus, LoginInfo}
import com.mohiva.play.silhouette.impl.providers.CredentialsProvider
import com.mohiva.play.silhouette.test._
import com.typesafe.config.ConfigFactory
import models.{JwtEnv, User}
import module.{GeneralModule, InitializationModule}
import modules.FakeModule
import modules.FakeModule._
import net.codingwell.scalaguice.ScalaModule
import org.mockito.ArgumentMatchers._
import org.mockito.Mockito.when
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalatest.mockito.MockitoSugar
import org.scalatestplus.play.PlaySpec
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json.Json
import play.api.mvc.Results
import play.api.test.Helpers._
import play.api.test._
import play.api.{Application, Configuration, Mode}
import services.UserService

import scala.concurrent.Future

/**
  * Test case for the [[controllers.ApplicationController]] class.
  */
class ApplicationControllerSpec extends PlaySpec with GuiceOneAppPerSuite with Results with MockitoSugar {

  private val userServiceMock: UserService = {
    val m = mock[UserService]

    when(m.updateFlags(any[User]()))
      .thenAnswer(new Answer[Future[User]] {
        override def answer(invocation: InvocationOnMock): Future[User] =
          Future.successful(
            invocation.getArguments.head.asInstanceOf[User]
          )
      })

    m
  }

  val usersMockModule = new ScalaModule {
    override def configure(): Unit = {      
      bind[UserService].toInstance(userServiceMock)
    }
  }

  implicit override lazy val app: Application = new GuiceApplicationBuilder()
    .loadConfig(Configuration(ConfigFactory.load("test.conf")))
    .overrides(FakeModule)
    .overrides(usersMockModule)
    .disable[GeneralModule].disable[InitializationModule]
    .configure("registration.schema" -> "open")
    .in(Mode.Test)
    .build()

  private lazy val eventBus = app.injector.instanceOf(classOf[EventBus])

  "The '/version' action" should {
    "retrieve info about application for admin user" in {
      val Some(result) = route(app, FakeRequest(routes.ApplicationController.version())
        .withAuthenticator[JwtEnv](LoginInfo(CredentialsProvider.ID, adminIdentity.email.get)))

      contentType(result) mustBe Some("text/plain")
      status(result) mustBe OK
    }
  }

  "The '/logout' action" should {
    "logout user" in {
      val Some(result) = route(app, FakeRequest(routes.ApplicationController.logout())
        .withAuthenticator[JwtEnv](LoginInfo(CredentialsProvider.ID, adminIdentity.email.get)))

      status(result) mustBe SEE_OTHER
    }
  }    

}
