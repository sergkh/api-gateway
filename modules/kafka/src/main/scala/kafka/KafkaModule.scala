package kafka

import com.google.inject.AbstractModule
import net.codingwell.scalaguice.ScalaModule

class KafkaModule extends AbstractModule with ScalaModule {
  override def configure(): Unit = {
    bind[KafkaEventWriter].asEagerSingleton()
  }
}
