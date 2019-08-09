package models

import com.mohiva.play.silhouette.impl.providers.{OAuth1Info, OAuth2Info}
import play.api.libs.json.Json

/**
  * Created by faiaz on 19.04.17.
  */
case class ProvidersInfo(
                          uuid: Long,
                          providers: Seq[String],
                          oAuth1: OAuth1Info,
                          oAuth2: OAuth2Info
                        ) {

}
object ProvidersInfo {
  implicit val oAuth1InfoFmt = Json.format[OAuth1Info]
  implicit val oAuth2InfoFmt = Json.format[OAuth2Info]
  implicit val fmt = Json.format[ProvidersInfo]
}