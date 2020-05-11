package modules


import com.mohiva.play.silhouette.api.{Environment, Silhouette, SilhouetteProvider}
import de.flapdoodle.embed.mongo.config.{MongodConfigBuilder, Net, RuntimeConfigBuilder}
import de.flapdoodle.embed.mongo.distribution.Version
import de.flapdoodle.embed.mongo.{Command, MongodExecutable, MongodProcess, MongodStarter}
import de.flapdoodle.embed.process.config.IRuntimeConfig
import de.flapdoodle.embed.process.config.io.ProcessOutput
import de.flapdoodle.embed.process.runtime.Network
import javax.inject.Singleton
import models.{JwtEnv, MongoFormats}
import modules.TestSilhouette.{bind, env}
import net.codingwell.scalaguice.ScalaModule
import org.mongodb.scala.{MongoClient, MongoDatabase}
import services.MongoApi

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

object EmbeddedMongoModule extends ScalaModule {
  override def configure(): Unit = {
    bind[MongoApi].to[EmbeddedMongoApi]
  }
}