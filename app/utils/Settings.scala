package utils

import scala.concurrent.duration._

/**
  *
  * @author Yaroslav Derman <yaroslav.derman@gmail.com>
  *         created on 16/03/16
  */
object Settings {

  final val emailTtl = 24 * 60 * 60
  final val phoneTtl = 10 * 60

  final val EMPTY = ""

  final val ERROR_KEY = "error"
  final val ERROR_REQUIRED = "error.required"
  final val ERROR_LONG = "error.long"
  final val ERROR_INT = "error.int"
  final val ERROR_PERMISSIONS = "error.permissions"

  final val DEFAULT_LIMIT = 100
  final val DEFAULT_OFFSET = 0

  final val DEFAULT_TEMP_TOKEN_TTL = 30.seconds

  final val PRECONDITION_REQUIRED = 428

  final val EXT_SERVICE = "extService"
  final val REQUISITES_KEY = "requisites"
  final val MSG = "msg"

  final val COLON = ":"
  final val COMMA = ","
  final val COMMA_COLON = ";"
  final val LF_SQRT_BRACKET = "["
  final val RT_SQRT_BRACKET = "]"

  final val SCRIPT_UPS = "UPS"
  final val SCRIPT_DOWNS = "DOWNS"
  final val SCRIPT_UNKNOWN = "UNKNOWN"

  final val TAG_ID = "id"
  final val TAG_UUID = "uuid"
  final val TAG_CREATED = "created"
  final val TAG_FIRST_NAME = "firstName"
  final val TAG_LAST_NAME = "lastName"
  final val TAG_FLAGS = "flags"
  final val TAG_EMAIL = "email"
  final val TAG_PHONE = "phone"
  final val TAG_LOGIN = "login"
  final val TAG_PASSWORD = "password"
  final val TAG_PASS = "pass"
  final val TAG_NEW_PASS = "newPass"
  final val TAG_GENDER = "gender"
  final val TAG_BIRTH_DATE = "birthDate"
  final val TAG_CODE = "code"
  final val TAG_STATE = "state"
  final val TAG_ADDRESS = "address"
  final val TAG_NAME = "name"
  final val TAG_ENABLED = "enabled"
  final val TAG_SERVICE = "service"
  final val TAG_GATEWAY_ID = "gatewayId"
  final val TAG_AMOUNT = "amount"
  final val TAG_CURRENCY = "currency"
  final val TAG_OPERATION = "operation"
  final val TAG_CLASS_NAME = "className"
  final val TAG_PRIORITY = "priority"
  final val TAG_TX_ID = "txId"
  final val TAG_COUNT = "count"
  final val TAG_TYPE = "type"
  final val TAG_PERCENT = "percent"
  final val TAG_WALLET = "wallet"
  final val TAG_MSISDN = "msisdn"
  final val TAG_USER = "user"
  final val TAG_USER_ID = "userId"
  final val TAG_DETAILS = "details"
  final val TAG_RECIPIENT = "recipient"
  final val TAG_TIMESTAMP = "timestamp"
  final val TAG_DIRECTION = "direction"
  final val TAG_SINCE = "since"
  final val TAG_UNTIL = "until"
  final val TAG_LIMIT = "limit"
  final val TAG_OFFSET = "offset"
  final val TAG_Q = "q"
  final val TAG_URL = "url"
  final val TAG_APPLICATION_ID = "applicationId"
  final val TAG_OAUTH_ID = "oauthId"
  final val TAG_DESCRIPTION = "description"
  final val TAG_LOGO = "logo"
  final val TAG_CONTACTS = "contacts"
  final val TAG_REDIRECT_URL_PATTERN = "redirectUrlPattern"
  final val TAG_CLIENT_ID = "clientId"
  final val TAG_SCOPE = "scope"
  final val TAG_RESPONSE_TYPE = "responseType"
  final val TAG_BLOCK = "block"

  final val CHARSET_UTF_8 = "UTF-8"

  final val MSG_THE_SAME_ACCOUNT = "It is the same account"
  final val MSG_UNSUPPORTED_SCHEMA = "Unsupported schema {0} for {1} method"

}

