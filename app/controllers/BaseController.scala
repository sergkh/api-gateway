package controllers

import zio._
import akka.stream.scaladsl.Source
import akka.util.ByteString
import com.mohiva.play.silhouette.api.actions.SecuredRequest
import models.JwtEnv
import play.api.Logger
import play.api.http.{ContentTypeOf, ContentTypes, Writeable}
import play.api.i18n.Lang
import play.api.libs.json.{JsArray, JsValue, Json}
import play.api.mvc._
import services.StreamedProxyRequest

import scala.language.implicitConversions
import scala.concurrent.Future

import utils.TaskExt._

/**
  * Created by sergeykhruschak on 12/9/15.
  */
trait BaseController extends InjectedController {

  val log = Logger(this.getClass.getName)

  implicit def contentTypeOfSeqJsValue(implicit codec: Codec): ContentTypeOf[Seq[JsValue]] = {
    ContentTypeOf[Seq[JsValue]](Some(ContentTypes.JSON))
  }

  implicit def writeableOfSeqJsValue(implicit codec: Codec): Writeable[Seq[JsValue]] = {
    Writeable { list =>
      codec.encode(Json.stringify(JsArray(list)))
    }
  }

  implicit def fromTask(task: Task[Result]): Future[Result] = task.toUnsafeFuture

//  implicit def securedRequestToProxyReq(req: SecuredRequest[JwtEnv, JsValue]): StreamedProxyRequest = {
//    StreamedProxyRequest(req, Some(req.identity), Option(Source.single(ByteString(Json.stringify(req.body).getBytes))))
//  }

  @deprecated("See https://www.playframework.com/documentation/2.6.x/MessagesMigration26", "2.6.0")
  implicit def request2lang(implicit request: RequestHeader): Lang = play.api.i18n.Lang.defaultLang
}
