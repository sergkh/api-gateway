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

    lazy val adminToken = login("admin@mail.test", "admin-password", "users:edit users:read")    

    "create a user by admin" in {
      val req = FakeRequest(routes.UserController.add)
      .withHeaders(auth(adminToken))
      .withJsonBody(Json.obj(
        "email" -> "test-user@mail.com",
        "password" -> "B^w3Ger#gYt4y1F6",
        "extra" -> Json.obj("test" -> "data")
      ))

      val Some(resp) = route(app, req)
      status(resp) shouldEqual OK
      val user = contentAsJson(resp)
      (user \ "id").asOpt[String] shouldBe defined
      (user \ "password").asOpt[String] shouldBe empty
      (user \ "email").asOpt[String] shouldEqual Some("test-user@mail.com")
      (user \ "flags").asOpt[List[String]] shouldEqual Some(List("unconfirmed_email"))
      (user \ "extra" \ "test").asOpt[String] shouldEqual Some("data")
      (user \ "version").asOpt[Int] shouldEqual Some(0)

      // Should fail to login without confirmed email
      assertThrows[AppException] {
        login("test-user@mail.com", "B^w3Ger#gYt4y1F6", "email")
      }
    }

    "not allow retrieving user without permission" in {
      val getReq = FakeRequest(routes.UserController.get("test-user@mail.com"))
        .withHeaders(auth(login("admin@mail.test", "admin-password", "users:edit")))
      val Some(getResp) = route(app, getReq)
      status(getResp) shouldEqual Forbidden
    }

    "allow admin to retrieve and update user" in {
      val getReq = FakeRequest(routes.UserController.get("test-user@mail.com")).withHeaders(auth(adminToken))
      val Some(getResp) = route(app, getReq)
      status(getResp) shouldEqual OK
      val user = contentAsJson(getResp)



      // val req = FakeRequest(routes.UserController.add)
      // .withHeaders(auth(adminToken))
      // .withJsonBody(Json.obj(
      //   "email" -> "test-user@mail.com",
      //   "password" -> "B^w3Ger#gYt4y1F6",
      //   "extra" -> Json.obj("test" -> "data")
      // ))
    }

  }

  private def login(username: String, password: String, scope: String): String = {
    val req = FakeRequest(routes.TokenController.getAccessToken())
        .withHeaders(TestEnv.TestClientAuth)
        .withJsonBody(Json.obj(
          "grant_type" -> "password",
          "username" -> username,
          "password" -> password,
          "scope" -> scope
        ))

      val Some(tokenResponse) = route(app, req)
      (contentAsJson(tokenResponse) \ "access_token").as[String] 
  }

  def auth(token: String): (String, String) =  "Authorization" -> s"Bearer $token"
}
