/*     ____ ____  ____ ____  ______                                     *\
**    / __// __ \/ __// __ \/ ____/    SOcos COmpiles Scala             **
**  __\_ \/ /_/ / /__/ /_/ /\_ \       (c) 2002, LAMP/EPFL              **
** /_____/\____/\___/\____/____/                                        **
\*                                                                      */

// $Id$
// $OldId: ExplicitOuterClasses.java,v 1.22 2002/10/17 12:31:56 schinz Exp $

package scalac.transformer;

import java.util.*;

import scalac.*;
import scalac.ast.*;
import scalac.util.*;
import scalac.parser.*;
import scalac.symtab.*;
import scalac.typechecker.*;
import Tree.*;

/**
 * Make links from nested classes to their enclosing class explicit.
 *
 * @author Michel Schinz
 * @version 1.0
 */

public class ExplicitOuterClasses extends Transformer {
    // Mapping from class constructor symbols to owner field symbols.
    protected HashMap/*<Symbol,Symbol>*/ outerMap = new HashMap();

    public ExplicitOuterClasses(Global global, PhaseDescriptor descr) {
        super(global, descr);
    }

    protected Type addValueParam(Type oldType, Symbol newValueParam) {
        switch (oldType) {
        case MethodType(Symbol[] vparams, Type result): {
            Symbol[] newVParams = new Symbol[vparams.length + 1];
            newVParams[0] = newValueParam;
            System.arraycopy(vparams, 0, newVParams, 1, vparams.length);
            return new Type.MethodType(newVParams, result);
        }

        case PolyType(Symbol[] tparams, Type result):
            return new Type.PolyType(tparams, addValueParam(result, newValueParam));

        default:
            throw global.fail("invalid type", oldType);
        }
    }

    protected Symbol outerSym(Symbol constSym) {
        if (! outerMap.containsKey(constSym)) {
            Symbol ownerSym = constSym.enclClass();
            Symbol outerSym =
                new TermSymbol(constSym.pos, freshOuterName(), constSym, 0);
            outerSym.setInfo(ownerSym.type());

            outerMap.put(constSym, outerSym);
        }
        return (Symbol)outerMap.get(constSym);
    }

    protected Type newConstType(Symbol constSym) {
        return addValueParam(constSym.info(), outerSym(constSym));
    }

    protected LinkedList/*<Symbol>*/ classStack = new LinkedList();
    protected LinkedList/*<Symbol>*/ outerLinks = new LinkedList();

    protected Name freshOuterName() {
        return global.freshNameCreator.newName(Names.OUTER_PREFIX);
    }

    // Return the given class with an explicit link to its outer
    // class, if any.
    protected Tree addOuterLink(ClassDef classDef) {
        Symbol classSym = classDef.symbol();
        Symbol constSym = classSym.constructor();
        Symbol outerSym = outerSym(constSym);
        assert (outerSym.owner() == constSym) : outerSym;
        outerLinks.addFirst(outerSym);

        // Add the outer parameter, both to the type and the tree.
        Type newConstType = newConstType(constSym);
        ValDef[][] vparams = classDef.vparams;
        ValDef[] newVParamsI;
        if (vparams.length == 0)
            newVParamsI = new ValDef[] { gen.ValDef(outerSym) };
        else {
            newVParamsI = new ValDef[vparams[0].length + 1];
            newVParamsI[0] = gen.ValDef(outerSym);
            System.arraycopy(vparams[0], 0, newVParamsI, 1, vparams[0].length);
        }
        ValDef[][] newVParams = new ValDef[][] { newVParamsI };

        constSym.updateInfo(newConstType);

        int newMods = classDef.mods | Modifiers.STATIC;
        classSym.flags |= Modifiers.STATIC;

        return copy.ClassDef(classDef,
                             newMods,
                             classDef.name,
                             transform(classDef.tparams),
                             transform(newVParams),
                             transform(classDef.tpe),
                             (Template)transform((Tree)classDef.impl));
    }

