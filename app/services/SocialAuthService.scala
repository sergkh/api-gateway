package services

import com.google.inject.Inject
import com.mohiva.play.silhouette.api.{AuthInfo, LoginInfo}
import com.mohiva.play.silhouette.impl.providers.{OAuth1Info, OAuth2Info}
import com.mongodb.client.model.UpdateOptions
import models.{SocialOAuth1, SocialOAuth2, UserAuthInfo}
import org.mongodb.scala.model.Filters._
import org.mongodb.scala.model.Updates

import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.runtime.universe._

/**
  * Created by faiaz on 16.05.17.
  */
class SocialAuthService @Inject()(mongoApi: MongoApi)(implicit exec: ExecutionContext)  {
  val col = mongoApi.collection[UserAuthInfo](UserAuthInfo.COLLECTION_NAME)

  def retrieve[A <: AuthInfo: TypeTag](id: String, loginInfo: LoginInfo): Future[Option[AuthInfo]] = {
    col.find(equal("_id", id)).headOption().map {
      case Some(userInfo) => typeOf[A] match {
        case _: OAuth1Info => userInfo.auth1Info.find(_.loginInfo == loginInfo).map(_.auth)
        case _: OAuth2Info => userInfo.auth2Info.find(_.loginInfo == loginInfo).map(_.auth)
      }
      case _ => None
    }
  }

  def save(id: String, loginInfo: LoginInfo, auth: AuthInfo): Future[AuthInfo] = {
    col.updateOne(equal("_id", id), updateQuery(loginInfo, auth), new UpdateOptions().upsert(true))
       .toFuture().map(_ => auth)
  }

  def update(id: String, loginInfo: LoginInfo, auth: AuthInfo): Future[AuthInfo] = save(id, loginInfo, auth)

  def findUserUuid(socId: String): Future[Option[String]] = {
    col.find(or(
      elemMatch(UserAuthInfo.OAUTH1, equal("loginInfo.providerKey", socId)),
      elemMatch(UserAuthInfo.OAUTH2, equal("loginInfo.providerKey", socId))
    )).headOption().map(_.map(_.id))
  }

  def retrieveAllSocialInfo(id: String): Future[List[String]] = {
    col.find(equal("_id", id)).headOption().map {
      case Some(userInfo) => userInfo.auth1Info.map(_.loginInfo.providerKey) ::: userInfo.auth2Info.map(_.loginInfo.providerKey)
      case _ => Nil
    }
  }

  def removeOne[A <: AuthInfo: TypeTag](id: String, loginInfo: LoginInfo): Future[Unit] = {
    val removeObj = typeOf[A] match {
      case _: OAuth1Info => Updates.pull(UserAuthInfo.OAUTH1, equal("loginInfo", loginInfo))
      case _: OAuth2Info => Updates.pull(UserAuthInfo.OAUTH2, equal("loginInfo", loginInfo))
    }

    col.updateOne(equal("_id", id), removeObj).toFuture().map(_ => {})
  }

  def removeAll(id: String): Future[Unit] = col.deleteOne(equal("_id", id)).headOption().map(_ => ())

  private def updateQuery(loginInfo: LoginInfo, auth: AuthInfo) = auth match {
    case oauth1: OAuth1Info => Updates.push(UserAuthInfo.OAUTH1, SocialOAuth1(loginInfo, oauth1))
    case oauth2: OAuth2Info => Updates.push(UserAuthInfo.OAUTH2, SocialOAuth2(loginInfo, oauth2))
  }
}
