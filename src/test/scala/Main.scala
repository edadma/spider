package xyz.hyperreal.spider

import java.nio.file.{Files, Path}
import java.util.{Timer, TimerTask}

import akka.actor.{Actor, ActorLogging, ActorSystem, Props, ReceiveTimeout}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.Uri.{Path => Uripath}
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.settings.ConnectionPoolSettings
import akka.util.ByteString
import akka.pattern.pipe

import scala.collection.mutable
import scala.util.{Failure, Success}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._

object Main extends App {

  val site = "http://www.autodealerdirectory.us/" //"http://asdf.com" // "https://postman-echo.com/get/"
  val path = "sites"
  val base = Path.of(path)

  implicit val system: ActorSystem = ActorSystem("spider")

  val response = system.actorOf(Props[ResponseActor](), "response")
  val hrefRegex = """href\s*=\s*"([^"]+)"""" r
  val requested = new mutable.HashSet[Uri]
  val timeout = new Timer(true)
  var task: TimerTask = null

  def kick(): Unit = synchronized {
    if (task ne null)
      task.cancel()

    task = new TimerTask {
      def run(): Unit = Http().shutdownAllConnectionPools().andThen(_ => system.terminate())
    }

    timeout.schedule(task, 20000)
  }

  def process(page: Uri, body: String): Unit = {
    for (l <- hrefRegex.findAllMatchIn(body).map(_.group(1))) {
      val u = Uri(l).withoutFragment

      if (!u.isEmpty && u.isRelative)
        request(u.resolvedAgainst(page))
      else if (!u.isEmpty && u.isAbsolute && u.authority == page.authority)
        request(u)
    }

    val path = filepath(root(page).path, base.resolve(page.authority.host.address))

    system.log.info(s"Writing file to path $path")
    Files.createDirectories(path.getParent)
    Files.writeString(path, body)
  }

  @scala.annotation.tailrec
  def filepath(upath: Uripath, path: Path): Path =
    if (upath.startsWithSlash)
      filepath(upath.tail, path)
    else if (upath.isEmpty)
      path
    else
      filepath(upath.tail, path.resolve(upath.head.toString))

  def root(uri: Uri) =
    if (uri.path.isEmpty || uri.path == Uripath("/"))
      uri.withPath(Uripath("/_root_.html"))
    else
      uri

  val headers =
    List(RawHeader("user-agent", "Mozilla/5.0 (X11; Ubuntu; Linux x86_64; rv:81.0) Gecko/20100101 Firefox/81.0"))

  def request(uri: Uri) = {
    if (uri.path.isEmpty || uri.path.endsWithSlash || uri.path.endsWith(".html")) {
      val u = root(uri)

      if (!requested(u)) {
        kick()
        requested += u
        system.log.info(s"Requesting page $uri")
        Http() singleRequest HttpRequest(uri = uri, headers = headers) map (r => (uri, r)) pipeTo response
      }
    }
  }

  request(Uri(site))

  class ResponseActor extends Actor with ActorLogging {

    implicit val system: ActorSystem = context.system

    def receive: Receive = {
      case (uri: Uri, HttpResponse(StatusCodes.OK, _, entity, _)) =>
        kick()
        entity.dataBytes.runFold(ByteString(""))(_ ++ _).map(_.utf8String).onComplete {
          case Success(body) =>
            log.info(s"Got response, body: uri = $uri, length = ${body.length}")
            process(uri, body)
          case Failure(e) =>
            log.error(s"Got response, failed to get body: uri = $uri, exception = $e")
        }
      case (uri, resp @ HttpResponse(code, _, _, _)) =>
        log.warning(s"Request failed: uri = $uri, code = $code")
        resp.discardEntityBytes()
    }

  }

}
