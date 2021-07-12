package module

import services._
import com.google.inject.name.Named
import com.google.inject.{AbstractModule, Provides}
import com.mohiva.play.silhouette.api.actions.{SecuredErrorHandler, UnsecuredErrorHandler}
import com.mohiva.play.silhouette.api.crypto.{Base64AuthenticatorEncoder, Crypter, CrypterAuthenticatorEncoder, Signer}
import com.mohiva.play.silhouette.api.repositories.AuthInfoRepository
import com.mohiva.play.silhouette.api.services.{AuthenticatorService, AvatarService, IdentityService}
import com.mohiva.play.silhouette.api.util._
import com.mohiva.play.silhouette.api.{Environment, EventBus, Silhouette, SilhouetteProvider}
import com.mohiva.play.silhouette.crypto._
import com.mohiva.play.silhouette.impl.authenticators._
import com.mohiva.play.silhouette.impl.providers._
import com.mohiva.play.silhouette.impl.providers.oauth1.{TwitterProvider, XingProvider}
import com.mohiva.play.silhouette.impl.providers.oauth1.secrets.{CookieSecretProvider, CookieSecretSettings}
import com.mohiva.play.silhouette.impl.providers.oauth1.services.PlayOAuth1Service
import com.mohiva.play.silhouette.impl.providers.oauth2._
import com.mohiva.play.silhouette.impl.providers.openid.YahooProvider
import com.mohiva.play.silhouette.impl.providers.openid.services.PlayOpenIDService
import com.mohiva.play.silhouette.impl.providers.state.{CsrfStateItemHandler, CsrfStateSettings}
import com.mohiva.play.silhouette.impl.services._
import com.mohiva.play.silhouette.impl.util._
import com.mohiva.play.silhouette.password.BCryptSha256PasswordHasher
import com.mohiva.play.silhouette.persistence.daos.DelegableAuthInfoDAO
import com.mohiva.play.silhouette.persistence.repositories.DelegableAuthInfoRepository
import com.typesafe.config.Config
import javax.inject.Singleton
import services.dao._
import models.{JwtEnv, User}
import net.ceedubs.ficus.Ficus._
import net.ceedubs.ficus.readers.ArbitraryTypeReader._
import net.ceedubs.ficus.readers.{EnumerationReader, ValueReader}
import net.codingwell.scalaguice.ScalaModule
import org.slf4j.LoggerFactory
import play.api.Configuration
import play.api.libs.openid.OpenIdClient
import play.api.libs.ws.WSClient
import play.api.mvc.Cookie
import security.{CustomJWTAuthenticator, KeysManager}
import utils.ServerErrorHandler

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext.Implicits.global
import models.conf.CryptoConfig

/**
  * The Guice module which wires all Silhouette dependencies.
  */
class SilhouetteModule extends AbstractModule with ScalaModule with EnumerationReader {

  val log = LoggerFactory.getLogger(getClass())

  implicit val cookieSamesiteReader = new ValueReader[Cookie.SameSite] {
    def read(config: Config, path: String) = Cookie.SameSite.parse(config.getString(path)).getOrElse {
      throw new IllegalArgumentException(s"Failed to read cookie same site config value from path: $path, invalid value: ${config.getString(path)}")
    }
  }

  /**
    * Configures the module.
    */
  override def configure(): Unit = {
    bind[IdentityService[User]].to[UserService]
    bind[Silhouette[JwtEnv]].to[SilhouetteProvider[JwtEnv]]
    bind[CacheLayer].to[PlayCacheLayer]
    bind[IDGenerator].toInstance(new SecureRandomIDGenerator(32))
    bind[PasswordHasher].toInstance(new BCryptSha256PasswordHasher())
    bind[Clock].toInstance(Clock())
    bind[UnsecuredErrorHandler].to[ServerErrorHandler]
    bind[SecuredErrorHandler].to[ServerErrorHandler]
    bind[DelegableAuthInfoDAO[PasswordInfo]].to[PasswordInfoDao]
    bind[DelegableAuthInfoDAO[OAuth1Info]].to[SocialOAuth1Dao]
    bind[DelegableAuthInfoDAO[OAuth2Info]].to[SocialOAuth2Dao]
    bind[SessionsService].to[MongoSessionsService]
    bind[ClientAuthenticator].to[ClientAppsService]
  }

