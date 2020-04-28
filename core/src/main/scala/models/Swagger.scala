package models

import play.api.libs.json._
import org.checkerframework.checker.units.qual.s

case class Components(data: JsObject) {
  def toJson: JsObject = data
  def mergeIn(other: Components): Components = Components(data.deepMerge(other.data))
}

case class Swagger(
    version: String,
    info: JsObject,
    servers: List[JsObject],
    tags: Map[String, JsObject],
    paths: List[(String, JsValue)],
    components: Option[Components],
    security: Option[JsObject],
    externalDocs: Option[JsObject]
) {
  def withInfo(title: String, version: String): Swagger = copy(info =
    info.as[JsObject] ++ Json.obj("title" -> title,  "version" -> version)
  )

  def prefixPaths(prefix: String): Swagger = { 
    val normalizedPrefix = prefix.stripSuffix("/")
    if (normalizedPrefix.isEmpty()) {
      this
    } else {
      copy(paths = paths.map {
        case ("/", js) => (prefix, js)
        case (key, js) => (prefix + key, js)
      })
    }
  }

  def mergeIn(other: Swagger): Swagger = Swagger(
    version,
    info,
    servers,
    other.tags ++ tags,
    other.paths ++ paths,
    components.map(c => 
      other.components.map(otherC => c.mergeIn(otherC)).getOrElse(c)
    ) orElse other.components,
    security,
    externalDocs
  )

  def toJson: JsObject = Json.obj(
    "openapi" -> version,
    "info" -> info,
    "servers" -> servers,
    "tags" -> tags.values,
    "paths" -> JsObject(paths),
    "components" -> components.map(_.toJson),
    "security" -> security,
    "externalDocs" -> externalDocs
  )
}

object Swagger {
  val empty = Swagger(
    version = "",
    info = JsObject.empty,
    servers = Nil,
    tags = Map.empty,
    paths = Nil,
    components = None,
    security = None,
    externalDocs = None
  )

  def apply(swagger: JsObject): Swagger = Swagger(
    (swagger \ "openapi").as[String],
    (swagger \ "info").asOpt[JsObject].getOrElse(JsObject.empty),
    (swagger \ "servers").asOpt[JsObject].map(_.as[List[JsObject]]).getOrElse(Nil),
    parseTags(swagger),
    parsePaths(swagger),
    (swagger \ "components").asOpt[JsObject].map(Components.apply),
    (swagger \ "security").asOpt[JsObject],
    (swagger \ "externalDocs").asOpt[JsObject]
  )

  private def parseTags(swagger: JsObject): Map[String, JsObject] = {
    (swagger \ "tags").asOpt[JsObject].map(_.as[List[JsObject]]).getOrElse(Nil).map { jsTag =>
      (jsTag \ "name").as[String] -> jsTag
    }.toMap
  }

  private def parsePaths(swagger: JsObject): List[(String, JsValue)] = {
    (swagger \ "paths").asOpt[JsObject].map(_.fields.toList).getOrElse(Nil)
  }


}