    // Return the number of outer links to follow to find the given
    // symbol.
    protected int outerLevel(Symbol sym) {
        Iterator classIt = classStack.iterator();
        for (int level = 0; classIt.hasNext(); ++level) {
            Symbol classSym = (Symbol)classIt.next();
            if (classSym == sym)
                return level;
        }
        return -1;
    }

    // Return a tree referencing the "level"th outer class.
    protected Tree outerRef(int level) {
        assert level >= 0 : level;

        if (level == 0) {
            Symbol thisSym = (Symbol)classStack.getFirst();
            return gen.This(thisSym.pos, thisSym);
        } else {
            Iterator outerIt = outerLinks.iterator();
            Tree root = gen.Ident((Symbol)outerIt.next());

            for (int l = 1; l < level; ++l) {
                Symbol outerSym = (Symbol)outerIt.next();
                root = gen.mkStable(gen.Select(root, outerSym));
            }

            return root;
        }
    }

    public Tree transform(Tree tree) {
        switch (tree) {
        case ClassDef(int mods, _, _, _, _, _) : {
            // Add outer link
            ClassDef classDef = (ClassDef) tree;

            Symbol sym = classDef.symbol();
            Tree newTree;

            classStack.addFirst(sym);
            if (classStack.size() == 1 || Modifiers.Helper.isStatic(mods)) {
                outerLinks.addFirst(null);
                newTree = super.transform(tree);
            } else
                newTree = addOuterLink(classDef);
            outerLinks.removeFirst();
            classStack.removeFirst();

            return newTree;
        }

        case Ident(Name name): {
            if (! name.isTermName())
                return super.transform(tree);

            // Follow "outer" links to fetch data in outer classes.
            int level = outerLevel(tree.symbol().classOwner());
            if (level > 0) {
                Tree root = outerRef(level);
                return gen.mkStable(gen.Select(root, tree.symbol()));
            } else {
                return super.transform(tree);
            }
        }

        case This(Tree qualifier): {
            // If "this" refers to some outer class, replace it by
            // explicit reference to it.
            int level = qualifier.hasSymbol() ? outerLevel(qualifier.symbol()) : 0;
            if (level > 0)
                return outerRef(level);
            else
                return super.transform(tree);
        }

        case Apply(Tree fun, Tree[] args): {
            // Add outer parameter to constructor calls.
            Tree realFun;
            Tree[] typeArgs;

            switch (fun) {
            case TypeApply(Tree fun2, Tree[] tArgs):
                realFun = fun2; typeArgs = tArgs; break;
            default:
                realFun = fun; typeArgs = null; break;
            }

            Tree newFun = null, newArg = null;

            if (realFun.hasSymbol() && realFun.symbol().isPrimaryConstructor()) {
                switch (transform(realFun)) {
                case Select(Tree qualifier, Name selector): {
                    if (! (qualifier.hasSymbol()
                           && Modifiers.Helper.isNoVal(qualifier.symbol().flags))) {
                        newFun = make.Ident(qualifier.pos, selector)
                            .setType(realFun.type())
                            .setSymbol(realFun.symbol());
                        newArg = qualifier;
                    }
                } break;

                case Ident(Name name): {
                    int level = outerLevel(realFun.symbol().owner());
                    if (level >= 0) {
                        newFun = realFun;
                        newArg = outerRef(level);
                    }
                } break;

                default:
                    throw global.fail("unexpected node in constructor call");
                }

                if (newFun != null && newArg != null) {
                    Tree[] newArgs = new Tree[args.length + 1];
                    newArgs[0] = newArg;
                    System.arraycopy(args, 0, newArgs, 1, args.length);

                    Tree finalFun;
                    if (typeArgs != null)
                        finalFun = copy.TypeApply(fun, newFun, typeArgs);
                    else
                        finalFun = newFun;

                    finalFun.type = addValueParam(finalFun.type,
                                                  outerSym(realFun.symbol()));
                    return copy.Apply(tree, finalFun, transform(newArgs));
                } else
                    return super.transform(tree);

            } else
                return super.transform(tree);
        }

        default:
            return super.transform(tree);
        }
    }
}
