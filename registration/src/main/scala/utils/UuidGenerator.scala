package utils

import java.security.SecureRandom
import java.util.Random

/**
  * Unique long generator
  * @author Sergey Khruschak (sergey.khruschak@gmail.com)
  */
object UuidGenerator {

  private final val numberGenerator = new SecureRandom
  private final val random = new Random(System.nanoTime)
  private final val UID_BASE = 1000000000000000L
  private final val ARRAY_SIZE = 8
  private final val DIGIT_MAX = 9
  private final val JS_SAFE_MAX = 9007199254740992L

  def generateId: Long = {
    val id = generateIdUnsafe

    if (id < JS_SAFE_MAX) {
      id
    } else {
      generateId
    }
  }

  private def generateIdUnsafe: Long = {
    val ng = numberGenerator
    val randomBytes = new Array[Byte](ARRAY_SIZE)
    ng.nextBytes(randomBytes)

    var uid: Long = 0L

    var i = 0
    while (i < 8) {
      uid = (uid << 8) | (randomBytes(i) & 0xff)
      i += 1
    }

    checkDigits(uid)
  }

  private def checkDigits(uid: Long): Long = {
    val normUid = if (uid < 0) -uid else uid
    val rndDigit = 1 + random.nextInt(DIGIT_MAX)

    (rndDigit * UID_BASE) + (normUid % UID_BASE)
  }

  def main(args: Array[String]) {
    println("New id:" + generateId)
  }
}
