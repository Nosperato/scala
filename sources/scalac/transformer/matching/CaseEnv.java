/*     ____ ____  ____ ____  ______                                     *\
**    / __// __ \/ __// __ \/ ____/    SOcos COmpiles Scala             **
**  __\_ \/ /_/ / /__/ /_/ /\_ \       (c) 2002, LAMP/EPFL              **
** /_____/\____/\___/\____/____/                                        **
**                                                                      **
** $Id$
\*                                                                      */

package scalac.transformer.matching;

import scalac.*;
import scalac.ast.*;
import scalac.util.*;
import scalac.symtab.*;
import Tree.ValDef;

/** the environment for a body of a case
 */

class CaseEnv {
    
    /** the owner of the variables created here
     */
    Symbol owner;
    
    /** the global definitions component
     */
    Definitions defs;
    
    /** the global tree generation component
     */
    TreeGen gen;
    
    /** the global tree factory
     */
    TreeFactory make;
    
    /** constructor
     */
    CaseEnv( Symbol owner, Unit unit ) {
	this.owner = owner;
	this.defs = unit.global.definitions;
	this.gen = unit.global.treeGen;
	this.make = unit.global.make;
    }
    
    protected ValDef[] boundVars = new ValDef[4];
    protected int numVars = 0;
    
    public void newBoundVar(int pos, Symbol sym, Type type, Tree init) {
	sym.setOwner( owner ); // FIXME should be corrected earlier
	if (numVars == boundVars.length) {
	    ValDef[] newVars = new ValDef[numVars * 2];
	    System.arraycopy(boundVars, 0, newVars, 0, numVars);
	    boundVars = newVars;
	}
	sym.setType(type);
	boundVars[numVars++] = (ValDef)make.ValDef(pos,
						   0,
						   sym.name,
						   gen.mkType( pos, type ),
						   init.duplicate()).setType( defs.UNIT_TYPE ).setSymbol(sym);
    }
    
    public ValDef[] boundVars() {
	ValDef[] newVars = new ValDef[numVars];
	System.arraycopy(boundVars, 0, newVars, 0, numVars);
	return newVars;
    }
    
    public boolean equals(Object obj) {
	if (!(obj instanceof CaseEnv))
	    return false;
	CaseEnv env = (CaseEnv)obj;
	if (env.numVars != numVars)
	    return false;
	for (int i = 0; i < numVars; i++)
	    if ((boundVars[i].name != env.boundVars[i].name) ||
		!boundVars[i].tpe.type.isSameAs(env.boundVars[i].tpe.type) ||
		(boundVars[i].rhs != env.boundVars[i].rhs))
		return false;
	return true;
    }
    
} // class CaseEnv
