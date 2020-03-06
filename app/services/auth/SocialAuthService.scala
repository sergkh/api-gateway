package services.auth

import com.google.inject.Inject
import com.mohiva.play.silhouette.api.{AuthInfo, LoginInfo}
import com.mohiva.play.silhouette.impl.providers.{OAuth1Info, OAuth2Info}
import models.auth._
import play.api.libs.json.Json
import play.modules.reactivemongo.ReactiveMongoApi
import reactivemongo.play.json._
import reactivemongo.play.json.collection.JSONCollection

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.reflect.runtime.universe._
import play.api.libs.json.JsObject
import models.auth.UserAuthInfo._
import models.auth.{SocialOAuth1, SocialOAuth2, UserAuthInfo}
import play.api.libs.json.{JsObject, Json}
import reactivemongo.play.json._

/**
  * Created by faiaz on 16.05.17.
  */
class SocialAuthService @Inject()(reactiveMongoApi: ReactiveMongoApi) {

  private def collection = reactiveMongoApi.database.map(_.collection[JSONCollection](UserAuthInfo.COLLECTION_NAME))

  def retrieve[A <: AuthInfo: TypeTag](uuid: String, loginInfo: LoginInfo): Future[Option[AuthInfo]] = {
    collection.flatMap(_.find(byId(uuid)).one[UserAuthInfo]) map {
      case Some(userInfo) => typeOf[A] match {
        case _: OAuth1Info => userInfo.auth1Info.find(_.loginInfo == loginInfo).map(_.auth)
        case _: OAuth2Info => userInfo.auth2Info.find(_.loginInfo == loginInfo).map(_.auth)
      }
      case _ => None
    }
  }

  def save(uuid: String, loginInfo: LoginInfo, auth: AuthInfo): Future[AuthInfo] = {
    collection.flatMap(_.update.one(byId(uuid), saveQuery(uuid, loginInfo, auth), upsert = true)).map(_ => auth)
  }

  def update(uuid: String, loginInfo: LoginInfo, auth: AuthInfo): Future[AuthInfo] = {
    isExist(uuid, loginInfo) flatMap  {
      case None => collection.flatMap(_.update.one(byId(uuid), updateQuery(loginInfo, auth))).map(_ => auth)
      case Some(_) => Future.successful(auth)
    }
  }


  def findUserUuid(socId: String): Future[Option[String]] = {
    collection.flatMap(_.find(
      Json.obj("$or" -> Seq(
        findByProviderKeyQuery(socId, UserAuthInfo.OAUTH1),
        findByProviderKeyQuery(socId, UserAuthInfo.OAUTH2))
      )
    ).one[UserAuthInfo]).map(_.map(_.uuid))
  }

  def retrieveAllSocialInfo(uuid: String): Future[List[String]] = {
    collection.flatMap(_.find(byId(uuid)).one[UserAuthInfo]) map {
      case Some(userInfo) => userInfo.auth1Info.map(_.loginInfo.providerKey) ::: userInfo.auth2Info.map(_.loginInfo.providerKey)
      case _ => Nil
    }
  }

  def removeOne[A <: AuthInfo: TypeTag](uuid: String, loginInfo: LoginInfo): Future[Unit] = {
    val removeObj = typeOf[A] match {
      case _: OAuth1Info => removeOneQuery(loginInfo, UserAuthInfo.OAUTH1)
      case _: OAuth2Info => removeOneQuery(loginInfo, UserAuthInfo.OAUTH1)
    }
    collection.flatMap(_.update(byId(uuid), removeObj)).map(_ => {})
  }

  def removeAll(uuid: String): Future[Unit] = {
    collection.flatMap(_.remove(byId(uuid))).map(_ => {})
  }

  private def isExist(uuid: String, loginInfo: LoginInfo): Future[Option[UserAuthInfo]] = {
    collection.flatMap(_.find(byId(uuid)).one[UserAuthInfo] map {
        case Some(userInfo) =>
          val exist = userInfo.auth1Info.exists(_.loginInfo == loginInfo) || userInfo.auth2Info.exists(_.loginInfo == loginInfo)
          if (exist) Some(userInfo) else None
        case _ => None
      })
    }


  def byId(uuid: String): JsObject = Json.obj("_id" -> uuid)

  def updateQuery(loginInfo: LoginInfo, auth: AuthInfo): JsObject = {
    val jsValue = auth match {
      case oauth1: OAuth1Info => Json.obj(UserAuthInfo.OAUTH1 -> Json.toJson(SocialOAuth1(loginInfo, oauth1)))
      case oauth2: OAuth2Info => Json.obj(UserAuthInfo.OAUTH2 -> Json.toJson(SocialOAuth2(loginInfo, oauth2)))
    }
    Json.obj("$push" -> jsValue)
  }

  def saveQuery(uuid: String, loginInfo: LoginInfo, auth: AuthInfo): JsObject = {
    auth match {
      case oauth1: OAuth1Info => Json.toJson(UserAuthInfo(uuid, auth1Info = List(SocialOAuth1(loginInfo, oauth1)))).as[JsObject]
      case oauth2: OAuth2Info => Json.toJson(UserAuthInfo(uuid, auth2Info = List(SocialOAuth2(loginInfo, oauth2)))).as[JsObject]
    }
  }

  def findByProviderKeyQuery(socialId: String, key: String): JsObject = Json.obj(key -> Json.obj("$elemMatch" -> Json.obj("loginInfo.providerKey" -> socialId)))

  def removeOneQuery(loginInfo: LoginInfo, authType: String): JsObject = Json.obj("$pull" -> Json.obj(authType -> Json.obj("loginInfo" -> loginInfo)))    
}
