/*     ____ ____  ____ ____  ______                                     *\
**    / __// __ \/ __// __ \/ ____/    SOcos COmpiles Scala             **
**  __\_ \/ /_/ / /__/ /_/ /\_ \       (c) 2002, LAMP/EPFL              **
** /_____/\____/\___/\____/____/                                        **
\*                                                                      */

// $OldId: ErasurePhase.java,v 1.13 2002/11/14 15:58:22 schinz Exp $
// $Id$

package scalac.transformer;

import scalac.Global;
import scalac.*;
import scalac.backend.Primitive;
import scalac.backend.Primitives;
import scalac.checkers.Checker;
import scalac.checkers.CheckOwners;
import scalac.checkers.CheckSymbols;
import scalac.checkers.CheckTypes;
import scalac.checkers.CheckNames;
import scalac.symtab.Definitions;
import scalac.symtab.Symbol;
import scalac.symtab.Type;
import scalac.util.Debug;

public class ErasurePhase extends PhaseDescriptor {

    public Definitions definitions;
    public Primitives primitives;

    public String name () {
        return "erasure";
    }

    public String description () {
        return "type eraser";
    }

    public String taskDescription() {
        return "erased types";
    }

    public void apply(Global global) {
        this.definitions = global.definitions;
        this.primitives = global.primitives;
        new Erasure(global).apply();
    }
    
	public void apply(Unit unit) {
		this.definitions = unit.global.definitions;
        this.primitives = unit.global.primitives;
        new Erasure(unit.global).apply(unit);
	}
	
    private Type eraseParams(Type tp) {
        switch (tp) {
        case PolyType(_, Type result):
            return eraseParams(result);
        case MethodType(Symbol[] params, Type result):
            Symbol[] params1 = Type.erasureMap.map(params);
            if (params1 == params) return tp;
            else return Type.MethodType(params1, result);
        default:
            return tp;
        }
    }

    public Type transformInfo(Symbol sym, Type tp) {
        if (sym.isClass()) return Type.erasureMap.map(tp);
        if (sym.isType()) return tp;
        if (sym == definitions.NULL) return tp.resultType().erasure();
        switch (primitives.getPrimitive(sym)) {
        case Primitive.IS : return tp;
        case Primitive.AS : return tp;
        case Primitive.BOX: return eraseParams(tp);
        default           : return tp.erasure();
        }
    }

    public Checker[] postCheckers(Global global) {
        return new Checker[] {
            new CheckSymbols(global),
            new CheckTypes(global),
            new CheckOwners(global),
            new CheckNames(global)
        };
    }
}
