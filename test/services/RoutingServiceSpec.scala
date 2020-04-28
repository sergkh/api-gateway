package services

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
import services.ServicesManager
import org.scalatest.time.{Seconds, Span}
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.matchers.should.Matchers
import utils.TaskExt._

class ServicesManagerSpec extends TestKit(ActorSystem("ServicesManagerSpec"))
  with AnyWordSpecLike
  with Matchers
  with MockWSHelpers
  with Eventually
  with MockitoSugar {
 
  val json: JsValue = Json.parse(
    """{
      |"openapi": "3.0.0",
      |"paths":{
      |   "/":{"get":{"tags":["Rates"],"summary":"Get list of rate","parameters":[{"in":"query","name":"offset","type":"integer","format":"int32","required":false},{"in":"query","name":"limit","type":"integer","format":"int32","required":false}],"responses":{"200":{"description":"list of rates","schema":{"type":"array","items":{"$ref":"#/definitions/PairRateList"}}}}}},
      |   "/{pair}":{
      |     "get":{"tags":["Rates"],"summary":"Return rate","parameters":[{"in":"path","name":"pair","type":"string","required":true}],"responses":{"200":{"description":"Rate","schema":{"$ref":"#/definitions/Rate"}}}},"post":{"tags":["Rates"],"summary":"Add rate","parameters":[{"in":"path","name":"pair","type":"string","required":true,"description":"Currencies pair in format 'EURUSD' or '978-840'"},{"in":"body","name":"body","schema":{"$ref":"#/definitions/Rate"}}],"responses":{"200":{"description":"Success, returns added item","schema":{"$ref":"#/definitions/Rate"}},"409":{"description":"Conflict item already exists, use update instead","schema":{"$ref":"#/definitions/errorResponse"}}},"consumes":["application/json"]},
      |     "put":{"tags":["Rates"],"consumes":["application/json"],"parameters":[{"in":"path","name":"pair","type":"string","required":true,"description":"Currencies pair in format 'EURUSD' or '978-840'"},{"in":"body","name":"body","schema":{"$ref":"#/definitions/Rate"}}],"responses":{"200":{"description":"Success, returns updated rate","schema":{"$ref":"#/definitions/Rate"}},"404":{"description":"No rate found for such currencies combination","schema":{"$ref":"#/definitions/errorResponse"}}}},
      |     "delete":{"tags":["Rates"],"parameters":[{"in":"path","name":"pair","type":"string","required":true,"description":"Currencies pair in format 'EURUSD' or '978-840'"}],"responses":{"200":{"description":"Success, returns removed rate","schema":{"$ref":"#/definitions/Rate"}},"404":{"description":"No rate found for such currencies combination","schema":{"$ref":"#/definitions/errorResponse"}}}}
      |   }
      |}
      |}""".stripMargin)

  val rates = Service("rates", "/rates", "secret", "http://localhost:9004", "http://localhost:9004/docs/api.json")

  val ws = MockWS {
    case ("GET", "http://localhost:9004/docs/api.json") => Action {
      Ok(json)
    }
    case _ => Action {
      Ok(JsObject(Nil))
    }
  }

  val bus = new ZioEventPublisher()

  val config = Configuration(ConfigFactory.parseString("""
    swagger {
      host = "localhost:9000"
      appName = "Default"
      schema = ["http"]
      path = "/"
      update = 1 minute
    }
  """)
  )

  val env = Environment.simple()

  val router = new ServicesManager(ws, config, bus, system, env)

  "Router" should {
    "return rates service" in {

      bus.publish(ServiceDiscovered(rates)).toUnsafeFuture

      eventually (timeout(Span(1, Seconds))) {
        router.matchService("/rates/USDEUR") shouldEqual Some(rates)
        router.matchService("/rates") shouldEqual Some(rates)
      }

    }
  }
}
