package xyz.hyperreal.spider

class Scanning(subject: String) {

  private var pointer = 0

  private def set(pos: Int) = {
    val old = pointer

    pointer = pos
    (old, pointer)
  }

  private def convert(pos: Int) =
    if (pos <= 0) subject.length + pos
    else pos - 1

  def move(i: Int): Option[String] = {
    val p = pointer + i

    if (p < 0 || p > subject.length) None
    else {
      val (o, n) = set(p)

      Some(subject.substring(o, n))
    }
  }

  def tab(pos: Int): Option[String] =
    if (-subject.length <= pos && pos <= subject.length + 1) {
      val (o, n) = set(convert(pos))

      Some(subject.substring(o, n))
    } else
      None

  def find(s: String): Option[Int] =
    subject indexOf (s, pointer) match {
      case -1  => None
      case idx => Some(idx + 1)
    }

  def pos(i: Int): Boolean = convert(i) == pointer

}
