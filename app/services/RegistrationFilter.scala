package services

import zio.Task
import play.mvc.Http.RequestHeader

trait RegistrationFilter {
  def filter(request: RequestHeader): Task[RequestHeader]
}
