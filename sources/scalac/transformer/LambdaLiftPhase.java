/*     ____ ____  ____ ____  ______                                     *\
**    / __// __ \/ __// __ \/ ____/    SOcos COmpiles Scala             **
**  __\_ \/ /_/ / /__/ /_/ /\_ \       (c) 2002, LAMP/EPFL              **
** /_____/\____/\___/\____/____/                                        **
\*                                                                      */

// $Id$

package scalac.transformer;

import scalac.*;
import scalac.util.*;
import scalac.parser.*;
import scalac.symtab.*;
import scalac.checkers.*;
import java.util.ArrayList;

public class LambdaLiftPhase extends PhaseDescriptor implements Kinds, Modifiers {

    private Global global;
    int nextPhase;

    public void initialize(Global global, int id) {
        super.initialize(global, id);
        this.global = global;
        this.nextPhase = id + 1;
    }

    public String name () {
        return "lambdalift";
    }

    public String description () {
        return "lambda lifter";
    }

    public String taskDescription() {
        return "lambda lifting";
    }

    public void apply(Global global) {
        new LambdaLift(global, this).apply();
    }
    
	public void apply(Unit unit) {
		new LambdaLift(unit.global, this).apply(unit);
	}
	
    public Type transformInfo(Symbol sym, Type tp) {
        if (global.debug) 
            global.log("transform info for " + sym + ":" + tp + sym.locationString());
        Type tp1 = tp;
        if (sym != Symbol.NONE) {
            switch (tp) {
            case MethodType(_, _):
            case PolyType(_, _):
                tp1 = transform(tp, sym); 
                break;
            default:
                if (sym.kind == CLASS)
                    tp1 = transform(tp, sym);
                else
                    tp1 = transform(tp, sym.owner()); 
            }
        }
        if ((sym.flags & Modifiers.CAPTURED) != 0) return refType(tp1);
        else return tp1;
    }

    /** Add proxies as type arguments for propagated type parameters.
     */
    Type transform(Type tp, Symbol owner) {
        return transformTypeMap.setOwner(owner).apply(tp);
    }

    /** MapOnlyTypes => All symbols are mapped to themselves.
     */
    private class TransformTypeMap extends Type.MapOnlyTypes {
        Symbol owner;
//      ArrayList/*<Symbol>*/ excluded = new ArrayList();
        Type.Map setOwner(Symbol owner) { this.owner = owner; return this; }

        public Type apply(Type tp) {
            switch (tp) {
            case TypeRef(Type pre, Symbol sym, Type[] targs):
                switch (pre) {
                case ThisType(_):
                    if (sym.kind == CLASS && 
			sym.primaryConstructor().isUpdated(nextPhase)) {
                        // !!! For some Java classes,
                        // Symbol.constructor() returns an Overloaded
                        // symbol. This is wrong as constructor()
                        // should return the primary constructor. Once
                        // this problem is solved, the following
                        // switch can be removed.
                        Type constrtype = sym.primaryConstructor().infoAt(nextPhase);
                        Symbol[] tparams;
                        switch (constrtype) {
                        case OverloadedType(_, _):
                            tparams = Symbol.EMPTY_ARRAY;
                            break;
                        default:
                            tparams = constrtype.typeParams();
                            break;
                        }
                        int i = tparams.length;
                        while (i > 0 && (tparams[i-1].flags & SYNTHETIC) != 0)
                            i--;
                        if (i < tparams.length) {
                            if (global.debug) 
                                global.log("adding proxies for " + sym + ": " + ArrayApply.toString(tparams));
                            
                            Type[] targs1 = new Type[tparams.length];
                            System.arraycopy(map(targs), 0, targs1, 0, targs.length);
                            while (i < tparams.length) {
                                targs1[i] = proxy(tparams[i], owner).type();
                                i++;
                            }
                            return Type.TypeRef(pre, sym, targs1);
                        }
                    } else if (sym.isLocal()) {
                        assert targs.length == 0;
                        return proxy(sym, owner).type();
                    }
                }
                break;
/*
            case PolyType(Symbol[] tparams, _):
                if (tparams.length != 0) {
                    int len = excluded.size();
                    for (int i = 0; i < tparams.length; i++)
                        excluded.add(tparams[i]);
                    Type tp1 = map(tp);
                    for (int i = 0; i < tparams.length; i++)
                        excluded.remove(excluded.size() - 1);
                    return tp1;
                }
*/
            }
            return map(tp);
        }
    }

    private TransformTypeMap transformTypeMap = new TransformTypeMap();

    /** Return closest enclosing (type)parameter that has same name as `fv',
     *  or `fv' itself if this is the closest definition.
     */
    Symbol proxy(Symbol fv, Symbol owner) {
        if (global.debug) 
            global.log("proxy " + fv + " of " + fv.owner() + " in " + LambdaLift.asFunction(owner));
        Symbol o = owner;
        while (o.kind != NONE) {
            if (global.debug) 
                global.log("looking in " +  LambdaLift.asFunction(o) + " " + 
                    ArrayApply.toString(o.typeParams()));
            Symbol fowner = LambdaLift.asFunction(o);
            if (fowner.isMethod()) {
                if (fv.owner() == fowner) return fv;
                Type ft = (fowner.isUpdated(nextPhase)) ? fowner.typeAt(nextPhase)
                    : fowner.type();
                Symbol[] ownerparams = fv.isType() ? ft.typeParams() 
                    : ft.firstParams();
                for (int i = 0; i < ownerparams.length; i++) {
                    if (ownerparams[i].name == fv.name)
                        return ownerparams[i];
                }
            }
            assert o.owner() != o;
            o = o.owner();
        }
        return fv;
        //throw new ApplicationError("proxy " + fv + " in " + owner);
    }

    /** The type scala.Ref[tp]
     */
    Type refType(Type tp) {
        Symbol refClass = global.definitions.getClass(Names.scala_Ref);
        assert refClass.kind == Kinds.CLASS;
        return Type.TypeRef(global.definitions.SCALA_TYPE, refClass, new Type[]{tp});
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
