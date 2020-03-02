package controllers

import java.util.Date

import com.mohiva.play.silhouette.api.LoginInfo
import com.mohiva.play.silhouette.impl.providers.CredentialsProvider
import com.mohiva.play.silhouette.test._
import helpers.AnswerSugar
import models.{AppException, Branch, JwtEnv, User}
import module.{GeneralModule, InitializationModule}
import modules.FakeModule
import modules.FakeModule._
import net.codingwell.scalaguice.ScalaModule
import org.mockito.ArgumentMatchers._
import org.mockito.Mockito._
import org.mockito.MockitoSugar
import org.scalatest.Inside
import org.scalatestplus.play.PlaySpec
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import play.api.http.Status.FORBIDDEN
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json.Json
import play.api.mvc.Results
import play.api.test.Helpers._
import play.api.test._
import play.api.{Application, Mode}
import services.{BranchesService, ExtendedUserService, UserService}

import scala.concurrent.Future

/**
  * @author Yaroslav Derman <yaroslav.derman@gmail.com>.
  *         created on 29.03.2016.
  *
  * Test case for the [[controllers.UserController]] class.
  */
class UserControllerSpec extends PlaySpec with GuiceOneAppPerSuite
  with Results with MockitoSugar with AnswerSugar with Inside {

  val userServiceMock = mock[UserService]
  val branchesMock = mock[BranchesService]
  val extInfoService = mock[ExtendedUserService]

  val usersMockModule = new ScalaModule {
    override def configure(): Unit = {
      bind[UserService].toInstance(userServiceMock)
      bind[BranchesService].toInstance(branchesMock)
      bind[ExtendedUserService].toInstance(extInfoService)
    }
  }


  implicit override lazy val app: Application = new GuiceApplicationBuilder()
    .overrides(FakeModule)
    .overrides(usersMockModule)
    .disable[GeneralModule]
    .disable[InitializationModule]
    .configure("kafka.enabled" -> "false")
    .in(Mode.Test)
    .build()

  "The UsersController" should {

    "return 401 status for unauthorized user" in {
      val undefinedToken = "eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIyLU55a3EyNnBKMlp4MzFPSnZwdnpqdUhnVXNCRkIz" +
        "Sk5kWVlkaUhYOVN3TEcybG45YWdjUG1xbjBSZkhGckhTVHZhZTZmVUhsbGtPc1lWbE5aeW5FQVB4aXJTRkxxVWx3UTZlTldkZ1wvSTRzaTNx" +
        "S1RmVnFJN1VnY1diWFpXN0E9PSIsImlzcyI6ImJ0Yy10b2tlbiIsInNlc3Npb25JZCI6eyIkb2lkIjoiNTdkMTFiZjExNDAwMDAyODAwYTY2" +
        "ODY0In0sImV4cCI6MTQ3MzM2NTE2OSwiaWF0IjoxNDczMzIxOTY5LCJqdGkiOiJjMjJjZmMwNmJmYzdkNWMyMDc0ZmU2ODY1NGM4OGZjYzFl" +
        "ZWQwZjFlZmQ0NzcxMGIxNjAyMzBhMDEwOGNlMzBiNGFjY2VlODdkNjNmMmEzM2RjZjdlYjQzYzE2MTM2N2QzMTJhN2Q4YjBiNWY0N2VhMWQ3" +
        "ODYzYjkxZDJjODU4MWE1NWI2MTE4Y2ZjNTVmNjY0MzZiYjQyMmFlNjAzMzQwN2ZmMzZmN2Y5ZDJhOGRiMWY5ZTNhYmI5ZjI1OWViYWMxMTlk" +
        "MWM3MWU4MTRkZmMwNjM4YTM0OWM2NmE4MDdjYjI5YTkxM2JkN2IxN2I2M2M5MTM2MDFmNWU3MjAzNTJhIn0.FVeXoC4einRyg2n3naZIKYSx" +
        "DUlsREMtcc62ROTy9OQ"

      val Some(result) = route(app, FakeRequest(routes.UserController.get("me"))
        .withHeaders("X-Auth-Token" -> undefinedToken))

      status(result) mustBe UNAUTHORIZED
    }

    "return OK if user exists" in {
      reset(userServiceMock, branchesMock)
      when(userServiceMock.getByAnyIdOpt("user@gmail.com")).thenReturn(Future.successful(Some(identity)))

      val Some(result) = route(app, FakeRequest(routes.UserController.checkExistence("user@gmail.com")))

      status(result) mustBe NO_CONTENT
    }

    "return 404 if user not found" in {
      reset(userServiceMock, branchesMock)
      when(userServiceMock.getByAnyIdOpt("unknown-user@gmail.com")).thenReturn(Future.successful(None))

      assertThrows[AppException] {
        val Some(result) = route(app, FakeRequest(routes.UserController.checkExistence("unknown-user@gmail.com")))
        status(result) mustBe NOT_FOUND
      }
    }

    "return user" in {
      reset(userServiceMock, branchesMock)
      when(userServiceMock.getRequestedUser(any(), any(), any())).thenReturn(Future.successful(identity))

      val Some(result) = route(app, FakeRequest(routes.UserController.get(9763319796470982L.toString))
        .withAuthenticator[JwtEnv](LoginInfo(CredentialsProvider.ID, adminIdentity.email.get)))

      val jsonUser = contentAsJson(result)

      val date = new Date()

      val readUser = jsonUser.as[User].copy(passUpdated = date)
      val original = identity.copy(passHash = "", passUpdated = date)

      readUser mustEqual original
    }

    "update user" in {
      reset(userServiceMock, branchesMock)
      when(userServiceMock.getRequestedUser(any(), any(), any())).thenReturn(Future.successful(newIdentity))

      when(userServiceMock.withPermissions(any())).thenAnswer { user: User =>
        Future.successful(user)
      }

      when(branchesMock.isAuthorized(Branch.ROOT, newIdentity)).thenReturn(Future.successful(true))

      when(userServiceMock.update(any(), any())).thenAnswer { (user: User, b: Boolean) =>
        Future.successful(user)
      }

      val request = FakeRequest(routes.UserController.put(9763319796470982L.toString))
        .withAuthenticator[JwtEnv](LoginInfo(CredentialsProvider.ID, newIdentity.email.get))
        .withJsonBody(Json.obj(
          "email" -> newIdentity.email.get,
          "firstName" -> "New First name",
          "lastName" -> "New Last Name",
          "version" -> 0
        ))

      val Some(result) = route(app, request)

      status(result) mustBe OK
      contentAsJson(result) mustEqual Json.obj(
        "uuid" -> newIdentity.uuid,
        "email" -> newIdentity.email.get,
        "permissions" -> Json.arr("users:edit","users:read"),
        "firstName" -> "New First name",
        "lastName" -> "New Last Name",
        "version" -> 0 // version remains the same, use we mock update
      )

      val updatedUser = newIdentity.copy(
        firstName = Some("New First name"),
        lastName = Some("New Last Name"),
        version = 0
      )

      verify(userServiceMock, times(1)).update(updatedUser, true)
    }

    "assign user to a branch" in {
      reset(userServiceMock, branchesMock)
      val branchId = "test11"
      when(userServiceMock.getRequestedUser(any(), any(), any())).thenReturn(Future.successful(newIdentity))
      when(userServiceMock.withPermissions(any())).thenAnswer { user: User =>
        Future.successful(user)
      }

      // Need root to be able to edit user
      when(branchesMock.isAuthorized(Branch.ROOT, adminIdentity)).thenReturn(Future.successful(true))
      when(branchesMock.isAuthorized(branchId, adminIdentity)).thenReturn(Future.successful(true))

      when(branchesMock.get(branchId)).thenReturn(Future.successful(Some(Branch(
        "test", adminIdentity.uuid, hierarchy = List("test11", "parent"), id = branchId
      ))))

      when(userServiceMock.update(any(), any())).thenAnswer { (user: User, b: Boolean) =>
        Future.successful(user)
      }

      val request = FakeRequest(routes.UserController.put(9763319796470982L.toString))
        .withAuthenticator[JwtEnv](LoginInfo(CredentialsProvider.ID, adminIdentity.email.get))
        .withJsonBody(Json.obj(
          "email" -> newIdentity.email.get,
          "firstName" -> "New First name",
          "lastName" -> "New Last Name",
          "branch" -> branchId,
          "version" -> 0
        ))

      val Some(result) = route(app, request)

      status(result) mustBe OK

      contentAsJson(result) mustEqual Json.obj(
        "uuid" -> newIdentity.uuid,
        "email" -> newIdentity.email.get,
        "permissions" -> Json.arr("users:edit","users:read"),
        "firstName" -> "New First name",
        "lastName" -> "New Last Name",
        "branch" -> branchId,
        "hierarchy" -> Json.arr("test11", "parent"),
        "version" -> 0 // version remains the same, use we mock update
      )

      val updatedUser = newIdentity.copy(
        firstName = Some("New First name"),
        lastName = Some("New Last Name"),
        hierarchy = Seq("test11", "parent"),
        version = 0
      )

      verify(userServiceMock, times(1)).update(updatedUser, true)
    }

    "fail to assign user to an unauthorized branch" in {
      reset(userServiceMock, branchesMock)
      val branchId = "unknow"
      when(userServiceMock.getRequestedUser(any(), any(), any())).thenReturn(Future.successful(newIdentity))
      when(userServiceMock.getByAnyIdOpt(any())).thenReturn(Future.successful(None))
      when(userServiceMock.withPermissions(any())).thenAnswer { u: User =>
        Future.successful(u)
      }

      when(branchesMock.isAuthorized(Branch.ROOT, adminIdentity)).thenReturn(Future.successful(true))
      when(branchesMock.isAuthorized(branchId, adminIdentity)).thenReturn(Future.successful(false))

      val request = FakeRequest(routes.UserController.put(9763319796470982L.toString))
        .withAuthenticator[JwtEnv](LoginInfo(CredentialsProvider.ID, adminIdentity.email.get))
        .withJsonBody(Json.obj(
          "email" -> "vnfsw@mail.com",
          "firstName" -> "New First name",
          "lastName" -> "New Last Name",
          "branch" -> branchId,
          "version" -> 0
        ))

      assertThrows[AppException] {
        val Some(result) = route(app, request)
        status(result) mustBe FORBIDDEN
      }
    }
  }

  "delete a user" in {
    reset(userServiceMock, branchesMock)
    when(userServiceMock.getRequestedUser(any(), any(), any())).thenReturn(future(newIdentity))

    when(branchesMock.isAuthorized(Branch.ROOT, adminIdentity)).thenReturn(Future.successful(true))
    when(userServiceMock.delete(newIdentity)).thenReturn(future(()))
    when(extInfoService.delete(any())).thenReturn(future(Some(Json.obj())))

    val request = FakeRequest(routes.UserController.delete(9763319796470982L.toString, Some("Tired of it")))
      .withAuthenticator[JwtEnv](LoginInfo(CredentialsProvider.ID, adminIdentity.email.get))

    val Some(result) = route(app, request)

    status(result) mustBe NO_CONTENT

    verify(userServiceMock, times(1)).delete(newIdentity)
  }

  "not allow to delete user from another branch" in {
    reset(userServiceMock, branchesMock)
    when(userServiceMock.getRequestedUser(any(), any(), any())).thenReturn(future(newIdentity))

    when(branchesMock.isAuthorized(Branch.ROOT, adminIdentity)).thenReturn(Future.successful(false))
    when(userServiceMock.delete(newIdentity)).thenReturn(future(()))

    val request = FakeRequest(routes.UserController.delete(9763319796470982L.toString, None))
      .withAuthenticator[JwtEnv](LoginInfo(CredentialsProvider.ID, adminIdentity.email.get))

    assertThrows[AppException] {
      val Some(res) = route(app, request)
      status(res) mustEqual FORBIDDEN
    }

    verify(userServiceMock, times(0)).delete(newIdentity)
  }

}
