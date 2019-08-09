package kamon.play.models

import java.util.Locale

import org.slf4j.LoggerFactory
import play.api.mvc.RequestHeader
import play.api.routing.Router

/**
  *
  * @author Yaroslav Derman <yaroslav.derman@gmail.com>
  *         created on 27/06/17
  */
object KamonExtensions {

  private final val normalizePattern = """\$([^<]+)<[^>]+>""".r

  val SegmentLibraryName = "WS-client"
  val log = LoggerFactory.getLogger(getClass)

  def generateTraceName(request: RequestHeader): String = {

    val path = request.attrs.get(Router.Attrs.HandlerDef).map(_.path).getOrElse(request.path)

    // Convert paths of form GET /foo/bar/$paramname<regexp>/blah to foo.bar.paramname.blah.get
    val p = normalizePattern.replaceAllIn(path, "$1").replace('/', '.').dropWhile(_ == '.').stripSuffix(".")
    s"$p.${request.method.toLowerCase(Locale.ENGLISH)}"
  }

}
