package services

import javax.inject.{Inject, Singleton}
import java.security._
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.security.spec.ECGenParameterSpec
import org.bouncycastle.asn1.x500.X500Name
import java.{util => ju}
import java.math.BigInteger
import java.security.cert.X509Certificate
import scala.compat.Platform
import scala.concurrent.duration._

@Singleton
class TokensService() {
  // http://pauldijou.fr/jwt-scala/samples/jwt-ecdsa/
  val g = KeyPairGenerator.getInstance("ECDSA", "BC")
  g.initialize(new ECGenParameterSpec("P-521"), new SecureRandom())

  val authKeyPair = g.generateKeyPair()
  val refreshKeyPair = g.generateKeyPair()
  val authCertificate = genCertificate(authKeyPair) 
  // val token = Jwt.encode("""{"user":1}""", ecKey.getPrivate, JwtAlgorithm.ES512)

  def authCertificates: Map[String, X509Certificate] = Map("default" -> authCertificate)

  def refreshTokenSignKey: PrivateKey = refreshKeyPair.getPrivate()

  def authTokenSignKey: PrivateKey = authKeyPair.getPrivate()

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