package kafka

import javax.inject._
import zio.kafka.producer._
import zio.kafka.serde._
import zio.blocking._
import org.apache.kafka.clients.producer.ProducerRecord
import events._
import utils.TaskExt._
import zio.kafka.consumer.Offset
import play.api.Configuration
import play.api.Logger

@Singleton
class KafkaEventWriter @Inject() (eventStream: EventsStream, conf: Configuration) {
  val log      = Logger(this.getClass.getName)
  val enabled  = conf.get[Boolean]("kafka.enabled")

  def init(): Unit = {
    if (!enabled) {
      log.info("Kafka publisher is disabled")
    } else {
      val servers = conf.get[String]("kafka.servers").split(",").map(_.trim).filter(_.isEmpty)
      
      log.info(s"Kafka servers: ${servers.mkString(", ")}")

      val producerSettings = ProducerSettings(servers.toList)
      val producer = Producer.make(producerSettings, Serde.string, JsonSerde.eventJsonSerializer) ++ Blocking.live

      eventStream.stream.flatMap { stream =>
        stream.mapConcat { evt => 
          eventTopicAndKey(evt).map { case (topic, key) => 
            new ProducerRecord(topic, key, evt)
          }
        }.mapM { record => Producer.produce[Any, String, Event](record) }
        .runDrain
        .provideSomeLayer(producer)
      }.unsafeRun

    }
  }

  def eventTopicAndKey(evt: Event): Option[(String, String)] = Option(evt).collect {
    case u: UserEvent => "users" -> u.user.id
    case r: RoleEvent => "roles" -> r.role.role
    case c: ClientEvent => "clients" -> c.client.id
    case c: BranchEvent => "branches" -> c.branch.id
  }

  init()
}