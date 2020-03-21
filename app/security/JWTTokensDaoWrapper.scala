package security

import com.mohiva.play.silhouette.api.repositories.AuthenticatorRepository
import com.mohiva.play.silhouette.impl.authenticators.JWTAuthenticator
import services.OAuthService
import utils.Logging
import utils.Responses._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class JWTTokensDaoWrapper(nonOauthStore: JWTTokensDao, oauthStore: OAuthService)
  extends AuthenticatorRepository[JWTAuthenticator] with Logging {

  override def find(id: String): Future[Option[JWTAuthenticator]] = {
    nonOauthStore.find(id).flatMap  {
      case Some(authenticator) => Future.successful(Some(authenticator))
      case None => oauthStore.find(id)
    }
  }

  override def update(authenticator: JWTAuthenticator): Future[JWTAuthenticator] = {
    suitableRepository(authenticator).update(authenticator)
  }

  override def remove(id: String): Future[Unit] = {
    nonOauthStore.remove(id).map { _ => oauthStore.remove(id) }
  }

  override def add(authenticator: JWTAuthenticator): Future[JWTAuthenticator] = {
    suitableRepository(authenticator).add(authenticator)
  }


  private def suitableRepository(authenticator: JWTAuthenticator): AuthenticatorRepository[JWTAuthenticator] = {
    if (authenticator.isOauth) oauthStore else nonOauthStore
  }

}