package security

import com.impactua.bouncer.commons.utils.Logging
import com.mohiva.play.silhouette.api.crypto.AuthenticatorEncoder
import com.mohiva.play.silhouette.api.exceptions.AuthenticatorRetrievalException
import com.mohiva.play.silhouette.api.repositories.AuthenticatorRepository
import com.mohiva.play.silhouette.api.services.AuthenticatorService.RetrieveError
import com.mohiva.play.silhouette.api.util.{Clock, ExtractableRequest, IDGenerator}
import com.mohiva.play.silhouette.impl.authenticators.JWTAuthenticator._
import com.mohiva.play.silhouette.impl.authenticators.JWTAuthenticatorService.ID
import com.mohiva.play.silhouette.impl.authenticators.{JWTAuthenticator, JWTAuthenticatorService, JWTAuthenticatorSettings}

import scala.concurrent.{ExecutionContext, Future}
import scala.language.{implicitConversions, postfixOps}
import scala.util.{Failure, Success, Try}
import scala.concurrent.duration._

/**
  * @author Yaroslav Derman <yaroslav.derman@gmail.com>.
  */
class CustomJWTAuthenticatorService(settings: JWTAuthenticatorSettings,
                                    repository: Option[AuthenticatorRepository[JWTAuthenticator]],
                                    authenticatorEncoder: AuthenticatorEncoder,
                                    idGenerator: IDGenerator,
                                    val clock: Clock)(implicit val exContext: ExecutionContext)
  extends JWTAuthenticatorService(
    settings,
    repository,
    authenticatorEncoder,
    idGenerator,
    clock)(exContext) with Logging {

  final val OAUTH_MIN_DURATION_MS = 1.day.toMillis

  override def retrieve[B](implicit request: ExtractableRequest[B]): Future[Option[JWTAuthenticator]] = {
    Future.fromTry(retrieveToken(request)).flatMap {
      case Some(token) => unserialize(token, authenticatorEncoder, settings) match {
        case Success(authenticator) =>
          repository.fold(Future.successful(Option(authenticator)))(_.find(authenticator.id)) map {
            case Some(oauth) if oauth.isTimedOut && isOauthToken(oauth) =>
              // OAuth tokens are long term and do not have idle timeout
              Some(oauth.copy(idleTimeout = None))
            case other =>
              other
          }
        case Failure(e) =>
          logger.info(e.getMessage, e)
          Future.successful(None)
      }
      case None => Future.successful(None)
    }.recover {
      case e => throw new AuthenticatorRetrievalException(RetrieveError.format(ID), e)
    }
  }


  /**
    * Retrieves the authenticator for defined token.
    *
    * If a backing store is defined, then the authenticator will be validated against it.
    *
    * @param token The token value.
    * @return Some authenticator or None if no authenticator could be found in request.
    */
  def retrieveByValue(token: String): Future[Option[JWTAuthenticator]] = {
    unserialize(token, authenticatorEncoder, settings) match {
      case Success(authenticator) =>
        repository.fold(Future.successful(Option(authenticator)))(_.find(authenticator.id))
      case Failure(e) =>
        logger.info(e.getMessage, e)
        Future.successful(None)
    }
  }

  private def retrieveToken[B](request: ExtractableRequest[B]) = {
    Try(
      request.extractString(settings.fieldName, settings.requestParts).orElse {
        request.extractString("Authorization", settings.requestParts).map(_.split(" ")).filter {
          case Array(schema, _) if schema.equalsIgnoreCase("bearer") => true
          case _ => false
        } map {
          case Array(_, token) => token
        }
      }
    )
  }

  /**
    * Heuristics to determine if authenticator is OAuth.
    * It's enough to check that it's a long term token.
    */
  private def isOauthToken(oauth: JWTAuthenticator): Boolean = {
    oauth.expirationDateTime.getMillis - oauth.lastUsedDateTime.getMillis > OAUTH_MIN_DURATION_MS
  }

}
