package models

case class AppException(code: String, message: String) extends Exception {

  override def getMessage: String = message + ", code: " + code

  override def toString: String = "Application exception: " + getMessage
}

case class ConfigException(msg: String, key: String) 
  extends Exception(s"Config error for key: $key:\n$msg")