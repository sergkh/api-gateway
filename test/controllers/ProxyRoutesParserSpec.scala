package controllers

import models.Service
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers
import play.core.routing.{DynamicPart, PathPattern, StaticPart}
import utils.ProxyRoutesParser._

class ProxyRoutesParserSpec extends AnyWordSpec with Matchers {

  "Proxy router" should {

    "parse one line urls" in {
      parse("login") shouldEqual PathPattern(Seq(StaticPart("login")))
    }

    "parse static only urls" in {
      parse("users/recovery/confirm") shouldEqual PathPattern(Seq(StaticPart("users/recovery/confirm")))
    }

    "parse dynamic urls ending with static part" in {
      parse("users/:userId/exists") shouldEqual PathPattern(Seq(StaticPart("users/"), DynamicPart("userId", """[^/]+""", true), StaticPart("/exists")))
    }

    "parse mixed dynamic urls ending with dynamic part" in {
      parse("users/:userId/accounts/:accountId") shouldEqual PathPattern(Seq(StaticPart("users/"), DynamicPart("userId", """[^/]+""",true), StaticPart("/accounts/"), DynamicPart("accountId", """[^/]+""", true)))
    }
  }

  "Proxy swagger router" should {
    "parse swagger urls" in {
      parse("/users/{userId}/accounts", '{') shouldEqual PathPattern(Seq(StaticPart("users/"), DynamicPart("userId", """[^/]+""",true), StaticPart("/accounts")))
    }
  }

  "Proxy routes builder" should {

    "build simple static urls" in {
      buildPath(parse("login"), Map()) shouldEqual "login"
    }

    "build dynamic urls" in {
      val params = Map[String, Either[Throwable, String]](
        "userId" -> Right[Throwable, String]("test")
      )

      buildPath(parse("users/:userId/exists"), params) shouldEqual "users/test/exists"
    }

    "build dynamic urls with multiple parts" in {
      val params = Map[String, Either[Throwable, String]](
        "userId" -> Right[Throwable, String]("mail@mail.com"),
        "accountId" -> Right[Throwable, String]("23464234")
      )

      buildPath(parse("users/:userId/accounts/:accountId"), params) shouldEqual "users/mail@mail.com/accounts/23464234"
    }
  }

  "Service" should {
    "build url" in {
      Service("reports", "/reports", "test123", "http://reports:9000", "http://reports:9000/docs/api.json")
        .makeUrl("/reports/datasources?limit=100&offset=0") shouldEqual Some(
        "http://reports:9000/datasources?limit=100&offset=0"
      )
    }
  }

}


