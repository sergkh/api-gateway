package security

import java.math.BigInteger
import java.security._
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.{util => ju}

import javax.inject.Singleton
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

import scala.compat.Platform
import scala.concurrent.duration._
import play.api.Configuration
import scala.io.Source
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.security.cert.CertificateFactory
import java.io.FileInputStream
import java.io.InputStream
import utils.{RandomStringGenerator, Logging}
import net.ceedubs.ficus.Ficus._
import net.ceedubs.ficus.readers.ArbitraryTypeReader._
import com.typesafe.config.Config
import models.ConfigException
import play.api.Logger
import models.conf.CryptoConfig

@Singleton
class KeysManager(conf: CryptoConfig) extends Logging {
  import KeysManager._

  if (Security.getProvider("BC") == null) Security.addProvider(new BouncyCastleProvider())
  
  private[this] val authKeys = loadAuthKeys(conf, log)

  /**
    * @return map of certificates and their key ID's used to verify the 
    * auth token signature
    */
  def authCertificates: Map[String, X509Certificate] = authKeys.certsMap

  /**
    * @return current private key used to sign auth token with it's key ID
    */
  def currentAuthPrivKey: (String, PrivateKey) = authKeys.currentPrivKey

  /**
    * @return certificate used to verify auth token signature by key ID
    */
  def authPubKey(keyId: String): Option[PublicKey] = authKeys.certsMap.get(keyId).map(_.getPublicKey())

  /**
    * @return function that used to sign internal codes stored in the database.
    */
  def codesSigner: String => String = { message =>
    val mac = javax.crypto.Mac.getInstance("HmacSHA256")
    mac.init(authKeys.codesSignKey)
    ju.Base64.getEncoder.encodeToString(mac.doFinal(message.getBytes()))
  }
}

object KeysManager {

  case class AuthKeys(
    keyId: String,
    singKey: PrivateKey,
    signCert: X509Certificate,
    codesSignKey: Key,
    deprecatedCerts: Map[String, X509Certificate] = Map()    
  ) {
    val currentPrivKey: (String, PrivateKey) = keyId -> singKey
    val certsMap: Map[String, X509Certificate] = Map(keyId -> signCert) ++ deprecatedCerts
  }

  def loadAuthKeys(cryptoConf: CryptoConfig, log: Logger): AuthKeys = {
    cryptoConf.keystore.file.map { ksFile =>
      val pass = cryptoConf.keystore.pass.getOrElse{
        throw ConfigException(
          "Keystore is set, but no password provided. Use 'KEYSTORE_PASSWORD' or 'KEYSTORE_PASSWORD_FILE' environment variable",
          "crypto.keystore.password"
        )
      }

      val keystore = loadKeystore(ksFile, pass)

      val (keyId, cert, privKey) = cryptoConf.accessToken.signKeyAlias.map { keyAlias =>
        log.warn("!WARNING! Access token key alias is not set. Generating key pair.")

        val primaryKeyId = cryptoConf.accessToken.signKeyId.getOrElse(keyAlias)
        
        val privKey = Option(keystore.getKey(keyAlias, pass.toCharArray()).asInstanceOf[PrivateKey]).getOrElse {
          throw ConfigException(s"Private key '$keyAlias' is not found", "crypto.accessToken.signKeyAlias")
        }
        val cert = Option(keystore.getCertificate(keyAlias).asInstanceOf[X509Certificate]).getOrElse {
          throw ConfigException(s"Certificate '$keyAlias' is not found", "crypto.accessToken.signKeyAlias")
        }

        (primaryKeyId, cert, privKey)
      }.getOrElse {
        val (cert, privKey) = generateAuthKeys
        (RandomStringGenerator.generateId(), cert, privKey)
      }
      
      val authCodesKey = cryptoConf.authCodes.signKeyAlias.map { alias => 
        Option(keystore.getKey(alias, pass.toCharArray())).getOrElse {
          throw new ConfigException(s"Unable to find key '$alias' in keystore", "crypto.authCodes.signKeyAlias")
        }
      } getOrElse {
        log.warn("!WARNING! Codes signature not set using generated one.")
        genAESKey
      }

      val deprecatedCerts = cryptoConf.accessToken.deprecatedKeyAliases.map { keyAliases =>
        keyAliases.split(",").map(_.split("=")).map {
          case Array(keyId, keyAlias) => keyId -> Option(keystore.getCertificate(keyAlias)).getOrElse {
            throw ConfigException(s"Certificate for alias $keyAlias is not found", 
                                        "crypto.accessToken.deprecatedKeyAliases")
          }.asInstanceOf[X509Certificate]
        }.toMap
      }.getOrElse(Map.empty)

      AuthKeys(keyId, privKey, cert, authCodesKey, deprecatedCerts)
    }.getOrElse { 
      log.warn("!WARNING! No keystore file set. Generating keys.")
      val (cert, privKey) = generateAuthKeys

      AuthKeys(RandomStringGenerator.generateId(), privKey, cert, genAESKey)
    }
  }

  def loadKeystore(storeFile: String, password: String): KeyStore = {
    val ks = KeyStore.getInstance("JCEKS")  
    ks.load(new FileInputStream(storeFile), password.toCharArray())
    ks
  }

  def generateAuthKeys: (X509Certificate, PrivateKey) = {
    val g = KeyPairGenerator.getInstance("ECDSA", "BC")
    g.initialize(new ECGenParameterSpec("P-521"), new SecureRandom())
    val pair = g.generateKeyPair()
    genCertificate(pair) -> pair.getPrivate()
  }

  def genCertificate(pair: KeyPair): X509Certificate = {
    val now = Platform.currentTime

    val generator = new X509v3CertificateBuilder(
      new X500Name("CN=Development"), // issuer DN
      new BigInteger(64, new SecureRandom()), // serial number
      new ju.Date(now - 1.hour.toMillis), // not before
      new ju.Date(now + 265.days.toMillis), // not after
      new X500Name("CN=Development"), // subject DN (same as issuer)
      SubjectPublicKeyInfo.getInstance(pair.getPublic.getEncoded) // public key
    )

    val holder = generator.build(new JcaContentSignerBuilder("SHA256withECDSA").build(pair.getPrivate))

    new JcaX509CertificateConverter().setProvider("BC").getCertificate(holder)
  }

  def genAESKey: Key = {
    val keyGen = javax.crypto.KeyGenerator.getInstance("AES", "BC")
    keyGen.init(256)
    keyGen.generateKey()
  }
}
