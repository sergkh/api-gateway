package controllers

import org.scalatest.wordspec.AsyncWordSpec
import org.scalatest.matchers.should.Matchers
import play.api.mvc.Results
import org.mockito.MockitoSugar
import org.mockito.ArgumentMatchersSugar

import models._
import events._
import zio._
import play.api.test.FakeRequest
import play.api.libs.json.Json
import play.api.test.Helpers

class TokenControllerSpec extends AsyncWordSpec with Matchers with Results with MockitoSugar with ArgumentMatchersSugar {
  
}
