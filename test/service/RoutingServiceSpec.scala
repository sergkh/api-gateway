package service

import akka.actor.ActorSystem
import akka.testkit.TestKit
import com.typesafe.config.ConfigFactory
import mockws.{MockWS, MockWSHelpers}
import events.ServiceDiscovered
import models.Service
import org.mockito.MockitoSugar
import org.scalatest.concurrent.Eventually
import play.api.{Configuration, Environment}
import play.api.libs.json.{JsObject, JsValue, Json}
import play.api.mvc.Results._
import services.RoutingService
import org.scalatest.time.{Seconds, Span}
import service.fakes.TestEventsStream
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.matchers.should.Matchers

class RoutingServiceSpec extends TestKit(ActorSystem("RoutingServiceSpec"))
  with AnyWordSpecLike
  with Matchers
  with MockWSHelpers
  with Eventually
  with MockitoSugar {

  val json: JsValue = Json.parse(
    """{
      |"paths":{
      |   "/":{"get":{"tags":["Rates"],"summary":"Get list of rate","parameters":[{"in":"query","name":"offset","type":"integer","format":"int32","required":false},{"in":"query","name":"limit","type":"integer","format":"int32","required":false}],"responses":{"200":{"description":"list of rates","schema":{"type":"array","items":{"$ref":"#/definitions/PairRateList"}}}}}},
      |   "/{pair}":{
      |     "get":{"tags":["Rates"],"summary":"Return rate","parameters":[{"in":"path","name":"pair","type":"string","required":true}],"responses":{"200":{"description":"Rate","schema":{"$ref":"#/definitions/Rate"}}}},"post":{"tags":["Rates"],"summary":"Add rate","parameters":[{"in":"path","name":"pair","type":"string","required":true,"description":"Currencies pair in format 'EURUSD' or '978-840'"},{"in":"body","name":"body","schema":{"$ref":"#/definitions/Rate"}}],"responses":{"200":{"description":"Success, returns added item","schema":{"$ref":"#/definitions/Rate"}},"409":{"description":"Conflict item already exists, use update instead","schema":{"$ref":"#/definitions/errorResponse"}}},"consumes":["application/json"]},
      |     "put":{"tags":["Rates"],"consumes":["application/json"],"parameters":[{"in":"path","name":"pair","type":"string","required":true,"description":"Currencies pair in format 'EURUSD' or '978-840'"},{"in":"body","name":"body","schema":{"$ref":"#/definitions/Rate"}}],"responses":{"200":{"description":"Success, returns updated rate","schema":{"$ref":"#/definitions/Rate"}},"404":{"description":"No rate found for such currencies combination","schema":{"$ref":"#/definitions/errorResponse"}}}},
      |     "delete":{"tags":["Rates"],"parameters":[{"in":"path","name":"pair","type":"string","required":true,"description":"Currencies pair in format 'EURUSD' or '978-840'"}],"responses":{"200":{"description":"Success, returns removed rate","schema":{"$ref":"#/definitions/Rate"}},"404":{"description":"No rate found for such currencies combination","schema":{"$ref":"#/definitions/errorResponse"}}}}
      |   }
      |},
      |"definitions":{
      |   "errorResponse":{
      |       "properties":{"error":{"type":"string","description":"Error code","enum":["INTERNAL_SERVER_ERROR","NOT_ENOUGH_MONEY","ACCOUNT_NOT_FOUND","INVALID_AMOUNT","TRANSACTION_NOT_FOUND","USER_NOT_FOUND","INVALID_TRANSACTION_DIRECTION","INVALID_TRANSACTION_ORDER_FIELD","INVALID_ORDER_TYPE","INVALID_REQUEST","GATEWAY_NOT_FOUND","GATEWAY_SERVER_ERROR","INVALID_GATEWAY_CONFIGURATION","INVALID_TRANSACTION_ACCOUNT","APPLICATION_NOT_FOUND","TEMPORARY_TOKEN_NOT_FOUND","TOKEN_CLAIMS_NOT_FOUND","INVALID_TOKEN_CLAIMS","EXTERNAL_ACCOUNT_INFO_NOT_FOUND","UNKNOWN_SERVICE","INVALID_ACCOUNT_CURRENCY","GATEWAY_SERVICE_EXISTS","GATEWAY_SERVICE_NOT_FOUND","ACCOUNTS_ARE_SAME","TEMPLATE_NOT_FOUND","WORKFLOW_NOT_FOUND","INVALID_APPLICATION_SECRET","INVALID_TOKEN","TOKEN_NOT_FOUND","IDENTIFIER_REQUIRED","BLOCKED_USER","USER_NOT_CONFIRMED","INVALID_USER_CREDENTIALS","USER_ALREADY_EXISTS","CONFIRM_CODE_NOT_FOUND","ACCESS_DENIED","INVALID_IDENTIFIER","USER_IDENTIFIER_EXISTS","INVALID_PASSWORD","NEWS_NOT_FOUND","CURRENCY_RATE_NOT_FOUND","EXTERNAL_SERVICE_UNAVAILABLE","CONFIRMATION_REQUIRED","ENTITY_NOT_FOUND","TRANSACTION_CANT_BE_CANCELED"]},"message":{"type":"string","description":"Error message description"},"timestamp":{"type":"integer","description":"Request timestamp"},"required":["login"]}},"Rate":{"type":"object","properties":{"rate":{"type":"number","format":"double","description":"Rate value"}}},"PairRate":{"type":"object","properties":{"currencyPair":{"type":"object","properties":{"from":{"type":"string","description":"Source currency name"},"to":{"type":"string","description":"Destination currency name"}}},"rate":{"type":"number","format":"double","description":"Rate value"}}},"PairRateList":{"type":"object","properties":{"items":{"type":"array","description":"array of currency pairs","items":{"$ref":"#/definitions/PairRate"}},"count":{"type":"integer","description":"objects count"}}}},"tags":[{"name":"routes"},{"name":"Rates","description":"Currency exchange rates management"}],"host":"localhost","info":{"title":"Rates API","version":"1.0.0"},"schemes":["https"],"produces":["application/json"],"swagger":"2.0","consumes":["application/json"]
      | }""".stripMargin)

  val rates = Service("rates", "/rates", "secret", "http://localhost:9004", "http://localhost:9004/docs/api.json")

  val ws = MockWS {
    case ("GET", "http://localhost:9004/docs/api.json") => Action {
      Ok(json)
    }
    case _ => Action {
      Ok(JsObject(Nil))
    }
  }

  val bus = new TestEventsStream()

  val config = Configuration(ConfigFactory.parseString("""
    swagger {
      host = "localhost:9000"
      appName = "Default"
      schema = ["http"]
      path = "/"
      update = 1 minute
      swaggerV3 = false
    }
  """)
  )

  val env = Environment.simple()

  val router = new RoutingService(ws, config, bus, system, env)

  "Router" should {
    "return rates service" in {

      bus.publish(ServiceDiscovered(rates))

      eventually (timeout(Span(1, Seconds))) {
        router.matchService("/rates/USDEUR") shouldEqual Some(rates)
        router.matchService("/rates") shouldEqual Some(rates)
      }

    }
  }
}
