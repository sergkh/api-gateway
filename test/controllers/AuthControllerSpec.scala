package controllers

import models.FormValidationException
import module.InitializationModule
import modules.{EmbeddedMongoModule, TestInitializationModule}
import org.mockito.{ArgumentMatchersSugar, MockitoSugar}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.Mode
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json._
import play.api.mvc.Results
import play.api.test.FakeRequest
import play.api.test.Helpers._
import services.TestEnv
import models.AppException
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import play.api.Application

class AuthControllerSpec extends AnyWordSpec with GuiceOneAppPerSuite
                          with Matchers with Results with MockitoSugar with ArgumentMatchersSugar {

  override def fakeApplication(): Application = {
    new GuiceApplicationBuilder()
   .configure("play.cache.createBoundCaches" -> false)
   .overrides(EmbeddedMongoModule)
   .overrides(TestInitializationModule)
   .disable[InitializationModule]
   .in(Mode.Test)
   .build()
  }

  "An auth controller" should {
    "require client ID and response type" in {
      val req = FakeRequest(routes.AuthController.authenticate("google"))

      val Some(redirectResp) = route(app, req)
      assertThrows[FormValidationException[_]] {
        status(redirectResp) shouldEqual PERMANENT_REDIRECT
      }
    }

    "fail to unknown auth provider" in {
      val req = FakeRequest(routes.AuthController.authenticate("doodle")).withJsonBody(Json.obj(
        "client_id" -> TestEnv.KnownClientId,
        "response_type" -> "code"
      ))

      val Some(redirectResp) = route(app, req)
      assertThrows[AppException] {
        status(redirectResp) shouldEqual PERMANENT_REDIRECT
      }
    }


    "redirect to social auth provider" ignore {
      val req = FakeRequest(routes.AuthController.authenticate("google")).withJsonBody(Json.obj(
        "client_id" -> TestEnv.KnownClientId,
        "response_type" -> "code"
      ))

      val Some(redirectResp) = route(app, req)
      status(redirectResp) shouldEqual PERMANENT_REDIRECT

    }
  }
}
