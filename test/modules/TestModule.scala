package modules

import java.util.UUID

import services.{ProxyService, SessionsService}
import akka.actor.ActorSystem
import com.mohiva.play.silhouette.api._
import com.mohiva.play.silhouette.api.crypto.Base64AuthenticatorEncoder
import com.mohiva.play.silhouette.api.services.{AuthenticatorService, IdentityService}
import com.mohiva.play.silhouette.api.util.Clock
import com.mohiva.play.silhouette.impl.authenticators._
import com.mohiva.play.silhouette.impl.providers.CredentialsProvider
import com.mohiva.play.silhouette.impl.util.SecureRandomIDGenerator
import com.mohiva.play.silhouette.test._
import events.EventsStream
import helpers.Context
import models.JwtEnv
import net.codingwell.scalaguice.ScalaModule
import org.mockito.MockitoSugar
import play.modules.reactivemongo.ReactiveMongoApi
import security.{CustomJWTAuthenticatorService, KeysManager}
import service.fakes.{TestEventsStream, TestProxy, TestSessionsService}
import utils.CustomEventBus

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.reflect.runtime.universe._

/**
  * A fake Guice module.
  *
  * @author Sergey Khruschak <sergey.khruschak@gmail.com>
  *         Created on 2/4/16.
  */
object TestModule extends ScalaModule with Context with MockitoSugar {

  implicit val ctx = scala.concurrent.ExecutionContext.Implicits.global

  val actorSystem = ActorSystem.create()
  val eventBus = new CustomEventBus(actorSystem)
  private val db = mock[ReactiveMongoApi]

  /**
    * A Silhouette fake environment.
    */
  //login info -> user
  implicit val env: Environment[JwtEnv] = new CustomFakeEnvironment[JwtEnv](Seq(
    LoginInfo(CredentialsProvider.ID, identity.email.get) -> identity,
    LoginInfo(CredentialsProvider.ID, newIdentity.email.get) -> newIdentity,
    LoginInfo(CredentialsProvider.ID, adminIdentity.email.get) -> adminIdentity,
    LoginInfo(CredentialsProvider.ID, "user@gmail.com") -> identity
  ))


  override def configure() {
    bind[ActorSystem].toInstance(actorSystem)
    bind[EventBus].toInstance(eventBus)
    bind[Environment[JwtEnv]].toInstance(env)
    bind[ProxyService].to[TestProxy]
    bind[SessionsService].to[TestSessionsService]
    bind[ReactiveMongoApi].toInstance(db)
    bind[EventsStream].to[TestEventsStream]
  }

  case class CustomFakeEnvironment[E <: Env](
          identities: Seq[(LoginInfo, E#I)],
          requestProviders: Seq[RequestProvider] = Seq(),
          eventBus: EventBus = eventBus)(implicit val executionContext: ExecutionContext, tt: TypeTag[E#A])
    extends Environment[E] {

    /**
      * The identity service implementation.
      */
    val identityService: IdentityService[E#I] = new FakeIdentityService[E#I](identities: _*)

    /**
      * The authenticator service implementation.
      */
    val authenticatorService: AuthenticatorService[E#A] = CustomFakeAuthenticatorService[E#A]()

  }

  object CustomFakeAuthenticatorService {

    /**
      * Creates a new fake authenticator for the given authenticator type.
      *
      * @tparam T The type of the authenticator.
      * @return A fully configured authenticator instance.
      */
    def apply[T <: Authenticator: TypeTag](): AuthenticatorService[T] = {
      (typeOf[T] match {
        case t if t <:< typeOf[SessionAuthenticator] => FakeSessionAuthenticatorService()
        case t if t <:< typeOf[CookieAuthenticator] => FakeCookieAuthenticatorService()
        case t if t <:< typeOf[BearerTokenAuthenticator] => FakeBearerTokenAuthenticatorService()
        case t if t <:< typeOf[JWTAuthenticator] => FakeCustomJWTAuthenticatorService()
        case t if t <:< typeOf[DummyAuthenticator] => FakeDummyAuthenticatorService()
      }).asInstanceOf[AuthenticatorService[T]]
    }
  }

  case class FakeCustomJWTAuthenticatorService() extends CustomJWTAuthenticatorService(
    JWTAuthenticatorSettings(sharedSecret = UUID.randomUUID().toString, authenticatorIdleTimeout = Some(15.minute)),
    None,
    new Base64AuthenticatorEncoder(),
    new SecureRandomIDGenerator(),
    new KeysManager(),
    Clock())


}
