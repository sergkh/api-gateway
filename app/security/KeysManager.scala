package security

import java.io.FileInputStream
import java.security._
import java.security.interfaces.{ECPrivateKey, ECPublicKey}
import java.security.spec.ECGenParameterSpec
import java.{util => ju}

import com.nimbusds.jose.jwk.ECKey.Curve
import com.nimbusds.jose.jwk.{JWK, _}
import javax.inject.Singleton
import models.ConfigException
import models.conf.CryptoConfig
import org.bouncycastle.jce.provider.BouncyCastleProvider
import play.api.Logger
import utils.{Logging, RandomStringGenerator}

@Singleton
class KeysManager(conf: CryptoConfig) extends Logging {
  import KeysManager._

  if (Security.getProvider("BC") == null) Security.addProvider(new BouncyCastleProvider())
  
  private[this] val authKeys = loadAuthKeys(conf, log)

  /** @return map of certificates and their key ID's used to verify the auth token signature */
  def jwkAuthKeys: List[JWK] = authKeys.jwkList

  /** @return current private key used to sign auth token with it's key ID */
  def currentAuthPrivKey: (String, PrivateKey) = authKeys.currentPrivKey

  /** @return certificate used to verify auth token signature by key ID */
  def authPubKey(keyId: String): Option[PublicKey] = authKeys.pubKeysMap.get(keyId)

  /** @return function that used to sign internal codes stored in the database */
  def codesSigner: String => String = { message =>
    val mac = javax.crypto.Mac.getInstance("HmacSHA256")
    mac.init(authKeys.codesSignKey)
    ju.Base64.getEncoder.encodeToString(mac.doFinal(message.getBytes()))
  }
}

object KeysManager {

  case class AuthKeys(
    jwk: JWK,
    keyPair: KeyPair, // Should correspond the current JWK
    codesSignKey: Key,
    deprecatedJWK: List[(JWK, PublicKey)] = Nil
  ) {
    val currentPrivKey: (String, PrivateKey) = jwk.getKeyID -> keyPair.getPrivate

    val pubKeysMap: Map[String, PublicKey] = Map(jwk.getKeyID -> keyPair.getPublic) ++ deprecatedJWK.map {
      case (jwk, pubKey) => jwk.getKeyID -> pubKey
    }

    val jwkList: List[JWK] = List(jwk) ++ deprecatedJWK.map(_._1)
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

      val (jwk, keyPair) = cryptoConf.accessToken.signKeyAlias.map { keyAlias =>
        loadAccessTokenKey(keyAlias, keystore, pass)
      }.getOrElse {
        log.warn("!WARNING! Access token key alias is not set. Generating key pair.")
        genJWK
      }

      val authCodesKey = cryptoConf.authCodes.signKeyAlias.map { alias =>
        Option(keystore.getKey(alias, pass.toCharArray())).getOrElse {
          throw ConfigException(s"Unable to find key '$alias' in keystore", "crypto.authCodes.signKeyAlias")
        }
      } getOrElse {
        log.warn("!WARNING! Codes signature not set using generated one.")
        genAESKey
      }

      AuthKeys(
        jwk,
        keyPair,
        authCodesKey,
        loadDeprecatedCerts(cryptoConf.accessToken.deprecatedKeyAliasesList, keystore, pass)
      )
    }.getOrElse {
      log.warn("!WARNING! No keystore file set. Generating keys.")
      val (jwk, pair) = genJWK
      AuthKeys(jwk, pair, genAESKey)
    }
  }

  def loadKeystore(storeFile: String, password: String): KeyStore = {
    val ks = KeyStore.getInstance("JCEKS")
    ks.load(new FileInputStream(storeFile), password.toCharArray)
    ks
  }

  def generateAuthKeys: (ECPublicKey, ECPrivateKey) = {
    val g = KeyPairGenerator.getInstance("ECDSA", "BC")
    g.initialize(new ECGenParameterSpec("P-521"), new SecureRandom())
    val pair = g.generateKeyPair()
    pair.getPublic.asInstanceOf[ECPublicKey] -> pair.getPrivate.asInstanceOf[ECPrivateKey]
  }

  def loadAccessTokenKey(alias: String, ks: KeyStore, password: String): (JWK, KeyPair) = {

    val privKey = Option(ks.getKey(alias, password.toCharArray).asInstanceOf[PrivateKey]).getOrElse {
      throw ConfigException(s"Private key '$alias' is not found", "crypto.accessToken.signKeyAlias")
    }

    val cert = Option(ks.getCertificate(alias)).getOrElse {
      throw ConfigException(s"Certificate '$alias' is not found", "crypto.accessToken.signKeyAlias")
    }

    val jwk = JWK.load(ks, alias, password.toCharArray).toPublicJWK
    jwk -> new KeyPair(cert.getPublicKey, privKey)
  }

  def loadDeprecatedCerts(aliases: List[String], ks: KeyStore, password: String): List[(JWK, PublicKey)] = {
    aliases.map { keyAlias =>
      val cert = Option(ks.getCertificate(keyAlias)).getOrElse {
        throw ConfigException(s"Certificate for alias $keyAlias is not found", "crypto.accessToken.deprecatedKeyAliases")
      }

      JWK.load(ks, keyAlias, password.toCharArray).toPublicJWK -> cert.getPublicKey
    }
  }

  def genJWK: (JWK, KeyPair) = {
    val (pub, priv) = generateAuthKeys
    val jwk = new ECKey.Builder(Curve.P_521, pub).keyID(RandomStringGenerator.generateId()).keyUse(KeyUse.SIGNATURE).build
    jwk -> new KeyPair(pub, priv)
  }

  def genAESKey: Key = {
    val keyGen = javax.crypto.KeyGenerator.getInstance("AES", "BC")
    keyGen.init(256)
    keyGen.generateKey()
  }
}
