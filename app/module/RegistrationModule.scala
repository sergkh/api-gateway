package module

import com.google.inject.multibindings.Multibinder
import net.codingwell.scalaguice.ScalaModule
import play.api.{Configuration, Environment}
import services.{OpenRegistrationService, RegistrationFilter, RegistrationFiltersChain, RegistrationService, UserExistenceService, UserService}

/**
  * @author Yaroslav Derman <yaroslav.derman@gmail.com>.
  * created on 07.08.2016.
  */
class RegistrationModule(environment: Environment, configuration: Configuration) extends ScalaModule {

  override def configure(): Unit = {
    bind[UserExistenceService].to[UserService]

    val filtersMultibinder = Multibinder.newSetBinder(binder, classOf[RegistrationFilter])

    configuration.get[Seq[String]]("registration.filters").map { className =>
      val bindingClass = environment.classLoader.loadClass(className).asSubclass(classOf[RegistrationFilter])
      filtersMultibinder.addBinding().to(bindingClass)
    }

    bind[RegistrationFiltersChain].asEagerSingleton()
    bind[RegistrationService].to[OpenRegistrationService].asEagerSingleton()
  }




}