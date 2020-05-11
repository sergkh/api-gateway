package security

import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers
import java.security.{KeyStore, Security}

import models.conf.CryptoConfig
import models.conf.CryptoConfig.{AccessTokenConfig, KeystoreConfig}
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.slf4j.LoggerFactory
import play.api.Logger

import scala.collection.JavaConverters._
class KeysManagerSpec extends AnyWordSpec with Matchers {
  import KeysManager._

  if (Security.getProvider("BC") == null) Security.addProvider(new BouncyCastleProvider())

  "A keys manager" should {

    "load keystore" in {
      val ks = loadKeystore("./test/keystore.jceks", "testtest")
      Option(ks.getCertificate("access-token")) shouldBe defined
      Option(ks.getKey("access-token", "testtest".toCharArray)) shouldBe defined
    }

    "load keystore keys" in {
      val conf = CryptoConfig(
        KeystoreConfig(Some("./test/keystore.jceks"), password = Some("testtest")),
        AccessTokenConfig(signKeyAlias = Some("access-token"))
      )

      KeysManager.loadAuthKeys(conf, Logger("test"))
    }
  }
}
