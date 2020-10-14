package xyz.hyperreal.spider

import java.nio.file.{Files, Path}
import java.util.{Timer, TimerTask}

import akka.actor.{Actor, ActorLogging, ActorSystem, Props, ReceiveTimeout}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.Uri.{Path => Uripath}
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.RawHeader
import akka.util.ByteString
import akka.pattern.pipe

import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.util.{Failure, Success}
import scala.concurrent.ExecutionContext.Implicits.global

object Main extends App {

  val site = "http://asdf.com" //"http://www.autodealerdirectory.us/" // "https://postman-echo.com/get/"
  val path = "sites"
  val base = Path.of(path)

  trait Action
  object AcceptAction extends Action
  object RejectAction extends Action
  object StoreAction extends Action
  case class ProcessAction(processor: (Uri, String) => Unit)

  val actionsList =
    List(
//      ("""[a-z]{2}_s_.*""".r, RejectAction),
      (""".*\.html""".r, AcceptAction),
      (null, StoreAction)
    )

  implicit val system: ActorSystem = ActorSystem("spider")

  val response = system.actorOf(Props[ResponseActor](), "response")
  val hrefRegex = """href\s*=\s*"([^"]+)"""" r
  val requested = new mutable.HashSet[Uri]
  val timeout = new Timer(true)
  var task: TimerTask = _
  val headers =
    List(
      RawHeader("user-agent", "Mozilla/5.0 (X11; Ubuntu; Linux x86_64; rv:81.0) Gecko/20100101 Firefox/81.0")
    )

  submit(site, null)

  if (task eq null)
    shutdown

  def shutdown = Http().shutdownAllConnectionPools().andThen(_ => system.terminate())

  def kick(): Unit = synchronized {
    if (task ne null)
      task.cancel()

    task = new TimerTask {
      def run(): Unit = shutdown
    }

    timeout.schedule(task, 20000)
  }

  def linkActions(link: String): Option[Seq[Action]] = {
    val buf = new ListBuffer[Action]

    for ((p, a) <- actionsList)
      if (p eq null)
        buf += a
      else
        (p.matches(link), a) match {
          case (true, RejectAction) | (false, AcceptAction) => return None
          case (true, AcceptAction)                         =>
          case (true, _)                                    => buf += a
          case _                                            =>
        }

    if (buf.isEmpty) None
    else Some(buf.toSeq)
  }

  def submit(url: String, page: Uri) = {
    val u = Uri(url).withoutFragment

    linkActions(root(u).toString) match {
      case None => system.log.warning(s"no actions found for link $url")
      case Some(a) =>
        if (!u.isEmpty && u.isRelative)
          if (page eq null)
            system.log.error(s"url is relative: $url")
          else
            request(u.resolvedAgainst(page), a)
        else if (!u.isEmpty && u.isAbsolute && (page == null || u.authority == page.authority))
          request(u, a)
        else
          system.log.info(s"Not requesting $u")
    }
  }

  def process(page: Uri, action: Seq[Action], body: String): Unit = {
    for (l <- hrefRegex.findAllMatchIn(body).map(_.group(1)))
      submit(l, page)

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

  def request(uri: Uri, action: Seq[Action]) = {
    val u = root(uri)

    if (!requested(u)) {
      kick()
      requested += u
      system.log.info(s"Requesting page $uri")
      Http() singleRequest HttpRequest(uri = uri, headers = headers) map (r => (uri, action, r)) pipeTo response
    }
  }

  class ResponseActor extends Actor with ActorLogging {

    implicit val system: ActorSystem = context.system

    def receive: Receive = {
      case (uri: Uri, actions: Seq[_], HttpResponse(StatusCodes.OK, _, entity, _)) =>
        kick()
        entity.dataBytes.runFold(ByteString(""))(_ ++ _).map(_.utf8String).onComplete {
          case Success(body) =>
            log.info(s"Got response, body: uri = $uri, length = ${body.length}")
            process(uri, actions.asInstanceOf[Seq[Action]], body)
          case Failure(e) =>
            log.error(s"Got response, failed to get body: uri = $uri, exception = $e")
        }
      case (uri, _, resp @ HttpResponse(code, _, _, _)) =>
        log.warning(s"Request failed: uri = $uri, code = $code")
        resp.discardEntityBytes()
    }

  }

}
