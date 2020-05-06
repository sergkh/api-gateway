package forms

import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers
import play.api.data._
import play.api.data.Forms._
import play.api.data.validation._
import play.api.test.FakeRequest
import play.api.mvc.MultipartFormData
import play.api.libs.json.Json

class FormConstraintsSpec extends AnyWordSpec with Matchers {
  import FormConstraints._

  "An map form" should {
    "map text map to itself" in {
      fieldMap(text).bind(Map(
        "field1" -> "test",
        "field2" -> "field2"
      )) shouldEqual Right(Map("field1" -> "test", "field2" -> "field2"))
      
    }

    "parse map from sub field" in {
      val form = tuple(
        "root" -> text,
        "sub" -> fieldMap(text)
      )

      form.bind(Map(
        "root" -> "test",
        "sub.1" -> "field1",
        "sub.2" -> "field2",
        "sub.field3" -> "field3",
      )) shouldEqual Right((
        "test", 
        Map("1" -> "field1", "2" -> "field2", "field3" -> "field3")
      ))
    }

    "parse json sub fields" in {
      val form = Form(tuple(
        "root" -> text,
        "sub" -> fieldMap(text)
      ))

      val req = FakeRequest("POST", "/").withBody(Json.parse("""{
        "root": "test", "sub": { "one": "field1", "two": "field2" }
      }"""))
      
      form.bindFromRequest()(req).get shouldEqual (
        "test", 
        Map("one" -> "field1", "two" -> "field2")
      )
    }

  }
  
}
