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

@Singleton
class KeysManager(conf: Configuration) {
  if (Security.getProvider("BC") == null) Security.addProvider(new BouncyCastleProvider())

  val g = KeyPairGenerator.getInstance("ECDSA", "BC")
  g.initialize(new ECGenParameterSpec("P-521"), new SecureRandom())

  val authKeyPair = g.generateKeyPair()

  val authCertificate = genCertificate(authKeyPair)

  def authCertificates: Map[String, X509Certificate] = Map("default" -> authCertificate)

  def currentAuthPrivKey: (String, PrivateKey) = "default" -> authKeyPair.getPrivate()
  def authPubKey(keyId: String): Option[PublicKey] = Some(authKeyPair.getPublic())

  private[this] def genCertificate(pair: KeyPair): X509Certificate = {
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
}
