package controllers

import java.util.concurrent.TimeUnit

import com.mohiva.play.silhouette.api.util.PasswordInfo
import com.mohiva.play.silhouette.api.{EventBus, LoginInfo}
import com.mohiva.play.silhouette.impl.providers.CredentialsProvider
import com.typesafe.config.ConfigFactory
import models.AppEvent.OtpGeneration
import models.{AppException, ErrorCodes, User}
import module.{GeneralModule, InitializationModule}
import modules.TestModule
import modules.TestModule._
import net.codingwell.scalaguice.ScalaModule
import org.mockito.ArgumentMatchers._
import org.mockito.Mockito._
import org.mockito.MockitoSugar
import org.scalatestplus.play.PlaySpec
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json.{JsValue, Json}
import play.api.mvc.Results
import play.api.test.FakeRequest
import play.api.test.Helpers._
import play.api.{Application, Configuration, Mode}
import services.RegistrationFiltersChain

import scala.concurrent.Future
import services.UserExistenceService

/**
  * @author Yaroslav Derman <yaroslav.derman@gmail.com>.
  *         created on 07.08.2016.
  */
class OpenRegistrationSpec extends PlaySpec
  with GuiceOneAppPerSuite
  with Results
  with MockitoSugar {

  import helpers.EventBusHelpers._

  private val userServiceMock = mock[UserExistenceService]
  private val filtersChaingMock = mock[RegistrationFiltersChain]

  val usersMockModule = new ScalaModule {
    override def configure(): Unit = {
      bind[UserExistenceService].toInstance(userServiceMock)
      bind[RegistrationFiltersChain].toInstance(filtersChaingMock)
    }
  }

  implicit override lazy val app: Application = new GuiceApplicationBuilder()
    .loadConfig(Configuration(ConfigFactory.load("test.conf")))
    .overrides(TestModule)
    .overrides(usersMockModule)
    .disable[GeneralModule].disable[InitializationModule]
    .configure("registration.schema" -> "open", "kafka.enabled" -> "false")
    .in(Mode.Test)
    .build()

  "The '/register' and '/confirm' action" should {
    "create, confirm, login and delete user for open registration schema" in {
      implicit val actorSystem = app.actorSystem
      val codeFuture = catchEvent[OtpGeneration]

      when(userServiceMock.exists(newIdentity.email.get)).thenReturn(Future.successful(false))

      val requestBody = Json.obj("login" -> newIdentity.email.get, "password" -> newIdentity.passHash)
      val Some(result) = route(app, FakeRequest(routes.ApplicationController.register()).withJsonBody(requestBody))

      val otpThrown = the[AppException] thrownBy {
        status(result)
      }

      otpThrown.code mustBe ErrorCodes.CONFIRMATION_REQUIRED

      val Some(failedConfirm) = route(app, FakeRequest(routes.ApplicationController.confirm())
        .withJsonBody(Json.obj("code" -> "undefined", "login" -> newIdentity.email.get)))

      val thrown = the[AppException] thrownBy {
        status(failedConfirm)
      }

      thrown.code mustBe ErrorCodes.CONFIRM_CODE_NOT_FOUND

      val confirmation = await(codeFuture, 1, TimeUnit.SECONDS)

      val Some(successConfirm) = route(app, FakeRequest(routes.ApplicationController.confirm())
        .withJsonBody(Json.obj("code" -> confirmation.code, "login" -> newIdentity.email.get)))

      status(successConfirm) mustBe OK
    }
  }

}
