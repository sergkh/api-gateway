
import javax.inject.{Inject, Singleton}
import de.flapdoodle.embed.mongo.distribution.Version
import de.flapdoodle.embed.mongo.{Command, MongodProcess, MongodExecutable, MongodStarter}
import de.flapdoodle.embed.mongo.config.{Net, MongodConfigBuilder}
import de.flapdoodle.embed.process.runtime.Network
import de.flapdoodle.embed.process.config.IRuntimeConfig
import de.flapdoodle.embed.process.config.io.ProcessOutput
import de.flapdoodle.embed.mongo.config.RuntimeConfigBuilder
import services.MongoApi
import org.mongodb.scala.{MongoDatabase, MongoCollection, MongoClient}
import scala.reflect.ClassTag
import models.MongoFormats

@Singleton
class EmbeddedMongoApi extends MongoApi {
  final val port = 47836

  private val runtimeConfig = new RuntimeConfigBuilder()
      .defaults(Command.MongoD)
      .processOutput(ProcessOutput.getDefaultInstanceSilent())
      .build()

  lazy val db: MongoDatabase = {
    start()
    val mongoClient = MongoClient(s"mongodb://127.0.0.1:$port")
    mongoClient.getDatabase("tests").withCodecRegistry(MongoFormats.registry)
  }

  protected def start(): MongodProps = {
    val mongodExe = mongodExec("127.0.0.1", port, Version.V4_0_2, runtimeConfig)
    MongodProps(mongodExe.start(), mongodExe)
  }

  protected def stop(mongodProps: MongodProps ) = {
    Option(mongodProps).foreach( _.mongodProcess.stop() )
    Option(mongodProps).foreach( _.mongodExe.stop() )
  }

  private def mongodExec(host: String, port: Int, version: Version, runtimeConfig: IRuntimeConfig): MongodExecutable =
      MongodStarter.getInstance(runtimeConfig).prepare(
          new MongodConfigBuilder()
              .version(version)
              .net(new Net(host, port, Network.localhostIsIPv6()))
              .build()
      )

  sealed case class MongodProps(mongodProcess: MongodProcess, mongodExe: MongodExecutable)
}
