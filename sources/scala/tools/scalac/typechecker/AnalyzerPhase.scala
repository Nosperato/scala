/*     ____ ____  ____ ____  ______                                     *\
**    / __// __ \/ __// __ \/ ____/    SOcos COmpiles Scala             **
**  __\_ \/ /_/ / /__/ /_/ /\_ \       (c) 2002, LAMP/EPFL              **
** /_____/\____/\___/\____/____/                                        **
\*                                                                      */

// $Id$

import scala.tools.util.Position;
import scalac._;
import scalac.symtab._;
import scalac.util._;
import scalac.ast._;
import java.util.HashMap;
import java.util.ArrayList;
import scala.tools.scalac.util.NewArray;
import scalac.typechecker.{AnalyzerPhase => scalac_AnalyzerPhase}
import scalac.{Global => scalac_Global}

package scala.tools.scalac.typechecker {

class NamerPhase(global0: scalac_Global, descriptor0: PhaseDescriptor)
  extends Phase(global0, descriptor0)
{
  override def apply(unit: CompilationUnit): Unit = {
    // change phase to make sure that no setInfo occurs before phase ANALYZER
    val analyzer = global.PHASE.ANALYZER.phase().asInstanceOf[AnalyzerPhase];
    val backup = global.currentPhase;
    global.currentPhase = analyzer;
    new Analyzer(global, analyzer).lateEnter(unit);
    global.currentPhase = backup;
  }
}

class AnalyzerPhase(global: scalac_Global, descriptor: PhaseDescriptor) extends scalac_AnalyzerPhase(global, descriptor) {

  var startContext = new Context(
    Tree.Empty,
    global.definitions.ROOT_CLASS,
    global.definitions.ROOT_CLASS.members(),
    Context.NONE);
  startContext.enclClass = startContext;

  if (!global.noimports) {
    startContext = addImport(startContext, global.definitions.JAVALANG);
    startContext = addImport(startContext, global.definitions.SCALA);
  }
  
  if (!global.noimports && !global.nopredefs) {
    startContext = addImport(startContext, global.definitions.PREDEF);
  }

  startContext = new Context(
    Tree.Empty, 
    startContext.owner, 
    global.definitions.ROOT_CLASS.members(), 
    startContext);

  var consoleContext = new Context(
    Tree.Empty,
    global.definitions.ROOT_CLASS,
    global.definitions.ROOT_CLASS.members(),
    startContext);

  val contexts = new HashMap/*<CompilationUnit,Context>*/();

  override def addConsoleImport(module: Symbol): unit =
    consoleContext = addImport(consoleContext, module);
  
  private def addImport(context: Context, module: Symbol): Context = {
    global.prevPhase();
    val tree = gen.mkImportAll(Position.NOPOS, module);
    global.nextPhase();
    val c = new Context(tree, context.owner, new Scope(), context);
    c.depth = context.depth;
    c
  }

  override def apply(unit: CompilationUnit): Unit =
    new Analyzer(global, this).apply(unit);

}
}
