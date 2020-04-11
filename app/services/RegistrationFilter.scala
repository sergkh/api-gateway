package services

import play.api.libs.json.JsValue
import zio.Task

trait RegistrationFilter {
  def filter(request: JsValue): Task[JsValue]
}
