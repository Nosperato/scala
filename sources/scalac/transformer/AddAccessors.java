/*     ____ ____  ____ ____  ______                                     *\
**    / __// __ \/ __// __ \/ ____/    SOcos COmpiles Scala             **
**  __\_ \/ /_/ / /__/ /_/ /\_ \       (c) 2002, LAMP/EPFL              **
** /_____/\____/\___/\____/____/                                        **
\*                                                                      */

// $OldId: AddAccessors.java,v 1.2 2002/11/21 14:14:31 schinz Exp $
// $Id$

package scalac.transformer;

import scalac.*;
import scalac.ast.*;
import scalac.typechecker.*;
import scalac.symtab.*;
import scalac.util.*;

import java.util.*;

/**
 * Add private accessor functions for all class constructor arguments
 * which are accessed from within the class' methods, or nested
 * classes.
 *
 * @author Michel Schinz
 * @version 1.0
 */

public class AddAccessors extends Transformer {
    public AddAccessors(Global global) {
        super(global);
    }

    protected Name valName(Symbol sym) {
        return Name.fromString(sym.name.toString() + "$");
    }

    protected final HashMap/*<Symbol,Symbol>*/ accessorMap = new HashMap();
    protected boolean inClassContext = true;

    protected Symbol accessor(Symbol sym) {
        Symbol accessor = (Symbol)accessorMap.get(sym);
        if (accessor == null) {
            accessor = new TermSymbol(sym.pos,
                                      sym.name,
                                      sym.classOwner(),
                                      Modifiers.STABLE
                                      | Modifiers.ACCESSOR
                                      | Modifiers.PRIVATE);
            accessor.setType(Type.MethodType(Symbol.EMPTY_ARRAY, sym.type()));
            accessorMap.put(sym, accessor);
        }
        return accessor;
    }

    public Tree transform(Tree tree) {
        switch (tree) {
        case ClassDef(_, // :
                      _,
                      Tree.AbsTypeDef[] tparams,
                      Tree.ValDef[][] vparams,
                      Tree tpe,
                      Tree.Template impl): {
            Symbol clsSym = tree.symbol();

            LinkedList/*<Tree>*/ newBody =
                new LinkedList(Arrays.asList(transform(impl.body)));

            // Add value definitions and accessors for all constructor
            // arguments which were found in the body of the class.
            assert vparams.length == 1;
            Tree.ValDef[] params = vparams[0];
            Scope newMembers = new Scope(clsSym.members());
            for (int i = 0; i < params.length; ++i) {
                Symbol paramSym = params[i].symbol();
                if (accessorMap.containsKey(paramSym)) {
                    Symbol accessorSym = (Symbol)accessorMap.get(paramSym);

                    Symbol valSym = new TermSymbol(paramSym.pos,
                                                   valName(paramSym),
                                                   clsSym,
                                                   Modifiers.PRIVATE);
                    valSym.setType(paramSym.type());

                    newBody.addFirst(gen.DefDef(accessorSym, gen.Ident(valSym)));
                    newMembers.enter(accessorSym);

                    newBody.addFirst(gen.ValDef(valSym, gen.Ident(paramSym)));
                    newMembers.enter(valSym);
                }
            }

            // Update class type with new values/accessors.
            clsSym.updateInfo(Type.compoundType(clsSym.parents(),
                                                newMembers,
                                                clsSym));

            assert inClassContext;
            inClassContext = false;
            Tree[] newParents = transform(impl.parents);
            inClassContext = true;

            Tree[] newBodyA = (Tree[])newBody.toArray(new Tree[newBody.size()]);

            return copy.ClassDef(tree,
                                 clsSym,
                                 transform(tparams),
                                 transform(vparams),
                                 transform(tpe),
                                 copy.Template(impl, newParents, newBodyA));
        }

        case ValDef(_, _, _, _): {
            // Do not go into RHS of value definitions which reference
            // case class constructor arguments, to avoid creating
            // another accessor.
            if (Modifiers.Helper.isCaseAccessor(tree.symbol().flags)) {
                return tree;
            } else
                return super.transform(tree);
        }

        case Select(Tree qualifier, _): {
            Symbol sym = tree.symbol();
	    assert sym.kind != Kinds.NONE : tree;
            if (sym.owner().isPrimaryConstructor())
                return gen.Apply(gen.Select(transform(qualifier), accessor(sym)),
                                 Tree.EMPTY_ARRAY);
            else
                return copy.Select(tree, sym, transform(qualifier));
        }

        case Ident(_): {
            Symbol sym = tree.symbol();
            if (inClassContext
                && sym.name.isTermName()
                && sym.owner().isPrimaryConstructor())
                return gen.Apply(gen.Ident(accessor(sym)), Tree.EMPTY_ARRAY);
            else
                return copy.Ident(tree, sym);
        }

        default:
            return super.transform(tree);
        }
    }
}
