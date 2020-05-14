package controllers

import zio._
import play.api.Mode
import play.api.mvc._
import org.scalatest.wordspec.AsyncWordSpec
import org.scalatest.matchers.should.Matchers
import org.mockito.{ArgumentMatchersSugar, MockitoSugar}
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

import akka.stream.testkit.NoMaterializer
import play.api.test.FakeRequest
import play.api.libs.json.Json
import play.api.test.Helpers
import models._
import events._

import scala.concurrent.Await
import scala.concurrent.duration._

class RegistrationControllerSpec extends AsyncWordSpec with Matchers with Results with MockitoSugar with ArgumentMatchersSugar {
  import Helpers._

  val userServiceMock = mock[UserService]

  def controller(
      userService: UserService = userServiceMock, 
      conf: RegistrationConfig = RegistrationConfig(),
      events: EventsStream = new ZioEventStream(),
      clientAuth: ClientAuthenticator = testClientAuthenticator): RegistrationController = {

    val c = new RegistrationController(
      userService, 
      clientAuth,
      confirmationService,
      conf,
      confirmationConf,
      events,
      passHasherRegistry,
      new RegistrationFiltersChain(ju.Collections.emptySet())
    )

    c.setControllerComponents(Helpers.stubControllerComponents())
    c
  }  

  "Registration controller" should {
    "fail without client credentials" in {
      val userService = mock[UserService]
  
      val request = Json.parse("""{"email":"user@mail.com", "password":"B^w3Ger#gYt4y1F6"}""")      
      val result = controller(userService).register()(FakeRequest("POST", "/register").withBody(request))

      recoverToSucceededIf[AppException] {
        result map { r => fail("Should fail") }
      }      
    }

    "fail if email already exists" in {
      val userService = mock[UserService]
      when(userService.exists("user@mail.com")) thenReturn Task.succeed(true)
      
      val request = FakeRequest("POST", "/register").withHeaders(TestClientAuth).withBody(Json.parse("""{"email":"user@mail.com", "password":"B^w3Ger#gYt4y1F6"}"""))
      val result = controller(userService).register()(request)

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

      val result = controller(userService).register()(FakeRequest("POST", "/register").withHeaders(TestClientAuth).withBody(request))

      recoverToSucceededIf[AppException] {
        result map { r => fail("Should fail") }
      }      
    }

    "fail if phone is required but not set" in {
      val userService = mock[UserService]
      val request = Json.parse("""{"email":"user@mail.com", "password":"B^w3Ger#gYt4y1F6"}""")
      
      val config = RegistrationConfig(requiredFields = List("email", "phone"))
      val result = controller(userService, config).register()(FakeRequest("POST", "/register").withHeaders(TestClientAuth).withBody(request))

      recoverToSucceededIf[AppException] {
        result map { r => fail("Should fail") }
      }      
    }

    "fail if password is required but not set" in {
      val userService = mock[UserService]      
      val request = Json.parse("""{"email":"user@mail.com"}""")
      val result = controller(userService).register()(FakeRequest("POST", "/register").withHeaders(TestClientAuth).withBody(request))

      recoverToSucceededIf[AppException] {
        result map { r => fail("Should fail") }
      }      
    }

    "forbid unsafe characters in fields" in {
      val userService = mock[UserService]
      
      val request = Json.parse("""{"email":"user@mail.com", "password":"B^w3Ger#gYt4y1F6", "extra": {"field":"${hello}"}}""")
      val result = controller().register()(FakeRequest("POST", "/register").withHeaders(TestClientAuth).withBody(request))
      
      recoverToSucceededIf[FormValidationException[_]] {
        result map { r => fail("Should fail") }
      }
    }

    "allow user to register" in {
      val userService = mock[UserService]
      when(userService.exists("user@mail.com")) thenReturn Task.succeed(false)
      when(userService.save(*)).thenAnswer{ u: User => Task.succeed(u) }

      val events = new ZioEventStream()
      val signupEvent = EventBusHelpers.expectEvent[Signup](events)
      val otpEvent = EventBusHelpers.expectEvent[OtpGenerated](events)
      
      val request = Json.parse("""{"email":"user@mail.com", "password":"B^w3Ger#gYt4y1F6", "extra": {"field":"data"}}""")
      val result = controller(userService, events = events).register()(FakeRequest("POST", "/register").withHeaders(TestClientAuth).withBody(request))
      
      val json = contentAsJson(result)

      json shouldEqual Json.parse(s"""{
        "id":"${(json \ "id").as[String]}",
        "email":"user@mail.com",
        "flags":["unconfirmed_email"],
        "extra":{"field":"data"},
        "version":0
      }""")

      Await.result(signupEvent, 1 second)
      Await.result(otpEvent, 1 second)

      succeed
    }
    
    "allow user to register without password and fields if nothing is required" in {
      val userService = mock[UserService]
      when(userService.exists("user@mail.com")) thenReturn Task.succeed(false)
      when(userService.save(*)).thenAnswer{ u: User => Task.succeed(u) }
      
      val config = RegistrationConfig(requirePassword = false)
      val request = Json.parse("""{}""")
      val result = controller(userService, config).register()(FakeRequest("POST", "/register").withHeaders(TestClientAuth).withBody(request))
      
      result map { r => 
        val json = contentAsJson(result) 
        json shouldEqual Json.parse(s"""{"id":"${(json \ "id").as[String]}", "version":0}""")
        succeed 
      }
    }
  }
}
