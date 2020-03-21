package security

import java.{util => ju}

import com.mohiva.play.silhouette.api.LoginInfo
import com.mohiva.play.silhouette.api.crypto.AuthenticatorEncoder
import com.mohiva.play.silhouette.api.exceptions.{AuthenticatorException, AuthenticatorInitializationException, AuthenticatorRetrievalException}
import com.mohiva.play.silhouette.api.repositories.AuthenticatorRepository
import com.mohiva.play.silhouette.api.services.AuthenticatorService
import com.mohiva.play.silhouette.api.util.{Clock, ExtractableRequest, IDGenerator}
import com.mohiva.play.silhouette.impl.authenticators.JWTAuthenticator._
import com.mohiva.play.silhouette.impl.authenticators.JWTAuthenticatorService.ID
import com.mohiva.play.silhouette.impl.authenticators.{JWTAuthenticator, JWTAuthenticatorService, JWTAuthenticatorSettings}
import org.joda.time.DateTime
import pdi.jwt._
import play.api.libs.json._
import play.api.mvc.RequestHeader
import utils.Logging
import utils.RichJson._

import scala.concurrent.{ExecutionContext, Future}
import scala.language.{implicitConversions, postfixOps}
import scala.util.{Failure, Success, Try}

/**
  * @author Yaroslav Derman
  */
class CustomJWTAuthenticatorService(settings: JWTAuthenticatorSettings,
                                    repository: Option[AuthenticatorRepository[JWTAuthenticator]],
                                    authenticatorEncoder: AuthenticatorEncoder,                                    
                                    idGenerator: IDGenerator,
                                    keysManager: KeysManager,
                                    val clock: Clock)(implicit val exContext: ExecutionContext)
  extends JWTAuthenticatorService(
    settings,
    repository,
    authenticatorEncoder,
    idGenerator,
    clock)(exContext) with Logging {

  final val algorithm = JwtAlgorithm.ES512

  override def retrieve[B](implicit request: ExtractableRequest[B]): Future[Option[JWTAuthenticator]] = {
    Future.fromTry(retrieveToken(request)).flatMap {
      case Some(token) => deserializeJwt(token, authenticatorEncoder, settings) match {
        case Success(authenticator) => repository.fold(Future.successful(Option(authenticator)))(_.find(authenticator.id))
        case Failure(e) =>
          logger.info(e.getMessage, e)
          Future.successful(None)
      }
      case None => Future.successful(None)
    }.recover {
      case e => throw new AuthenticatorRetrievalException(AuthenticatorService.RetrieveError.format(ID), e)
    }
  }

  /**
   * Creates a new JWT for the given authenticator and return it. If a backing store is defined, then the
   * authenticator will be stored in it.
   *
   * @param authenticator The authenticator instance.
   * @param request       The request header.
   * @return The serialized authenticator value.
   */
  override def init(authenticator: JWTAuthenticator)(implicit request: RequestHeader): Future[String] = {
    repository.fold(Future.successful(authenticator))(_.add(authenticator)).map { a =>
      serializeJwt(a, authenticatorEncoder, settings)
    }.recover {
      case e => throw new AuthenticatorInitializationException(AuthenticatorService.InitError.format(ID, authenticator), e)
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

  private def serializeJwt(authenticator: JWTAuthenticator,
                        authenticatorEncoder: AuthenticatorEncoder,
                        settings: JWTAuthenticatorSettings): String = {
    val (keyId, privKey) = keysManager.currentAuthPrivKey

    val subject = Json.toJson(authenticator.loginInfo).toString()

    val header = JwtHeader(Some(algorithm), typ = Some("JWT"), keyId = Some(keyId))
    val claim = JwtClaim(
      content = authenticator.customClaims.map(_.without("aud")).map(Json.stringify).getOrElse("{}"),
      issuer = Some(settings.issuerClaim),
      subject = Some(authenticatorEncoder.encode(subject)),
      audience = authenticator.customClaims.flatMap(js => (js \ "aud").asOpt[Set[String]]),
      expiration = Some(authenticator.expirationDateTime.getMillis / 1000),
      issuedAt = Some(authenticator.lastUsedDateTime.getMillis / 1000),
      jwtId = Some(authenticator.id)
    )

    Jwt.encode(header, claim, privKey)
  }

    /**
   * Unserializes the authenticator.
   *
   * @param str                  The string representation of the authenticator.
   * @param authenticatorEncoder The authenticator encoder.
   * @param settings             The authenticator settings.
   * @return An authenticator on success, otherwise a failure.
   */
  def deserializeJwt(
    token: String,
    authenticatorEncoder: AuthenticatorEncoder,
    settings: JWTAuthenticatorSettings): Try[JWTAuthenticator] = {

    for {
      keyId     <- getKeyId(token)
      publicKey <- Try(keysManager.authPubKey(keyId).getOrElse(throw new RuntimeException("Unknown key ID")))
      claim     <- Jwt.decode(token, publicKey, List(algorithm))
    } yield {
      if (!claim.issuer.contains(settings.issuerClaim)) throw new RuntimeException("Token issued by a different issuer")
      val subject = authenticatorEncoder.decode(claim.subject.getOrElse{throw new RuntimeException("Subject is not present")})

      val loginInfo = Json.parse(subject).as[LoginInfo]
      
      JWTAuthenticator(
        id = claim.jwtId.getOrElse(throw new RuntimeException("ID is not present")),
        loginInfo = loginInfo,
        lastUsedDateTime = new DateTime(claim.issuedAt.get * 1000),
        expirationDateTime = new DateTime(claim.expiration.get * 1000),
        idleTimeout = settings.authenticatorIdleTimeout,
        customClaims = Some(Json.parse(claim.content).as[JsObject])
      )
    }
  }

  private def getKeyId(token: String): Try[String] = Try {
    token.split("\\.").headOption.flatMap { header =>
      Try((Json.parse(ju.Base64.getDecoder.decode(header)) \ "kid").as[String]).toOption
    }.getOrElse {
      throw new AuthenticatorException(JWTAuthenticatorService.InvalidJWTToken.format(ID, token))
    }
  }
}
