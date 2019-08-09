package module

import com.google.inject.AbstractModule
import net.codingwell.scalaguice.ScalaModule
import services.{BranchesService, MongoBranchesService}

class BranchesModule extends AbstractModule with ScalaModule {

  override def configure(): Unit = {
    bind[BranchesService].to[MongoBranchesService]
  }
}
