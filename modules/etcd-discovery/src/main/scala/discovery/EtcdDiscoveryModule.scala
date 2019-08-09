package discovery
import com.google.inject.AbstractModule
import net.codingwell.scalaguice.ScalaModule

class EtcdDiscoveryModule extends AbstractModule with ScalaModule {
  override def configure(): Unit = {
    bind[EtcdServicesManager].asEagerSingleton()
  }
}
