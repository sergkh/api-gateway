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

@Singleton
class TokenController @Inject()(silh: Silhouette[JwtEnv], conf: Configuration, eventStream: EventsStream)
                               (implicit exec: ExecutionContext) extends BaseController {


  // /auth/realms/$realm/protocol/openid-connect/certs
  def certs(realm: String) = Action.async { request =>

    val keys = Map("key" -> "test")
    
    Ok(Json.obj(
      "keys" -> Json.arr(
        keys.map {
          case (kid, certBytes) => Json.obj("kid" -> kid, "x5c" -> Json.arr(certBytes))
        }
      )
    ))
  }
}