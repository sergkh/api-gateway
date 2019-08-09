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

/**
  * Created by faiaz on 16.05.17.
  */
class SocialAuthService @Inject()(reactiveMongoApi: ReactiveMongoApi) extends AuthInfoServiceHelper {

  private def collection = reactiveMongoApi.database.map(_.collection[JSONCollection](UserAuthInfo.COLLECTION_NAME))

  def retrieve[A <: AuthInfo: TypeTag](uuid: Long, loginInfo: LoginInfo): Future[Option[AuthInfo]] = {
    collection.flatMap(_.find(searchQuery(uuid)).one[UserAuthInfo]) map {
      case Some(userInfo) => typeOf[A] match {
        case _: OAuth1Info => userInfo.auth1Info.find(_.loginInfo == loginInfo).map(_.auth)
        case _: OAuth2Info => userInfo.auth2Info.find(_.loginInfo == loginInfo).map(_.auth)
      }
      case _ => None
    }
  }

  def save(uuid: Long, loginInfo: LoginInfo, auth: AuthInfo): Future[AuthInfo] = {
    collection.flatMap(_.update(searchQuery(uuid), saveQuery(uuid, loginInfo, auth), upsert = true)).map(_ => auth)
  }

  def update(uuid: Long, loginInfo: LoginInfo, auth: AuthInfo): Future[AuthInfo] = {
    isExist(uuid, loginInfo) flatMap  {
      case None => collection.flatMap(_.update(searchQuery(uuid), updateQuery(loginInfo, auth))).map(_ => auth)
      case Some(_) => Future.successful(auth)
    }
  }


  def findUserUuid(socId: String): Future[Option[Long]] = {
    collection.flatMap(_.find(
      Json.obj("$or" -> Seq(
        findByProviderKeyQuery(socId, UserAuthInfo.OAUTH1),
        findByProviderKeyQuery(socId, UserAuthInfo.OAUTH2))
      )
    ).one[UserAuthInfo]).map(_.map(_.uuid))
  }

  def retrieveAllSocialInfo(uuid: Long): Future[List[String]] = {
    collection.flatMap(_.find(searchQuery(uuid)).one[UserAuthInfo]) map {
      case Some(userInfo) => userInfo.auth1Info.map(_.loginInfo.providerKey) ::: userInfo.auth2Info.map(_.loginInfo.providerKey)
      case _ => Nil
    }
  }

  def removeOne[A <: AuthInfo: TypeTag](uuid: Long, loginInfo: LoginInfo): Future[Unit] = {
    val removeObj = typeOf[A] match {
      case _: OAuth1Info => removeOneQuery(loginInfo, UserAuthInfo.OAUTH1)
      case _: OAuth2Info => removeOneQuery(loginInfo, UserAuthInfo.OAUTH1)
    }
    collection.flatMap(_.update(searchQuery(uuid), removeObj)).map(_ => {})
  }

  def removeAll(uuid: Long): Future[Unit] = {
    collection.flatMap(_.remove(searchQuery(uuid))).map(_ => {})
  }

  private def isExist(uuid: Long, loginInfo: LoginInfo): Future[Option[UserAuthInfo]] = {
    collection.flatMap(_.find(searchQuery(uuid)).one[UserAuthInfo] map {
        case Some(userInfo) =>
          val exist = userInfo.auth1Info.exists(_.loginInfo == loginInfo) || userInfo.auth2Info.exists(_.loginInfo == loginInfo)
          if (exist) Some(userInfo) else None
        case _ => None
      })
    }
}
