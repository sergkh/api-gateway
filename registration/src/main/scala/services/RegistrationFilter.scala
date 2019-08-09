package services

import play.api.libs.json.JsValue

import scala.concurrent.Future

trait RegistrationFilter {
  def filter(request: JsValue): Future[JsValue]
}
