/*     ____ ____  ____ ____  ______                                     *\
**    / __// __ \/ __// __ \/ ____/    SOcos COmpiles Scala             **
**  __\_ \/ /_/ / /__/ /_/ /\_ \       (c) 2002, LAMP/EPFL              **
** /_____/\____/\___/\____/____/                                        **
**                                                                      **
** $Id$
\*                                                                      */

package scalac;

import scala.tools.util.Position;
import scala.tools.util.SourceFile;

import scalac.ast.Tree;
import scalac.atree.ARepository;
import scalac.util.FreshNameCreator;
import java.util.HashMap;


/** A representation for a compilation unit in scala
 *
 *  @author     Matthias Zenger
 *  @version    1.0
 */
public class Unit {

    /** the global compilation environment
     */
    public final Global global;

    /** the associated source code file
     */
    public final SourceFile source;

    /** does this unit come from the interpreter console
     */
    public final boolean console;

    /** is this unit only there for mixin expansion?
     */
    public final boolean mixinOnly;

    /** the fresh name creator
     */
    public final FreshNameCreator fresh;

    /** the content of the compilation unit in tree form
     */
    public Tree[] body;
    public ARepository repository;

    public Unit(Global global, SourceFile source, 
		boolean console, boolean mixinOnly) {
        this.global = global;
        this.source = source;
        this.console = console;
	this.mixinOnly = mixinOnly;
        this.fresh = new FreshNameCreator();
    }

    public Unit(Global global, SourceFile source, boolean console) {
	this(global, source, console, false);
    }

    /** return the position representing the given encoded position
     */
    public Position position(int pos) {
        return new Position(source, pos);
    }

    /** issue an error in this compilation unit at a specific location
     */
    public void error(int pos, String message) {
        global.reporter.error(position(pos), message);
    }

    /** issue a warning in this compilation unit at a specific location
     */
    public void warning(int pos, String message) {
        global.reporter.warning(position(pos), message);
    }

    /** return a string representation
     */
    public String toString() {
        return source.toString();
    }

}
