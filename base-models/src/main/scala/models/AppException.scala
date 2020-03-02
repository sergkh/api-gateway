package models

case class AppException(code: String, message: String) extends Exception {

  override def getMessage: String = message + ", code: " + code

  override def toString: String = "Application exception: " + getMessage
}