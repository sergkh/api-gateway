package module

import com.google.inject.multibindings.Multibinder
import com.google.inject.name.Names
import net.codingwell.scalaguice.ScalaModule
import play.api.{Configuration, Environment}
import services.impl.{OpenRegistrationService, ReferralRegistrationService, RegistrationFiltersChain}
import services.{ExtendedUserInfoService, _}

/**
  * @author Yaroslav Derman <yaroslav.derman@gmail.com>.
  *         created on 07.08.2016.
  */
class RegistrationModule(environment: Environment,
                         configuration: Configuration) extends ScalaModule {

  override def configure(): Unit = {
    bind[UserIdentityService].to[UserService]
    bind[ExtendedUserInfoService].to[ExtendedUserService]

    val filtersMultibinder = Multibinder.newSetBinder(binder, classOf[RegistrationFilter])

    configuration.get[Seq[String]]("registration.filters").map { className =>
      val bindingClass = environment.classLoader.loadClass(className).asSubclass(classOf[RegistrationFilter])
      filtersMultibinder.addBinding().to(bindingClass)
    }

    bind[RegistrationFiltersChain].asEagerSingleton()

    configuration.getOptional[String]("registration.schema").getOrElse("open") match {
      case "open" => bind[RegistrationService].to[OpenRegistrationService].asEagerSingleton()
      case "referral" => bind[RegistrationService].to[ReferralRegistrationService].asEagerSingleton()
      case _ => bind[RegistrationService].to[OpenRegistrationService].asEagerSingleton()
    }
  }




}