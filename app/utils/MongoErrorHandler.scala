package utils

import models.{AppException, ErrorCodes}
import reactivemongo.core.errors.DatabaseException
import ErrorCodes._
/**
  * Created by faiaz on 19.10.16.
  */
object MongoErrorHandler extends Logging {

  private val existsCodeEntity = Seq(11000, 11001, 10054, 10056, 10058, 10107, 13435, 13436)
  private val notfoundCodeEntity = Seq(10057, 15845, 16550)

  def processError[T]: PartialFunction[Throwable, T] = {
    case ex: DatabaseException if ex.code.exists(existsCodeEntity.contains) => throw AppException(ErrorCodes.ALREADY_EXISTS, "Entity already exists")
    case ex: DatabaseException if ex.code.exists(notfoundCodeEntity.contains) => throw AppException(ErrorCodes.ENTITY_NOT_FOUND, "Entity not found")
    case ex: DatabaseException =>
      log.error("Mongo database exception occured", ex)
      throw AppException(ErrorCodes.INTERNAL_SERVER_ERROR, "Database exception occurred")
    case ex: Exception =>
      throw AppException(ErrorCodes.INTERNAL_SERVER_ERROR, ex.getMessage)
  }
}