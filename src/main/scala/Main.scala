package xyz.hyperreal.spider

import java.nio.file.Path
import java.util.{Timer, TimerTask}

import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.model.Uri.Path.Segment
import akka.http.scaladsl.model.Uri.{Path => Uripath}

object Main extends App {
  val timeout = new Timer(true)
  var task = new TimerTask {
    def run(): Unit = {
      println("timeout")
    }
  }

  println(task.scheduledExecutionTime())
  timeout.schedule(task, 1000)
  println(task.scheduledExecutionTime())
}
