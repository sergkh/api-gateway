package controllers

import java.util.concurrent.TimeUnit

import com.impactua.bouncer.commons.models.ResponseCode
import com.impactua.bouncer.commons.models.exceptions.AppException
import com.mohiva.play.silhouette.api.util.PasswordInfo
import com.mohiva.play.silhouette.api.{EventBus, LoginInfo}
import com.mohiva.play.silhouette.impl.providers.CredentialsProvider
import com.typesafe.config.ConfigFactory
import helpers.AnswerSugar
import models.AppEvent.OtpGeneration
import models.User
import module.{GeneralModule, InitializationModule}
import modules.FakeModule
import modules.FakeModule._
import net.codingwell.scalaguice.ScalaModule
import org.mockito.ArgumentMatchers._
import org.mockito.Mockito._
import org.scalatest.mockito.MockitoSugar
import org.scalatestplus.play.PlaySpec
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json.{JsValue, Json}
import play.api.mvc.Results
import play.api.test.FakeRequest
import play.api.test.Helpers._
import play.api.{Application, Configuration, Mode}
import services.impl.RegistrationFiltersChain
import services.{RestrictionService, UserService}

import scala.concurrent.Future

/**
  * @author Yaroslav Derman <yaroslav.derman@gmail.com>.
  *         created on 07.08.2016.
  */
class OpenRegistrationSpec extends PlaySpec
  with GuiceOneAppPerSuite
  with Results
  with MockitoSugar
  with AnswerSugar {

  import helpers.EventBusHelpers._

  private val userServiceMock = {
    val m = mock[UserService]

    when(m.save(any[User]())).thenAnswer { inv =>
      Future.successful(inv.getArguments.head.asInstanceOf[User])
    }

    when(m.updatePassHash(anyString(), any[PasswordInfo]())).thenReturn(Future.successful({}))

    when(m.updateFlags(any[User]())).thenAnswer { inv =>
      Future.successful(inv.getArguments.head.asInstanceOf[User])
    }

    m
  }

  private val restrictionServiceMock = mock[RegistrationFiltersChain]

  val usersMockModule = new ScalaModule {
    override def configure(): Unit = {
      bind[UserService].toInstance(userServiceMock)
      bind[RegistrationFiltersChain].toInstance(restrictionServiceMock)
    }
  }

  implicit override lazy val app: Application = new GuiceApplicationBuilder()
    .loadConfig(Configuration(ConfigFactory.load("test.conf")))
    .overrides(FakeModule)
    .overrides(usersMockModule)
    .disable[GeneralModule].disable[InitializationModule]
    .configure("registration.schema" -> "open", "kafka.enabled" -> "false")
    .in(Mode.Test)
    .build()

  "The '/register' and '/confirm' action" should {
    "create, confirm, login and delete user for open registration schema" in {
      implicit val actorSystem = app.actorSystem
      val codeFuture = catchEvent[OtpGeneration]

      when(userServiceMock.retrieve(LoginInfo("credentials", newIdentity.email.get)))
        .thenReturn(Future.successful(None.asInstanceOf[Option[User]]))

      when(restrictionServiceMock.apply(any())).thenAnswer { inv =>
        Future.successful(inv.getArguments.head.asInstanceOf[JsValue])
      }

      val requestBody = Json.obj("login" -> newIdentity.email.get, "password" -> newIdentity.passHash)
      val Some(result) = route(app, FakeRequest(routes.ApplicationController.register()).withJsonBody(requestBody))

      val otpThrown = the[AppException[_]] thrownBy {
        status(result)
      }

      otpThrown.code mustBe ResponseCode.CONFIRMATION_REQUIRED

      val Some(failedConfirm) = route(app, FakeRequest(routes.ApplicationController.confirm())
        .withJsonBody(Json.obj("code" -> "undefined", "login" -> newIdentity.email.get)))

      val thrown = the[AppException[_]] thrownBy {
        status(failedConfirm)
      }

      thrown.code mustBe ResponseCode.CONFIRM_CODE_NOT_FOUND

      val confirmation = await(codeFuture, 1, TimeUnit.SECONDS)

      when(userServiceMock.retrieve(LoginInfo(CredentialsProvider.ID, newIdentity.email.get)))
        .thenReturn(Future.successful(Some(newIdentity)))

      val Some(successConfirm) = route(app, FakeRequest(routes.ApplicationController.confirm())
        .withJsonBody(Json.obj("code" -> confirmation.code, "login" -> newIdentity.email.get)))

      status(successConfirm) mustBe OK

    }
  }

}
