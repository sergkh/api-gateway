package discovery

import akka.actor.ActorSystem
import akka.testkit.TestKit
import com.typesafe.config.ConfigFactory
import play.api.Configuration
import play.api.libs.json._
import play.api.mvc.Action
import play.api.mvc.Results.Ok
import models._
import events._
import mockws.MockWS
import org.scalatest.{Matchers, WordSpecLike}

class EtcdServicesManagerSpec extends TestKit(ActorSystem("RoutingServiceSpec")) with WordSpecLike with Matchers {

  val json = Json.parse(
    """{
      | "action":"get",
      | "node":{"key":"/services","dir":true,
      |   "nodes":[
      |     {"key":"/services/kafka","value":"{\"prefix\":\"/\", \"Env\": [\"KAFKA_ADVERTISED_HOST_NAME=kafka\", \"ZOOKEEPER_CONNECTION_STRING=zookeeper:2181\", \"KAFKA_ADVERTISED_PORT=9092\"], \"name\": \"kafka\", \"address\": \"kafka:9092\"}","modifiedIndex":255494,"createdIndex":255494},
      |     {"key":"/services/redis","value":"{\"prefix\":\"/\", \"Env\": [\"LOGSPOUT=ignore\"], \"name\": \"redis\"}","modifiedIndex":255495,"createdIndex":255495},
      |     {"key":"/services/oracle","value":"{\"prefix\":\"/\", \"Env\": [ \"PLAY_SECRET=oracle_secret\", \"DB_DRIVER=org.postgresql.Driver\"], \"name\": \"oracle\"}","modifiedIndex":255484,"createdIndex":255484},
      |     {"key":"/services/postgres","value":"{\"prefix\":\"/\", \"Env\": [\"POSTGRES_USER=btcuser\", \"POSTGRES_DB=qwerty\"], \"name\": \"postgres\", \"address\": \"postgres:5432\"}","modifiedIndex":255490,"createdIndex":255490},
      |     {"key":"/services/nginx","value":"{\"prefix\":\"/\", \"Env\": [\"DOMAIN_NAME=test\"], \"name\": \"nginx\", \"address\": \"nginx:443\"}","modifiedIndex":255491,"createdIndex":255491},
      |     {"key":"/services/filestore","value":"{\"prefix\":\"/\", \"Env\": [\"PLAY_SECRET=filestore_secret\", \"MONGO_URL=mongodb://mongo/filestore\"], \"name\": \"filestore\", \"address\": \"filestore:9000\"}","modifiedIndex":255481,"createdIndex":255481},
      |     {"key":"/services/rates","value":"{\"prefix\":\"/\", \"Env\": [\"PLAY_SECRET=rates_secret\", \"KAFKA_BROKERS=kafka:9092\", \"MONGO_URL=mongodb://mongo\", \"MONGO_DB=rates\", \"APILAYER_ENABLED=true\"], \"name\": \"rates\"}","modifiedIndex":255476,"createdIndex":255476},
      |     {"key":"/services/nginx-proxy","value":"{\"prefix\":\"/\", \"name\": \"nginx-proxy\", \"address\": \"nginx-proxy:80\"}","modifiedIndex":255497,"createdIndex":255497},{"key":"/services/scratch","value":"{\"name\": \"scratch\", \"com.docker.stack.namespace\": \"a\", \"Env\": [\"TS_SECRET=\\\"123\\\"\", \"DB_USER=btcuser\", \"TASK_MAX_POOL_SIZE=5\", \"HTTP_DEF_MAX_PER_ROUTE=20\", \"TASK_KEEP_ALIVE=180000\", \"DB_URL=jdbc:postgresql://postgres/scratch\"]}","modifiedIndex":255500,"createdIndex":255500},
      |     {"key":"/services/etcd","value":"{\"prefix\":\"/\", \"name\": \"etcd\", \"address\": \"etcd:2379\"}","modifiedIndex":255499,"createdIndex":255499},
      |     {"key":"/services/registrator","value":"{\"prefix\":\"/\", \"Env\": [\"ETCD_PORT=2379\", \"ETCD_HOST=etcd\"], \"name\": \"registrator\"}","modifiedIndex":255498,"createdIndex":255498},
      |     {"key":"/services/mongo","value":"{\"prefix\":\"/\", \"Env\": [\"LOGSPOUT=ignore\"], \"name\": \"mongo\", \"address\": \"mongo:27017\"}","modifiedIndex":255483,"createdIndex":255483},
      |     {"key":"/services/templator","value":"{\"prefix\":\"/\", \"Env\": [\"PLAY_SECRET=templator_secret\", \"KAFKA_BROKERS=kafka:9092\", \"MONGO_DB=templates\", \"MONGO_URL=mongodb://mongo\"], \"name\": \"templator\"}","modifiedIndex":255492,"createdIndex":255492},
      |     {"key": "/services/cp_nginx","value":"{\"name\": \"cp_nginx\", \"com.docker.stack.namespace\": \"cp\", \"com.docker.stack.image\": \"nginx:1.15-alpine\", \"etcd_ignore\": \"true\", \"address\": \"cp_nginx:80\"}","modifiedIndex": 8,"createdIndex": 8}
      |  ],
      | "modifiedIndex":4,"createdIndex":4}
      |}""".stripMargin)

  val ws = MockWS {
    case ("GET", "http://etcd:2379/v2/keys/services") => Action {
      Ok(json)
    }
    case _ => Action {
      Ok(JsObject(Nil))
    }
  }

  val config = Configuration(ConfigFactory.parseString("""
    etcd.url = "http://etcd:2379"
    etcd.fetchTime = 1 minute
    etcd.skip = []
    play.http.secret.key = "none"
    """)
  )

  val service = new EtcdServicesManager(config, ws, ActorSystem("test"))

  "generate event on service addition" in {
    val newServices = Set(Service("oracle"), Service("templator"), Service("ts"))
    val oldServices = Set(Service("oracle"), Service("ts"))

    service.generateEvents(newServices, oldServices) shouldEqual Seq(
      ServicesListUpdate(newServices.toSeq),
      ServiceDiscovered(Service("templator"))
    )
  }

  "generate event on service removal" in {
    val newServices = Set(Service("oracle"), Service("ts"))
    val oldServices = Set(Service("oracle"), Service("ts"), Service("templator"))

    service.generateEvents(newServices, oldServices) shouldEqual Seq(
      ServicesListUpdate(newServices.toSeq),
      ServiceLost(Service("templator"))
    )
  }

  "generate pair of events on service change" in {
    val newServices = Set(Service("oracle"), Service("ts"), Service("templator", secret = "new_secret"))
    val oldServices = Set(Service("oracle"), Service("ts"), Service("templator"))

    service.generateEvents(newServices, oldServices) shouldEqual Seq(
      ServicesListUpdate(newServices.toSeq),
      ServiceDiscovered(Service("templator", secret = "new_secret")),
      ServiceLost(Service("templator"))
    )
  }

  "ignore services with etcd_ignore flag" in {
    val list = service.parseEtcdConfig(json)
    list.find(_.name == "cp_nginx") shouldBe empty
  }
}
