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

class UserControllerSpec extends AnyWordSpec with GuiceOneAppPerSuite
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

  "An user controller" should {

    lazy val adminToken = {
      val req = FakeRequest(routes.TokenController.getAccessToken())
        .withHeaders(TestEnv.TestClientAuth)
        .withJsonBody(Json.obj(
          "grant_type" -> "password",
          "username" -> "admin@mail.test",
          "password" -> "admin-password",
          "scope" -> "users:edit"
        ))

      val Some(tokenResponse) = route(app, req)
      (contentAsJson(tokenResponse) \ "access_token").as[String]  
    }

    "create user by admin" in {
      val req = FakeRequest(routes.UserController.add)
      .withHeaders("Authorization" -> s"Bearer $adminToken")
      .withJsonBody(Json.obj(
        "email" -> "test-user@mail.com",
        "password" -> "B^w3Ger#gYt4y1F6",
        "extra" -> Json.obj("test" -> "data")
      ))

      val Some(redirectResp) = route(app, req)
      println(contentAsString(redirectResp))
      status(redirectResp) shouldEqual OK
    }

  }
}
