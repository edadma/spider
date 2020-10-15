package xyz.hyperreal.spider

import java.nio.file.Path
import java.util.{Timer, TimerTask}

import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.model.Uri.Path.Segment
import akka.http.scaladsl.model.Uri.{Path => Uripath}

import scala.util.Using

object Main extends App {
  val dealers = Using(io.Source.fromFile("sites/www.autodealerdirectory.us/subaru_m43m_madd.html"))(_.mkString).get

  val entityRegex = "&(?:([a-z]+)|#([0-9]+));" r
  val spaceRegex = """\s+""" r

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

  val s = new Scanning(dealers)

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

        s.move(6)

        val phone = fix(s.tab(s.find("ID").get).get)

        s.move(2)
        buf ++= s""""$name", "$addr1, $addr2", "$phone""""
        buf += '\n'
        leads(buf)
    }
  }

  println(leads())
}
