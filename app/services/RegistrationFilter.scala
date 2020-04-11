package services

import play.api.mvc.RequestHeader
import zio.Task

trait RegistrationFilter {
  def filter(request: RequestHeader): Task[RequestHeader]
}
