package controllers

//scalastyle:off public.methods.have.type

import akka.actor.ActorSystem
import javax.inject.{Inject, Singleton}
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.util.{ByteString, Timeout}
import com.mohiva.play.silhouette.api.Silhouette
import com.mohiva.play.silhouette.api.actions.UserAwareRequest
import models.{AppException, ErrorCodes, JwtEnv, Service, User}
import play.api.Configuration
import play.api.cache.SyncCacheApi
import play.api.libs.json.Json
import play.api.libs.streams.Accumulator
import play.api.mvc.BodyParser
import security.{ConfirmationCodeService, WithAnyPermission}
import services.{ProxyService, RoutingService, StreamedProxyRequest}
import ErrorCodes._
import utils.Responses._

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import events.EventsStream
import utils.RichJson._
import java.security._
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.security.spec.ECGenParameterSpec
import org.bouncycastle.asn1.x500.X500Name
import java.{util => ju}
import java.math.BigInteger
import java.security.cert.X509Certificate
import scala.compat.Platform
import services.TokensService

@Singleton
class TokenController @Inject()(silh: Silhouette[JwtEnv], tokensService: TokensService)
                               (implicit exec: ExecutionContext) extends BaseController {

  def authCerts = Action { _ =>

    val keys = tokensService.authCertificates
    
    Ok(Json.obj(
      "keys" -> Json.arr(
        keys.map {
          case (kid, cert) => Json.obj("kid" -> kid, "x5c" -> Json.arr(
            ju.Base64.getEncoder.encodeToString(cert.getEncoded)
          ))
        }
      )
    ))
  }
}

