/*     ____ ____  ____ ____  ______                                     *\
**    / __// __ \/ __// __ \/ ____/    SOcos COmpiles Scala             **
**  __\_ \/ /_/ / /__/ /_/ /\_ \       (c) 2002, LAMP/EPFL              **
** /_____/\____/\___/\____/____/                                        **
\*                                                                      */

// $Id$

package scala.tools.nsc.reporters;
import scala.collection.mutable.HashSet;
import scala.tools.nsc.util.Position;

/**
 * This reporter implements filtering. 
 */
abstract class AbstractReporter extends Reporter {
  private val positions = new HashSet[Position]();

  def display(pos : Position, msg : String, severity : Severity) : Unit;
  var prompt : Boolean = false;
  var verbose : Boolean = false;
  var nowarn  : Boolean = false;
  def displayPrompt : Unit;

  protected def info0(pos : Position, msg : String, severity : Severity, force : Boolean) : Unit = severity match {
    case INFO    => if (force || verbose) display(pos, msg, severity);
    case WARNING => {
      val hidden = testAndLog(pos);
      if (!nowarn) {
	if (!hidden || prompt) display(pos, msg, severity);
	if (prompt) displayPrompt;
      }
    }
    case ERROR => {
      val hidden = testAndLog(pos);
      if (!hidden || prompt) display(pos, msg, severity);
      if (prompt) displayPrompt;
    }
  }

  //########################################################################
  // Private Methods

  /** Logs a position and returns true if it was already logged. */
  private def testAndLog(pos : Position) : Boolean = {
    if (pos eq null) return false;
    if (pos.column == 0) return false;
    if (positions.contains(pos)) return true;
    positions += (pos);
    return false;
  }

  //########################################################################
}
