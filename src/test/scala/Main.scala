package xyz.hyperreal.spider

import java.nio.file.{Files, Path}
import java.util.{Timer, TimerTask}

import akka.actor.{Actor, ActorLogging, ActorSystem, Props}
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

  val site = "http://www.autodealerdirectory.us/" // "http://asdf.com" //"https://postman-echo.com/get/"
  val actionsList =
    List(
      ("""[a-z]{2}_s_.*""".r, RejectAction),
      (""".*\.html""".r, AcceptAction),
//      (null, StoreAction),
      (null, ProcessAction(leads))
    )

  val path = "sites"
  val base = Path.of(path)

  def leads(page: Uri, body: ByteString): Unit = {
    val path = filepath(root(page).path, base.resolve(page.authority.host.address))
    val b = body.utf8String

    val entityRegex = "&(?:([a-z]+)|#([0-9]+));" r
    val spaceRegex = """\s+""" r
    val stateRegex = " [A-Z][A-Z] " r

    def fix(s: String) =
      spaceRegex.replaceAllIn(
        entityRegex
          .replaceAllIn(s, { m =>
            m.group(1) match {
              case null   => m.group(2).toInt.toChar.toString
              case "nbsp" => " "
              case "lt"   => "<"
              case "rt"   => ">"
              case "amp"  => "&"
            }
          })
          .trim,
        " "
      )

    val s = new Scanning(b)

    @scala.annotation.tailrec
    def leads(buf: StringBuilder = new StringBuilder): String = {
      s.find("<hr />") match {
        case None => buf.toString
        case Some(p) =>
          s.tab(p + 6)

          val name = fix(s.tab(s.find("<br />").get).get)

          s.move(6)

          val addr1 = fix(s.tab(s.find("<br />").get).get)

          s.move(6)

          val addr2 = fix(s.tab(s.find("<br />").get).get)
          val state = stateRegex.findFirstMatchIn(addr2).map(_.group(0).trim).getOrElse("")

          s.move(6)

          val phone = fix(s.tab(s.find("ID").get).get)

          s.move(2)
          buf ++= s""""$name","$addr1, $addr2",$state,"$phone""""
          buf += '\n'
          leads(buf)
      }
    }

    leads() match {
      case "" => system.log.info(s"No leads found in $page")
      case csv =>
        val leadpath = path.getParent.resolve(s"${path.getFileName.toString}.csv")

        system.log.info(s"Writing leads to path $leadpath")

        Files.createDirectories(leadpath.getParent)
        Files.writeString(leadpath, csv)
    }
  }

  implicit val system: ActorSystem = ActorSystem("spider")

  val response = system.actorOf(Props[ResponseActor](), "response")
  val hrefRegex = """href\s*=\s*"([^"]+)"""" r
  val requested = new mutable.HashSet[Uri]
  val timeout = new Timer(true)
  var task: TimerTask = _
  val headers =
    List(
      "user-agent" -> "Mozilla/5.0 (X11; Ubuntu; Linux x86_64; rv:81.0) Gecko/20100101 Firefox/81.0"
    )
  val rawheaders = headers map { case (k, v) => RawHeader(k, v) }

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
      case None => system.log.info(s"No actions found for link $url")
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

  def process(page: Uri, actions: Seq[Action], body: ByteString): Unit = {
    for (l <- hrefRegex.findAllMatchIn(body.utf8String).map(_.group(1)))
      submit(l, page)

    actions foreach {
      case StoreAction =>
        val path = filepath(root(page).path, base.resolve(page.authority.host.address))

        system.log.info(s"Writing file to path $path")
        Files.createDirectories(path.getParent)
        Files.write(path, body.toArray)
      case ProcessAction(processor) => processor(page, body)
    }
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
      Http() singleRequest HttpRequest(uri = uri, headers = rawheaders) map ((uri, action, _)) pipeTo response
    }
  }

  trait Action
  object AcceptAction extends Action
  object RejectAction extends Action
  object StoreAction extends Action
  case class ProcessAction(processor: (Uri, ByteString) => Unit) extends Action

  class ResponseActor extends Actor with ActorLogging {

    implicit val system: ActorSystem = context.system

    def receive: Receive = {
      case (uri: Uri, actions: Seq[_], HttpResponse(StatusCodes.OK, _, entity, _)) =>
        kick()
        entity.dataBytes.runFold(ByteString(""))(_ ++ _).onComplete {
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
