package utils

import java.security.SecureRandom

import scala.util.Random

object RandomStringGenerator {

  final val LCASE = "abcdefgijkmnopqrstwxyz"
  final val UCASE = "ABCDEFGHJKLMNPQRSTWXYZ"
  final val NUMERIC = "1234567890"
  final val SPECIALS = "*$-+?_&=!%^#~"
  final val SAFE_SPECIALS = "-_"
  final val URL_SAFE_ALPHABET = LCASE + UCASE + NUMERIC + SAFE_SPECIALS

  def generateNumericPassword(minLen: Int, maxLen: Int): String =
    generateRandomString(minLen, maxLen, allowNumbers = true)

  def generateStringPassword(minLen: Int, maxLen: Int): String =
    generateRandomString(minLen, maxLen, true, true, true, false)

  def generateSecret(length: Int): String =
    generateRandomString(length, length, true, true, true, false)

  /**
    * Generates random string containing only allowed characters.
    *
    * @param minLength minimum password length.
    * @param maxLength maximum password length.
    * @param allowLCase allow to use lower case letters.
    * @param allowUCase allow to use upper case letters.
    * @param allowNumbers allow to use numbers.
    * @param allowSpecials allow to use special characters.
    * @return random string.
    */
  def generateRandomString(minLength: Int, maxLength: Int,
                           allowNumbers: Boolean = true,
                           allowLCase: Boolean = false,
                           allowUCase: Boolean = false,
                           allowSpecials: Boolean = false): String = {

    val rnd = new SecureRandom
    val allowedChars = new StringBuilder

    if (allowLCase) allowedChars.append(LCASE)
    if (allowUCase) allowedChars.append(UCASE)
    if (allowNumbers) allowedChars.append(NUMERIC)
    if (allowSpecials) allowedChars.append(SPECIALS)

    val allowed = allowedChars.toString().toCharArray

    scatterArray(allowed, rnd.nextLong + System.currentTimeMillis)

    val password = if (minLength < maxLength) {
      new Array[Char](minLength + rnd.nextInt(maxLength - minLength + 1))
    } else {
      new Array[Char](minLength)
    }

    val allowedLen = allowed.length
    var i = 0
    while (i < password.length) {
      password(i) = allowed(rnd.nextInt(allowedLen))
      i += 1
    }

    new String(password)
  }

  /**
    * Generate URL friendly Id.
    *
    * @param len length of the string, 21 by default, which is random enough.
    * @param alphabet alphabet to use, default is URL friendly alphabet
    * @return URL friendly random string.
    */
  def generateId(len: Int = 21, alphabet: String = URL_SAFE_ALPHABET): String = {
    val sb = new StringBuilder(len)
    val randomArr = new Array[Byte](len)

    new SecureRandom().nextBytes(randomArr)

    val alphabetLen = alphabet.length

    randomArr.foreach { b =>
      sb.append(alphabet((b & 0xFF) % alphabetLen))
    }

    sb.toString()
  }

  /**
    * Makes set of random swaps of array elements.
    *
    * @param array array to scatter.
    * @param seed random seed.
    */
  private def scatterArray(array: Array[Char], seed: Long) {
    val r = new Random(seed)
    val len = array.length
    val swaps = 20 + r.nextInt(200)
    var i: Int = 0

    while (i < swaps) {
      val from = r.nextInt(len)
      val to = r.nextInt(len)

      val tmp = array(from)
      array(from) = array(to)
      array(to) = tmp

      i += 1
    }
  }
}