  /**
    * Provides the HTTP layer implementation.
    *
    * @param client Play's WS client.
    * @return The HTTP layer implementation.
    */
  @Provides
  @Singleton
  def provideHTTPLayer(client: WSClient): HTTPLayer = new PlayHTTPLayer(client)

  /**
    * Provides the Silhouette environment.
    *
    * @param userService The user service implementation.
    * @param authService The authentication service implementation.
    * @param eventBus The event bus instance.
    * @return The Silhouette environment.
    */
  @Provides
  def provideEnvironment(userService: UserService,
                         authService: AuthenticatorService[JWTAuthenticator],
                         eventBus: EventBus): Environment[JwtEnv] = {

    Environment[JwtEnv](userService, authService, Seq(), eventBus)
  }

  @Provides
  @Singleton
  def provideKeyManger(conf: CryptoConfig): KeysManager = new KeysManager(conf)

  /**
    * Provides the social provider registry.
    *
    * @return The Silhouette environment.
    */
  @Provides
  def provideSocialProviderRegistry(httpLayer: HTTPLayer,
                                    stateHandler: SocialStateHandler,
                                    tokenSecretProvider: OAuth1TokenSecretProvider,
                                    client: OpenIdClient,
                                    conf: Configuration): SocialProviderRegistry = {

    val socialConfigs = conf.underlying.getConfigList("silhouette.social").asScala
    
    val providers = socialConfigs.flatMap {
      case cfg: Config =>
        Some(cfg.getString("type") -> cfg).filter(_._2.getBoolean("enabled"))
    }
    .flatMap {
      case ("facebook", cfg) =>
        log.debug(s"Adding Facebook social provider")
        Some(new FacebookProvider(httpLayer, stateHandler, cfg.as[OAuth2Settings]))
      case ("google", cfg) =>
        log.debug(s"Adding Google social provider")
        Some(new GoogleProvider(httpLayer, stateHandler, cfg.as[OAuth2Settings]))
      case ("twitter", cfg) =>
        log.debug(s"Adding Twitter social provider")
        val settings = cfg.as[OAuth1Settings]
        Some(new TwitterProvider(httpLayer, new PlayOAuth1Service(settings), tokenSecretProvider, settings))
      case ("xing", cfg) =>
        log.debug(s"Adding Xing social provider")
        val settings = cfg.as[OAuth1Settings]
        Some(new XingProvider(httpLayer, new PlayOAuth1Service(settings), tokenSecretProvider, settings))
      case ("yahoo", cfg) =>
        log.debug(s"Adding Yahoo social provider")
        val settings = cfg.as[OpenIDSettings]
        Some(new YahooProvider(httpLayer, new PlayOpenIDService(client, settings), settings))      
      case (provider, _) =>
        log.warn(s"Ignoring provider configuration: $provider")
        None
    }.toSeq

    SocialProviderRegistry(providers)
  }

  /**
    * Provides the authenticator service.
    *
    * @param idGenerator The ID generator implementation.
    * @param conf The Play configuration.
    * @param clock The clock instance.
    * @return The authenticator service.
    */
  @Provides
  @Singleton
  def provideAuthenticatorService(idGenerator: IDGenerator,
                                  conf: Configuration,
                                  @Named("authenticator-crypter") crypter: Crypter,
                                  keyManager: KeysManager,
                                  clock: Clock): AuthenticatorService[JWTAuthenticator] = {

    val settings = conf.underlying.as[JWTAuthenticatorSettings]("silhouette.authenticator")

    val encoder = conf.get[String]("silhouette.authenticator.encoder") match {
      case "base64" => new Base64AuthenticatorEncoder()
      case "crypter" => new CrypterAuthenticatorEncoder(crypter)
      case other =>
        throw new IllegalArgumentException(
          s"Setting silhouette.authenticator.encoder(AUTHENTICATOR_ENCODER) value $other is not supported." +
          s"Use: base64 or crypter"
        )
    }

    new CustomJWTAuthenticator(settings, None, encoder, idGenerator, keyManager, clock)
  }


