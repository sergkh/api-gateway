package models

import com.mohiva.play.silhouette.api.LoginInfo
import com.mohiva.play.silhouette.api.util.PasswordInfo
import com.mohiva.play.silhouette.impl.providers.{OAuth1Info, OAuth2Info}
import org.bson.codecs.configuration.CodecRegistries
import org.mongodb.scala.bson.codecs.Macros
import org.mongodb.scala.MongoClient.DEFAULT_CODEC_REGISTRY

object MongoFormats {

  val rolePermissionsCodec = Macros.createCodecProviderIgnoreNone[RolePermissions]()
  val refreshTokenCodec = Macros.createCodecProviderIgnoreNone[RefreshToken]()
  val sessionCodec = Macros.createCodecProviderIgnoreNone[Session]()
  val clientAppCodec = Macros.createCodecProviderIgnoreNone[ClientApp]()
  val branchCodec = Macros.createCodecProviderIgnoreNone[Branch]()
  val passwordInfoCodec = Macros.createCodecProviderIgnoreNone[PasswordInfo]()
  val userCodec = Macros.createCodecProviderIgnoreNone[User]()
  val userAuthInfoCodec = Macros.createCodecProviderIgnoreNone[UserAuthInfo]()
  val loginCodec = Macros.createCodecProviderIgnoreNone[LoginInfo]()
  val auth1Codec = Macros.createCodecProviderIgnoreNone[OAuth1Info]()
  val auth2Codec = Macros.createCodecProviderIgnoreNone[OAuth2Info]()
  val social1Codec = Macros.createCodecProviderIgnoreNone[SocialOAuth1]()
  val social2Codec = Macros.createCodecProviderIgnoreNone[SocialOAuth2]()

  val registry = CodecRegistries.fromRegistries(
    CodecRegistries.fromProviders(
      rolePermissionsCodec,
      refreshTokenCodec,
      sessionCodec,
      clientAppCodec,
      branchCodec,
      passwordInfoCodec,
      userCodec,
      userAuthInfoCodec,
      loginCodec,
      auth1Codec,
      auth2Codec,
      social1Codec,
      social2Codec
    ),
    DEFAULT_CODEC_REGISTRY
  )
}
