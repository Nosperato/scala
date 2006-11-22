/* NSC -- new Scala compiler
 * Copyright 2005-2006 LAMP/EPFL
 * @author  Martin Odersky
 */
// $Id$

package scala.tools.nsc

import scala.tools.nsc.util.{FreshNameCreator,Position,SourceFile}
import scala.tools.nsc.io.AbstractFile
import scala.collection.mutable.HashSet

trait CompilationUnits requires Global {

  class CompilationUnit(val source: SourceFile) {

    /** the fresh name creator */
    var fresh = new FreshNameCreator

    /** the content of the compilation unit in tree form */
    var body: Tree = EmptyTree

    val depends = new HashSet[AbstractFile]

    def position(pos: int) = new Position(source, pos)

    val errorPositions = new HashSet[int]

    def error(pos: int, msg: String) =
      if (!(errorPositions contains pos)) {
        errorPositions += pos
        reporter.error(position(pos), msg)
      }

    def warning(pos: int, msg: String) = reporter.warning(position(pos), msg)

    def deprecationWarning(pos: int, msg: String) = 
      if (settings.deprecation.value) reporter.warning(position(pos), msg)
      else currentRun.deprecationWarnings = true

    def uncheckedWarning(pos: int, msg: String) = 
      if (settings.unchecked.value) reporter.warning(position(pos), msg)
      else currentRun.uncheckedWarnings = true

    override def toString() = source.toString()

    def clear() = {
      fresh = null
      body = null
      depends.clear
      errorPositions.clear
    }
  }
}


