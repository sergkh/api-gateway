package controllers

import play.api.inject.guice.GuiceApplicationBuilder
import play.api.Mode
import play.api.mvc._
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers

class RegistrationControllerSpec extends AnyWordSpec with Matchers with Results {
  
  // val application = new GuiceApplicationBuilder()
  //     .configure("kafka.enabled" -> false)
  //   // .loadConfig(env => Configuration.load(env))
  //   // .disable[ComponentModule]
  //   // .overrides(bind[Component].to[MockComponent])
  //   .in(Mode.Test)
  //   .build()

  "Registration controller" should {
    "should be valid" in {
      
      // val controller             = new RegistrationController(Helpers.stubControllerComponents())
      //val result: Future[Result] = controller.index().apply(FakeRequest())
      //val bodyText: String       = contentAsString(result)
      //bodyText mustBe "ok"
      succeed
    }
  }


}
