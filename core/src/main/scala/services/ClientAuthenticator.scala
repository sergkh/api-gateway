package services

import zio._
import utils.TaskExt._
import models.{AppException, ErrorCodes }

trait ClientAuthenticator {
  def authenticateClient(clientId: String, clientSecret: String): Task[Boolean]

  def authenticateClientOrFail(creds: (String, String)): Task[Unit] = 
    authenticateClientOrFail(creds._1, creds._2)

  def authenticateClientOrFail(clientId: String, clientSecret: String): Task[Unit] = {
    authenticateClient(clientId, clientSecret).flatMap { 
      case true => Task.unit
      case false => Task.fail(AppException(ErrorCodes.ACCESS_DENIED, "Wrong client secret"))
    }
  }
}
