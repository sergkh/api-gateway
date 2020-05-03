package controllers

import zio._
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.Mode
import play.api.mvc._
import org.scalatest.wordspec.AsyncWordSpec
import org.scalatest.matchers.should.Matchers
import org.mockito.{MockitoSugar, ArgumentMatchersSugar}
import models.conf.RegistrationConfig
import models.conf.ConfirmationConfig
import com.mohiva.play.silhouette.password.BCryptPasswordHasher
import services._
import com.mohiva.play.silhouette.api.util.PasswordHasherRegistry
import java.security.KeyStore
import security.KeysManager
import models.conf.CryptoConfig
import TestEnv._
import java.{util => ju}
import play.api.test.FakeRequest
import play.api.libs.json.Json
import play.api.test.Helpers
import models.AppException
import models.User

class RegistrationControllerSpec extends AsyncWordSpec with Matchers with Results with MockitoSugar with ArgumentMatchersSugar {
  import Helpers._

  val userServiceMock = mock[UserService]

  def controller(userService: UserService = userServiceMock, conf: RegistrationConfig = RegistrationConfig()): RegistrationController = {

    val c = new RegistrationController(
      userService, 
      confirmationService,
      conf,
      confirmationConf,
      new ZioEventStream(),
      passHasherRegistry,
      new RegistrationFiltersChain(ju.Collections.emptySet())
    )

    c.setControllerComponents(Helpers.stubControllerComponents())
    c
  }  

  "Registration controller" should {
    "fail if email already exists" in {
      val userService = mock[UserService]
      when(userService.exists("user@mail.com")) thenReturn Task.succeed(true)
      
      val request = Json.parse("""{"email":"user@mail.com", "password":"B^w3Ger#gYt4y1F6"}""")      
      val result = controller(userService).register()(FakeRequest("POST", "/register").withBody(request))

      recoverToSucceededIf[AppException] {
        result map { r => fail("Should fail") }
      }      
    }

    "fail if phone already exists" in {
      val userService = mock[UserService]
      when(userService.exists("user@mail.com")) thenReturn Task.succeed(false)
      when(userService.exists("+31973452233")) thenReturn Task.succeed(true)
      
      val request = Json.parse("""{
        "email":"user@mail.com", 
        "phone":"+31973452233", 
        "password":"B^w3Ger#gYt4y1F6"}""")
      val result = controller(userService).register()(FakeRequest("POST", "/register").withBody(request))

      recoverToSucceededIf[AppException] {
        result map { r => fail("Should fail") }
      }      
    }

    "fail if phone is required but not set" in {
      val userService = mock[UserService]
      val request = Json.parse("""{"email":"user@mail.com", "password":"B^w3Ger#gYt4y1F6"}""")
      
      val config = RegistrationConfig(requiredFields = List("email", "phone"))
      val result = controller(userService, config).register()(FakeRequest("POST", "/register").withBody(request))

      recoverToSucceededIf[AppException] {
        result map { r => fail("Should fail") }
      }      
    }

    "fail if password is required but not set" in {
      val userService = mock[UserService]      
      val request = Json.parse("""{"email":"user@mail.com"}""")
      val result = controller(userService).register()(FakeRequest("POST", "/register").withBody(request))

      recoverToSucceededIf[AppException] {
        result map { r => fail("Should fail") }
      }      
    }

    "allow to register" in {
      val userService = mock[UserService]
      when(userService.exists("user@mail.com")) thenReturn Task.succeed(false)
      when(userService.save(*)).thenAnswer{ u: User => Task.succeed(u) }
      
      val request = Json.parse("""{"email":"user@mail.com", "password":"B^w3Ger#gYt4y1F6"}""")
      val result = controller(userService).register()(FakeRequest("POST", "/register").withBody(request))


      result map { r => 
        val json = contentAsJson(result) 
        json shouldEqual Json.parse(s"""{"id":"${(json \ "id").as[String]}","email":"user@mail.com","flags":["unconfirmed_email"],"version":0}""")
        succeed 
      }
    }
  }
}
