package controllers

import com.nimbusds.jose._
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.jwk._
import models.{AppException, FormValidationException}
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
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import play.api.Application


class TokenControllerSpec extends AnyWordSpec with GuiceOneAppPerSuite
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

  "A token controller" should {
    "return current auth certificates" in {
      val Some(certsResponse) = route(app, FakeRequest(routes.TokenController.authCerts()))

      status(certsResponse) shouldBe OK
      val json = contentAsJson(certsResponse)
      println("Keys response: " + json)
      val keys = (json \ "keys").as[List[JsObject]]      
      keys shouldNot be (empty)
      keys.foreach { k =>
        (k \ "kty").asOpt[String] shouldBe defined
        (k \ "kid").asOpt[String] shouldBe defined
        // (k \ "use").asOpt[String] shouldBe defined
      }
    }

    "require client authentication" in {
      val req = FakeRequest(routes.TokenController.getAccessToken())
        .withJsonBody(Json.obj("grant_type" -> "password"))

      val Some(tokenResponse) = route(app, req)
      
      assertThrows[AppException] {
        status(tokenResponse) shouldBe OK
      }
    }

    "fail for unknown grant type" in {
      val req = FakeRequest(routes.TokenController.getAccessToken())
        .withHeaders(TestEnv.TestClientAuth)
        .withJsonBody(Json.obj("grant_type" -> "passwordless"))

      val Some(tokenResponse) = route(app, req)
      
      assertThrows[FormValidationException[_]] {
        status(tokenResponse) shouldBe OK
      }
    }

    "create access token for a username/password requested in JSON" in {
      val req = FakeRequest(routes.TokenController.getAccessToken())
        .withHeaders(TestEnv.TestClientAuth)
        .withJsonBody(Json.obj(
          "grant_type" -> "password",
          "username" -> "user@mail.test",
          "password" -> "user-password"
        ))

      val Some(tokenResponse) = route(app, req)
      status(tokenResponse) shouldBe OK
      val json = contentAsJson(tokenResponse)
      
      (json \ "token_type").as[String] shouldEqual "Bearer"
      (json \ "expires_in").as[Int] should be > 60
      val token = (json \ "access_token").as[String]
      
      val Some(certsResponse) = route(app, FakeRequest(routes.TokenController.authCerts()))
      val jwtJson = parseAndValidateJwtToken(token, contentAsString(certsResponse))

      (jwtJson \ "id").as[String] shouldEqual "user"
      (jwtJson \ "iss").as[String] shouldEqual "https://localhost/"
      (jwtJson \ "exp").asOpt[Long] shouldBe defined
    }

    "create access token and refresh for a username/password requested in JSON" in {
      val req = FakeRequest(routes.TokenController.getAccessToken())
        .withHeaders(TestEnv.TestClientAuth)
        .withJsonBody(Json.obj(
          "grant_type" -> "password",
          "username" -> "user@mail.test",
          "password" -> "user-password",
          "scope" -> "profile email offline_access"
        ))

      val Some(tokenResponse) = route(app, req)
      status(tokenResponse) shouldBe OK
      val json = contentAsJson(tokenResponse)    
      (json \ "token_type").as[String] shouldEqual "Bearer"
      (json \ "expires_in").as[Int] should be > 60
      (json \ "refresh_token").asOpt[String] shouldBe defined
      (json \ "scope").asOpt[String] shouldEqual Some("profile email offline_access")

      val token = (json \ "access_token").as[String]      
      val Some(certsResponse) = route(app, FakeRequest(routes.TokenController.authCerts()))
      val jwtJson = parseAndValidateJwtToken(token, contentAsString(certsResponse))
      (jwtJson \ "scope").asOpt[String] shouldEqual Some("profile email offline_access")
    }

    def parseAndValidateJwtToken(token: String, keyset: String): JsObject = {
      val keys = JWKSet.parse(keyset)
      
      val jwt = JWSObject.parse(token)

      jwt.getHeader().getAlgorithm() shouldEqual JWSAlgorithm.ES512

      val keyId = jwt.getHeader().toJSONObject().getAsString("kid")
      val key = keys.getKeyByKeyId(keyId)
      val ecKey = key.asInstanceOf[ECKey].toECPublicKey()

      jwt.verify(new ECDSAVerifier(ecKey))

      Json.parse(jwt.getPayload.toJSONObject.toJSONString()).as[JsObject]
    }
  }
}
