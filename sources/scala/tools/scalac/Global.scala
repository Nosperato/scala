/*     ____ ____  ____ ____  ______                                     *\
**    / __// __ \/ __// __ \/ ____/    SOcos COmpiles Scala             **
**  __\_ \/ /_/ / /__/ /_/ /\_ \       (c) 2002, LAMP/EPFL              **
** /_____/\____/\___/\____/____/                                        **
**                                                                      **
** $Id: Global.scala
\*                                                                      */

import scalac.{CompilationUnit, CompilerCommand, Global => scalac_Global};
import scalac.ast.printer.TreePrinter;
import scalac.backend.jvm.GenJVM;
import scalac.backend.msil.GenMSIL;
import scala.tools.scalac.backend.GenJVMFromICode;

package scala.tools.scalac {

import ast.printer._;
import java.io.PrintWriter;
import typechecker.Infer;

/** The global environment of a compiler run
 *
 */
class Global(args: CompilerCommand, interpret: boolean) extends scalac_Global(args, interpret) {
  
  def this(args: CompilerCommand) = this(args, false);

  override def newInfer(): Infer =
    new Infer(this, treeGen, make);
  override def newTextTreePrinter(writer: PrintWriter): TreePrinter =
    new TextTreePrinter(this, writer);
  override def newHTMLTreePrinter(writer: PrintWriter): TreePrinter =
    new HTMLTreePrinter(this, writer);
  override def newSwingTreePrinter(writer: PrintWriter): TreePrinter =
    new SwingTreePrinter(this);

  override def dump(units: Array[CompilationUnit]): Unit = {
    if (target == scalac_Global.TARGET_JVM) {
      GenJVM.translate(this, units);
    } else if (target == scalac_Global.TARGET_MSIL) {
      GenMSIL.translate(this, units);
    } else if (target == scalac_Global.TARGET_JVMFROMICODE) {
      GenJVMFromICode.translate(this, units);
    }
    symdata.clear();
  }

}
}
