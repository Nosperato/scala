/*     ____ ____  ____ ____  ______                                     *\
**    / __// __ \/ __// __ \/ ____/    SOcos COmpiles Scala             **
**  __\_ \/ /_/ / /__/ /_/ /\_ \       (c) 2002, LAMP/EPFL              **
** /_____/\____/\___/\____/____/                                        **
\*                                                                      */

// $Id$

import scalac.symtab._;
import scalac.transformer.{ICodePhase => scalac_ICodePhase}
import scalac.{Global => scalac_Global}
import scalac.{CompilationUnit => scalac_CompilationUnit}
import scalac.PhaseDescriptor;
import scalac.Phase;
import scalac.atree._;

import ch.epfl.lamp.util.CodePrinter;

package scala.tools.scalac.icode {




/** This class represents the ICode phase. This phase use the ATrees 
  * that represents the code with some primitives backends-like
  * and convert them in a inline form: The ICode (for intermediate
  * code). The ICode will be produced in the icode variable
  * of all AMethod objects. */
class ICodePhase(global: scalac_Global, descriptor: PhaseDescriptor) extends scalac_ICodePhase (global, descriptor) {
  
  // ##################################################
  // Public methods

  /* Apply the icode phase to the given units */
  override def apply(units: Array[scalac_CompilationUnit]) = {
    val units_it = Iterator.fromArray(units);
    
    units_it.foreach(translate);
  }
  
  /* Return a CodePrinter for the ICode */
  override def getPrinter(cp: CodePrinter) = new ICodePrinter(cp);

  // ##################################################
  // Private methods

  /** This method translates a single unit, it traverses all methods and
    * generates ICode for each of them */  
  private def translate(u: scalac_CompilationUnit) : unit = {
    def genClass(c: AClass) : unit = {
      val nestedClasses_it = Iterator.fromArray(c.classes());
      nestedClasses_it.foreach(genClass);
      val methods_it = Iterator.fromArray(c.methods());
      methods_it.foreach((m: AMethod) => {
	val label : String = m.symbol().name.toString();
	
	val ic : ICode = new ICode(label, global);
	ic.generate(m.code());
	
	// save the produced ICode
	m.icode = ic; 
	
      });
    }
    val classes_it = Iterator.fromArray(u.repository.classes());
    classes_it.foreach(genClass);
  }

}
}
