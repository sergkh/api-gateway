package utils

import com.impactua.bouncer.commons.models.ResponseCode
import com.impactua.bouncer.commons.models.exceptions.AppException
import com.impactua.bouncer.commons.utils.Logging
import reactivemongo.core.errors.DatabaseException

/**
  * Created by faiaz on 19.10.16.
  */
object MongoErrorHandler extends Logging {

  private val existsCodeEntity = Seq(11000, 11001, 10054, 10056, 10058, 10107, 13435, 13436)
  private val notfoundCodeEntity = Seq(10057, 15845, 16550)

  def processError[T]: PartialFunction[Throwable, T] = {
    case ex: DatabaseException if ex.code.exists(existsCodeEntity.contains) => throw AppException(ResponseCode.ALREADY_EXISTS, "Entity already exists")
    case ex: DatabaseException if ex.code.exists(notfoundCodeEntity.contains) => throw AppException(ResponseCode.ENTITY_NOT_FOUND, "Entity not found")
    case ex: DatabaseException =>
      log.error("Mongo database exception occured", ex)
      throw AppException(ResponseCode.INTERNAL_SERVER_ERROR, "Database exception occurred")
    case ex: Exception =>
      throw AppException(ResponseCode.INTERNAL_SERVER_ERROR, ex.getMessage)
  }
}