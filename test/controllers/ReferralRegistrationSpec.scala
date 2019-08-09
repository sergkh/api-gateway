package controllers

import java.util.concurrent.TimeUnit

import com.impactua.bouncer.commons.models.ResponseCode
import com.impactua.bouncer.commons.models.exceptions.AppException
import com.impactua.bouncer.commons.utils.RichJson._
import com.mohiva.play.silhouette.api.util.PasswordInfo
import com.mohiva.play.silhouette.api.{EventBus, LoginInfo}
import com.mohiva.play.silhouette.impl.providers.CredentialsProvider
import com.typesafe.config.ConfigFactory
import helpers.AnswerSugar
import helpers.EventBusHelpers.catchEvent
import models.AppEvent.OtpGeneration
import models.User
import module.{GeneralModule, InitializationModule}
import modules.FakeModule
import modules.FakeModule._
import net.codingwell.scalaguice.ScalaModule
import org.mockito.ArgumentMatchers._
import org.mockito.Mockito.when
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalatest.SequentialNestedSuiteExecution
import org.scalatest.mockito.MockitoSugar
import org.scalatestplus.play.PlaySpec
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json.{JsObject, JsValue, Json}
import play.api.mvc.Results
import play.api.test.FakeRequest
import play.api.test.Helpers.{contentAsJson, _}
import play.api.{Application, Configuration, Mode}
import services.impl.RegistrationFiltersChain
import services.{ExtendedUserInfoService, RestrictionService, UserService}

import scala.concurrent.Future

/**
  * @author Yaroslav Derman <yaroslav.derman@gmail.com>.
  *         created on 07.08.2016.
  */
class ReferralRegistrationSpec extends PlaySpec
  with GuiceOneAppPerSuite
  with Results
  with SequentialNestedSuiteExecution
  with MockitoSugar
  with AnswerSugar {

  private val userServiceMock = {
    val m = mock[UserService]

    when(m.save(any[User]())).thenAnswer { inv =>
      Future.successful(inv.getArguments.head.asInstanceOf[User])
    }

    when(m.updatePassHash(anyString(), any[PasswordInfo]())).thenReturn(Future.successful({}))

    when(m.updateFlags(any[User]())).thenAnswer{ inv =>
      Future.successful(inv.getArguments.head.asInstanceOf[User])
    }

    m
  }

  private val restrictionServiceMock = mock[RegistrationFiltersChain]

  private val extendedUserServiceMock = {
    val m = mock[ExtendedUserInfoService]

    when(m.create(any[JsObject]())).thenAnswer { inv =>
      Future.successful(inv.getArgument[JsObject](0))
    }

    when(m.retrieve(Json.obj("_id" -> referralIdentity.uuid), Seq():_*)).thenReturn(Future.successful(None))

    m
  }

  val userModule = new ScalaModule {
    override def configure(): Unit = {
      bind[ExtendedUserInfoService].toInstance(extendedUserServiceMock)
      bind[UserService].toInstance(userServiceMock)
      bind[RegistrationFiltersChain].toInstance(restrictionServiceMock)
    }
  }

  implicit override lazy val app: Application = new GuiceApplicationBuilder()
    .loadConfig(Configuration(ConfigFactory.load("test.conf")))
    .overrides(FakeModule)
    .overrides(userModule)
    .disable[GeneralModule].disable[InitializationModule]
    .configure("registration.schema" -> "referral", "kafka.enabled" -> "false")
    .in(Mode.Test)
    .build()

  "The '/register' and '/confirm' action" should {
    "create and confirm user for referral registration schema" in {
      implicit val as = app.actorSystem
      val requestBody = Json.obj("login" -> referralIdentity.email.get, "password" -> referralIdentity.passHash)

      when(userServiceMock.retrieve(LoginInfo("credentials", referralIdentity.email.get)))
        .thenReturn(Future.successful(None.asInstanceOf[Option[User]]))

      when(restrictionServiceMock.apply(any())).thenAnswer { inv =>
        Future.successful(inv.getArguments.head.asInstanceOf[JsValue])
      }

      val Some(result) = route(app, FakeRequest(routes.ApplicationController.register()).withJsonBody(requestBody))
      val codeFuture = catchEvent[OtpGeneration]

      val otpThrown = the[AppException[_]] thrownBy {
        status(result)
      }

      otpThrown.code mustBe ResponseCode.CONFIRMATION_REQUIRED    

      val confirmation = await(codeFuture, 1, TimeUnit.SECONDS)

      when(userServiceMock.retrieve(LoginInfo(CredentialsProvider.ID, referralIdentity.email.get)))
        .thenReturn(Future.successful(Some(referralIdentity)))

      val Some(successConfirm) = route(app, FakeRequest(routes.ApplicationController.confirm())
        .withJsonBody(Json.obj("code" -> confirmation.code, "login" -> referralIdentity.email.get)))

      status(successConfirm) mustBe OK
    }
  }

  "Extended info request" ignore {
    "be present for registered user" ignore {

      val Some(loginResult) = route(app, FakeRequest(routes.ApplicationController.authenticate())
        .withJsonBody(Json.obj("login" -> referralIdentity.email.get, "password" -> referralIdentity.passHash)))

      status(loginResult) mustBe OK

      contentType(loginResult) mustBe Some("application/json")
      headers(loginResult).get("X-Auth-Token") mustBe defined

      val json = contentAsJson(loginResult)
      (json \ "token").asOpt[String] mustBe defined

      val Some(extendedInfoResult) = route(app, FakeRequest(routes.UserController.retrieveExtendedInfo("me"))
        .withHeaders("X-Auth-Token" -> (json \ "token").as[String]))

      status(extendedInfoResult) mustBe OK
      val extendedJson = contentAsJson(extendedInfoResult)

      (extendedJson \ "invitationCode").asOpt[String] mustBe defined
    }
  }

}
