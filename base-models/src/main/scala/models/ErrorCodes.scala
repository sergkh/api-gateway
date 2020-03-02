package models

object ErrorCodes {
  val AUTHORIZATION_FAILED = "authorization_failed"
  val ALREADY_EXISTS = "already_exists"
  val ACCESS_DENIED = "access_denied"
  val INTERNAL_SERVER_ERROR = "internal_server_error"
  val BLOCKED_USER = "user_blocked"
  val EXPIRED_PASSWORD = "password_expired"
  val CONCURRENT_MODIFICATION = "concurrent_modification"
  val ENTITY_NOT_FOUND = "entity_not_found"
  val NON_EMPTY_SET = "non_empty_set"
  val IDENTIFIER_REQUIRED = "identifier_required"
  val CONFIRM_CODE_NOT_FOUND = "confirm_code_not_found"
  val INVALID_REQUEST = "invalid_request"
  val CONFIRMATION_REQUIRED = "confirmation_required"
  val APPLICATION_NOT_FOUND = "application_not_found"
  val DUPLICATE_REQUEST = "duplicate_request"
  val INVALID_IDENTIFIER = "invalid_identifier"
  val INVALID_TOKEN_CLAIMS = "invalid_token_claims"
  val SERVICE_UNAVAILABLE = "service_unavailable"
}
