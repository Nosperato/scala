/*     ____ ____  ____ ____  ______                                     *\
**    / __// __ \/ __// __ \/ ____/    SOcos COmpiles Scala             **
**  __\_ \/ /_/ / /__/ /_/ /\_ \       (c) 2002, LAMP/EPFL              **
** /_____/\____/\___/\____/____/                                        **
\*                                                                      */

// $Id$

/*
**       The Global analysis phase.
**
**  We add a new phase in the compiler, WholeProgPhase.
**
** [iuli]   3.03.2004                                                   */

import scalac.{Global => scalac_Global}
import scalac.transformer.{WholeProgPhase => scalac_WholeProgPhase}
import scalac.PhaseDescriptor;
import scalac.CompilationUnit;
import scalac.util.Debug;

package scala.tools.scalac.wholeprog {

/**
 * This phase analyzes the whole program and tries to derive some 
 * useful facts about it: which classes can be marked final, what 
 * methods, fields are never used, and monomorphic call-sites.
 */
class WholeProgPhase(global: scalac_Global, descriptor: PhaseDescriptor) 
    extends scalac_WholeProgPhase (global, descriptor) { 
 
 
  /* Apply the global analysis phase to the given units */
  def applyAll(units: Array[CompilationUnit]): unit = {

    if (!global.args.XdotFile.value.equals("$")) {
      val dotFilePrinter = new PrintDotFile(units);
      dotFilePrinter.makeDotFile(global.args.XdotFile.value);
    }

    if (!global.args.XrootClass.value.equals("$")) {

      var builder: ApplicationBuilder = new ApplicationBuilder(global);
      builder.buildApplication(global.args.XrootClass.value, units);
    }

    if (!global.args.XdotFile.value.equals("$")) {
      val dotFilePrinter = new PrintDotFile(units);
      dotFilePrinter.makeDotFile(global.args.XdotFile.value + "2");
    }
  }

  override def apply(unit: CompilationUnit): Unit =
    throw Debug.abort("!!! Phase " + this + " is currently disabled");

}

}
