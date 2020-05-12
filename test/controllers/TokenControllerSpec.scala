package controllers

import module.InitializationModule
import modules.{EmbeddedMongoModule, TestSilhouette}
import org.mockito.{ArgumentMatchersSugar, MockitoSugar}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.test.Helpers._
import play.api.Mode
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.mvc.Results
import play.api.test.FakeRequest

class TokenControllerSpec extends AnyWordSpec
                          with Matchers with Results with MockitoSugar with ArgumentMatchersSugar {

  val app = new GuiceApplicationBuilder()
   .overrides(TestSilhouette)
   .overrides(EmbeddedMongoModule)
   .disable[InitializationModule]
   .in(Mode.Test)
   .build()

  "A token controller" should {    
    "return current auth certificates in" in {
      val Some(certsResponse) = route(app, FakeRequest(routes.TokenController.authCerts()))

      status(certsResponse) shouldBe OK
      val json = contentAsJson(certsResponse)
      println(json)
    }

  }
}
