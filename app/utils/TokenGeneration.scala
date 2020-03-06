package utils

import net.ceedubs.ficus.Ficus._
import net.ceedubs.ficus.readers.ArbitraryTypeReader._
import net.ceedubs.ficus.readers.EnumerationReader._
import com.mohiva.play.silhouette.impl.authenticators._
import com.mohiva.play.silhouette.crypto._
import com.mohiva.play.silhouette.api.crypto._
import com.mohiva.play.silhouette.api.util._
import play.api.test._
import play.api.mvc.request._
import security._
import models._

import scala.concurrent.ExecutionContext.Implicits.global
import play.api.mvc._
import com.mohiva.play.silhouette.api._

import scala.collection.JavaConverters._
import scala.concurrent._
import scala.concurrent.duration._
import play.api.libs.json._
import play.api.mvc.Results._
import com.nimbusds.jose.JWSObject
import com.nimbusds.jose.crypto.MACVerifier
import com.nimbusds.jwt.JWTClaimsSet
import org.joda.time.DateTime

import scala.io.Source
import scala.util.Try

object TokenGeneration {

  val config = com.typesafe.config.ConfigFactory.load()
  val settings = config.as[JWTAuthenticatorSettings]("silhouette.authenticator")
  val crypter = new JcaCrypter(config.as[JcaCrypterSettings]("silhouette.authenticator.crypter"))
  val encoder = new CrypterAuthenticatorEncoder(crypter)
  val idGenerator = new com.mohiva.play.silhouette.impl.util.SecureRandomIDGenerator()
  val ETERNAL_TOKEN_TTL = config.as[FiniteDuration]("oauth.ttl")

  val authSrvc = new CustomJWTAuthenticatorService(settings, None, encoder, idGenerator, Clock())

  class RequestHeaderMock(
                           override val connection: RemoteConnection = null,
                           override val method: String = "GET",
                           override val target: RequestTarget = RequestTarget("http://localhost/", "/", Map()),
                           override val version: String = "1.1",
                           override val headers: Headers = null,
                           override val attrs: play.api.libs.typedmap.TypedMap = null) extends RequestHeader

  implicit val mockHeader = new RequestHeaderMock()

  def parseFile(file: String): Seq[(String, String)] = {
    val futures = Source.fromFile(file, "UTF-8").getLines().filterNot(_.trim.isEmpty).toList.map { oldToken =>
      parseToken(oldToken).map(convertToken).getOrElse(Future.successful(s"Error parsing token: $oldToken")) map { newToken =>
        oldToken -> newToken
      }
    }

    Await.result(Future.sequence(futures), 1 minute)
  }

  def parseToken(str: String): Try[JWTClaimsSet] = Try {
    JWTClaimsSet.parse(JWSObject.parse(str).getPayload.toJSONObject)
  }

  def convertToken(claims: JWTClaimsSet): Future[String] = {
    val sub = Json.parse(Base64.decode(claims.getSubject))
    val loginInfo = LoginInfo(
      (sub \ "providerID").as[String],
      (sub \ "providerKey").as[String]
    )

    val tokenClaims = TokenClaims(
      claims.getStringClaim("userId"),
      Option(claims.getStringClaim("userEmail")),
      Option(claims.getStringClaim("userPhone")),
      claims.getStringClaim("clientId"),
      claims.getStringListClaim("permissions").asScala.toList
    )

    for {
      authenticator <- authSrvc.create(loginInfo).map(_.copy(
        idleTimeout = None,
        expirationDateTime = new DateTime(claims.getExpirationTime),
        customClaims = Some(Json.toJson(tokenClaims).as[JsObject])
      ))
      value <- authSrvc.init(authenticator)
    } yield {
      value
    }
  }

  def verifyToken(t: String): Boolean = {
    Try {
      val verifier = new MACVerifier(settings.sharedSecret)
      val jwsObject = JWSObject.parse(t)
      if (!jwsObject.verify(verifier)) {
        throw new IllegalArgumentException("Fraudulent JWT token: " + t)
      }

      JWTClaimsSet.parse(jwsObject.getPayload.toJSONObject)
    }.isSuccess
  }

}