  @Provides
  @Named("authenticator-signer")
  def provideAuthenticatorCookieSigner(configuration: Configuration): Signer = {
    val config = configuration.underlying.as[JcaSignerSettings]("silhouette.authenticator.signer")
    new JcaSigner(config)
  }

  /**
    * Provides the crypter for the OAuth1 token secret provider.
    *
    * @param configuration The Play configuration.
    * @return The crypter for the OAuth1 token secret provider.
    */
  @Provides
  @Named("authenticator-crypter")
  def provideOAuth1TokenSecretCrypter(configuration: Configuration): Crypter = {
    val config = configuration.underlying.as[JcaCrypterSettings]("silhouette.authenticator.crypter")
    new JcaCrypter(config)
  }

  /**
    * Provides the auth info repository.
    *
    * @param passwordInfoDAO The implementation of the delegable password auth info DAO.
    * @return The auth info repository instance.
    */
  @Provides
  def provideAuthInfoRepository(passwordInfoDAO: DelegableAuthInfoDAO[PasswordInfo],
                                oAuth1InfoDao: DelegableAuthInfoDAO[OAuth1Info],
                                oAuth2InfoDao: DelegableAuthInfoDAO[OAuth2Info]): AuthInfoRepository = { 
    new DelegableAuthInfoRepository(passwordInfoDAO, oAuth1InfoDao, oAuth2InfoDao)
  }

  /**
    * Provides the avatar service.
    *
    * @param httpLayer The HTTP layer implementation.
    * @return The avatar service implementation.
    */
  @Provides
  def provideAvatarService(httpLayer: HTTPLayer): AvatarService = new GravatarService(httpLayer)

  /**
    * Provides the password hasher registry.
    *
    * @param passwordHasher The default password hasher implementation.
    * @return The password hasher registry.
    */
  @Provides
  def providePasswordHasherRegistry(passwordHasher: PasswordHasher): PasswordHasherRegistry = {
    PasswordHasherRegistry(passwordHasher)
  }

  /**
    * Provides the credentials provider.
    *
    * @param authInfoRepository The auth info repository implementation.
    * @param passwordHasherRegistry The password hasher registry.
    * @return The credentials provider.
    */
  @Provides
  def provideCredentialsProvider(
                                  authInfoRepository: AuthInfoRepository,
                                  passwordHasherRegistry: PasswordHasherRegistry): CredentialsProvider = {
    new CredentialsProvider(authInfoRepository, passwordHasherRegistry)
  }

  @Provides
  def provideOAuth1TokenSecretProvider(configuration: Configuration,
                                       @Named("authenticator-signer") cookieSigner: Signer,
                                       @Named("authenticator-crypter") crypter: Crypter,
                                       clock: Clock): OAuth1TokenSecretProvider = {
    val settings = configuration.underlying.as[CookieSecretSettings]("silhouette.oauth1TokenSecretProvider")
    new CookieSecretProvider(settings, cookieSigner, crypter, clock)
  }

  @Provides
  @Named("auth2-cookie-signer")
  def provideOAuth2StageCookieSigner(configuration: Configuration): Signer = {
    val config = configuration.underlying.as[JcaSignerSettings]("silhouette.authenticator.signer")
    new JcaSigner(config)
  }

  @Provides
  def socialStateHandler(@Named("authenticator-signer") signer: Signer,
                         idGenerator: IDGenerator,
                         configuration: Configuration): SocialStateHandler = {
    val csrfSettings = configuration.underlying.as[CsrfStateSettings]("silhouette.oauth2StateProvider")

    val csrfHandler = new CsrfStateItemHandler(csrfSettings, idGenerator, signer)

    new DefaultSocialStateHandler(Set(csrfHandler), signer)
  }

}
