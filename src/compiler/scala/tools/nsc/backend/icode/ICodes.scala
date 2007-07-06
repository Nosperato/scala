/* NSC -- new scala compiler
 * Copyright 2005-2007 LAMP/EPFL
 * @author  Martin Odersky
 */

// $Id$

package scala.tools.nsc.backend.icode

import java.io.PrintWriter

import scala.collection.mutable.HashMap
import scala.tools.nsc.symtab._
import analysis.{Liveness, ReachingDefinitions}

/** Glue together ICode parts.
 *
 *  @author Iulian Dragos
 */
abstract class ICodes extends AnyRef
                                 with Members
                                 with BasicBlocks                
                                 with Opcodes
                                 with TypeStacks 
                                 with TypeKinds
                                 with ExceptionHandlers
                                 with Primitives
                                 with Linearizers
                                 with Printers
{
  val global: Global

  /** The ICode representation of classes */
  var classes: HashMap[global.Symbol, IClass] = new HashMap()

  /** The ICode linearizer. */
  val linearizer: Linearizer = 
    if (global.settings.Xlinearizer.value == "rpo")
      new ReversePostOrderLinearizer()
    else if (global.settings.Xlinearizer.value == "dfs")
      new DepthFirstLinerizer()
    else if (global.settings.Xlinearizer.value == "normal")
      new NormalLinearizer();
    else if (global.settings.Xlinearizer.value == "dump")
      new DumpLinearizer()
    else
      global.abort("Unknown linearizer: " + global.settings.Xlinearizer.value)

  /** Print all classes and basic blocks. Used for debugging. */
  def dump {
    val printer = new TextPrinter(new PrintWriter(Console.out, true),
                                  new DumpLinearizer)

    classes.values foreach { c => printer.printClass(c) }
  }

  object liveness extends Liveness {
    val global: ICodes.this.global.type = ICodes.this.global
  }

  object reachingDefinitions extends ReachingDefinitions {
    val global: ICodes.this.global.type = ICodes.this.global
  }

  var AnyRefReference: TypeKind = _
  def init = { 
    AnyRefReference = REFERENCE(global.definitions.AnyRefClass)
  }

  import global.settings
  if (settings.XO.value) {
    settings.inline.value = true
    settings.Xcloselim.value = true
    settings.Xdce.value = true
  }

  /** A phase which works on icode. */
  abstract class ICodePhase(prev: Phase) extends global.GlobalPhase(prev) {
    override def erasedTypes = true

    override def apply(unit: global.CompilationUnit) {
      unit.icode foreach { c => apply(c) }
    }
    
    def apply(cls: global.icodes.IClass): Unit
  }
}

