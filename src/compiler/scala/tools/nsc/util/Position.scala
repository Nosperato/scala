/*                     __                                               *\
**     ________ ___   / /  ___     Scala API                            **
**    / __/ __// _ | / /  / _ |    (c) 2003-2004, LAMP/EPFL             **
**  __\ \/ /__/ __ |/ /__/ __ |                                         **
** /____/\___/_/ |_/____/_/ | |                                         **
**                          |/                                          **
\*                                                                      */

// $Id$
package scala.tools.nsc.util

abstract class Position {
  def offset : Option[Int] = None
  def line   : Option[Int] = None
  def column : Option[Int] = None
  def source : Option[SourceFile] = None                     
  def lineContent: String =
    if (!line.isEmpty && !source.isEmpty) source.get.lineToString(line.get - 1)
    else "NO_LINE"
  /** Map this position to a position in an original source
   * file.  If the SourceFile is a normal SourceFile, simply
   * return this.
   */
  def inUltimateSource = if (!source.isEmpty) source.get.positionInUltimateSource(this)
                         else this
  
  def dbgString = {
    (if (source.isEmpty) "" else "source-" + source.get.path) +
      (if (line.isEmpty) "" else "line-" + line.get) +
        (if (offset.isEmpty || source.isEmpty) "" 
         else if (offset.get >= source.get.content.length) "out-of-bounds-" + offset.get 
         else {
           val ret = "offset=" + offset.get;
           var add = "";
           while (offset.get + add.length < source.get.content.length &&
                  add.length < 10) add = add + source.get.content(offset.get + add.length());
           ret + " c[0..9]=\"" + add + "\"";
         })
  }
      
}

object NoPosition extends Position;
case class FakePos(msg : String) extends Position;

case class LinePosition(line0 : Int, override val source : Option[SourceFile]) extends Position {
  def this(line0 : Int) = this(line0, None)
  assert(line0 >= 1)
  override def offset = None
  override def column = None
  override def line = Some(line0)
}
case class OffsetPosition(source0 : SourceFile, offset0 : Int) extends Position {
  private val tabInc = 8
  override def source = Some(source0)
  override def offset = Some(offset0)
  override def line = Some(source0.offsetToLine(offset0) + 1)
  override def column = {
    var column = 1
    // find beginning offset for line
    val line = source0.offsetToLine(offset0)
    var coffset = source0.lineToOffset(line)
    var continue = true
    while (continue) {
      if (coffset == offset.get(-1)) continue = false
      else if (source0.content(coffset) == '\t') column = ((column - 1) / tabInc * tabInc) + tabInc + 1
      else column = column + 1
      coffset = coffset + 1
    }
    Some(column)
  }
}