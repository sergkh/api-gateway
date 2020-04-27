package kafka

import javax.inject._
import zio.kafka.producer._
import zio.kafka.serde._
import org.apache.kafka.clients.producer.ProducerRecord
import events.EventsStream

@Singleton
class KafkaEventWriter(eventStream: EventsStream) {
  val producerSettings: ProducerSettings = ProducerSettings(List("localhost:9092"))
  val producer = Producer.make(producerSettings, Serde.string, JsonSerde.eventJsonSerializer)

  

  //val producerRecord: ProducerRecord[Int, String] = new ProducerRecord("my-output-topic", key, newValue)
}
