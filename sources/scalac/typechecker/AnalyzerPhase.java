/*     ____ ____  ____ ____  ______                                     *\
**    / __// __ \/ __// __ \/ ____/    SOcos COmpiles Scala             **
**  __\_ \/ /_/ / /__/ /_/ /\_ \       (c) 2002, LAMP/EPFL              **
** /_____/\____/\___/\____/____/                                        **
**
** $Id$
\*                                                                      */

package scalac.typechecker;

import scalac.*;
import scalac.util.*;
import scalac.ast.*;
import scalac.symtab.*;
import scalac.checkers.*;
import java.util.HashMap;
import java.util.ArrayList;

public class AnalyzerPhase extends PhaseDescriptor {

    /* final */ Context startContext;
    HashMap/*<Unit,Context>*/ contexts = new HashMap();
    ArrayList/*<Unit>*/ newSources = new ArrayList();
    
    public void initialize(Global global, int id) {
        super.initialize(global, id);
        Definitions definitions = global.definitions;
        this.startContext = new Context(
	    Tree.Empty, 
	    definitions.ROOT_CLASS,
	    definitions.ROOT_CLASS.members(),
	    Context.NONE);
	this.startContext.enclClass = this.startContext;

        if (!global.noimports) {
            TreeFactory make = global.make;

            Tree java = make.Ident(Position.NOPOS, Names.java)
                .setSymbol(definitions.JAVA)
                .setType(Type.singleType(definitions.ROOT_TYPE, definitions.JAVA));
            Tree javalang = make.Select(Position.NOPOS, java, Names.lang)
                .setSymbol(definitions.JAVALANG)
                .setType(Type.singleType(java.type, definitions.JAVALANG));
            Tree importjavalang = make.Import(
		Position.NOPOS, javalang, new Name[]{Names.WILDCARD})
                .setSymbol(definitions.JAVALANG)
                .setType(definitions.UNIT_TYPE);
            startContext.imports = new ImportList(
		importjavalang, startContext.scope, startContext.imports);

            Tree scala = make.Ident(Position.NOPOS, Names.scala)
                .setSymbol(definitions.SCALA)
                .setType(Type.singleType(definitions.ROOT_TYPE, definitions.SCALA));
            Tree importscala = make.Import(
		Position.NOPOS, scala, new Name[]{Names.WILDCARD})
                .setSymbol(definitions.SCALA)
                .setType(definitions.UNIT_TYPE);
            startContext.imports = new ImportList(
		importscala, new Scope(), startContext.imports);

	    scala = make.Ident(Position.NOPOS, Names.scala)
                .setSymbol(definitions.SCALA)
                .setType(scala.type);
	    Symbol scalaPredefSym = definitions.getModule(Names.scala_Predef);
	    Tree scalaPredef = make.Select(Position.NOPOS, scala, Names.Predef)
		.setSymbol(scalaPredefSym)
		.setType(Type.singleType(scala.type, scalaPredefSym));
	    
	    Tree importscalaPredef = make.Import(
		Position.NOPOS, scalaPredef, new Name[]{Names.WILDCARD})
		.setSymbol(scalaPredefSym)
                .setType(definitions.UNIT_TYPE);
            startContext.imports = new ImportList(
		importscalaPredef, new Scope(), startContext.imports);
        }
    }

    public String name() {
        return "analyze";
    }

    public String description () {
        return "name and type analysis";
    }

    public String taskDescription() {
        return "type checking";
    }

    public Phase createPhase(Global global) {
	return new Analyzer(global, this);
    }

    public Checker[] postCheckers(Global global) {
        return new Checker[] {
	    /* todo: uncomment
            new CheckSymbols(global),
            new CheckTypes(global),
            new CheckOwners(global)
	    */
        };
    }
}
