package module

import com.google.inject.multibindings.Multibinder
import net.codingwell.scalaguice.ScalaModule
import play.api.{Configuration, Environment}
import services.{RegistrationFilter, RegistrationFiltersChain}

class RegistrationModule(environment: Environment, configuration: Configuration) extends ScalaModule {

  override def configure(): Unit = {
    val filtersMultibinder = Multibinder.newSetBinder(binder, classOf[RegistrationFilter])

    configuration.get[Seq[String]]("registration.filters").map { className =>
      val bindingClass = environment.classLoader.loadClass(className).asSubclass(classOf[RegistrationFilter])
      filtersMultibinder.addBinding().to(bindingClass)
    }

    bind[RegistrationFiltersChain].asEagerSingleton()
  }
}