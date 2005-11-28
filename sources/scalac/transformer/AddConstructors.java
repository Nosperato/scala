/*     ____ ____  ____ ____  ______                                     *\
**    / __// __ \/ __// __ \/ ____/    SOcos COmpiles Scala             **
**  __\_ \/ /_/ / /__/ /_/ /\_ \       (c) 2002-2005, LAMP/EPFL         **
** /_____/\____/\___/\____/____/                                        **
\*                                                                      */

// $Id$

package scalac.transformer;

import java.util.ArrayList;
import java.util.HashMap;

import scalac.Global;
import scalac.ast.GenTransformer;
import scalac.ast.Tree;
import scalac.ast.Tree.Template;
import scalac.symtab.Modifiers;
import scalac.symtab.Symbol;
import scalac.symtab.SymbolSubstTypeMap;
import scalac.symtab.Type;
import scalac.util.Debug;

/**
 * This phase adds to all classes one initializer method per
 * constructor. Initializers have the same value parameters as their
 * corresponding constructor but no type parameters.
 *
 * An initializer may be used in the body of another initializer
 * either to invoke another initializer of the same class or to invoke
 * an initailizer of the superclass. In that case, the initializer
 * must appear in a Select node whose qualifier is either a This node
 * or a Super node. The type of such a Select node is the type of the
 * initializer.
 *
 * An initializer may also be used in a new operation. In that case,
 * the initializer must appear in an Ident node.  The type of such an
 * Ident node is a PolyType whose arguments are the type arguments of
 * the initializer's class and whose result type is the initializer's
 * type.
 *
 * This phase does the following in the tree:
 *
 * - replaces all non-primary constructors definitions by initializer
 *   definitions with the same body,
 *
 * - for each non-interface class creates an primary initializer
 *   method corresponding to the primary constructor,
 *
 * - moves the call to the super constructor into the primary
 *   initializer,
 *
 * - moves all class fields initialization code (rhs of ValDefs) into
 *   the primary initializer,
 *
 * - moves all class-level expressions into the primary initializer,
 *
 * - replaces all constructor invocations by initializer invocations,
 *   except in the parents field of class templates.
 *
 * @author Nikolay Mihaylov
 * @version 1.2
 */
public class AddConstructors extends GenTransformer {

    /** A constructor to initializer map */
    private final HashMap/*<Symbol,Symbol>*/ initializers;

    /** A constructor to initializer parameter substitution */
    private final SymbolSubstTypeMap subst;

    /** The current primary initializer or null */
    private Symbol primaryInitializer;

    /**
     * ...
     *
     * @param global
     * @param initializers
     */
    public AddConstructors(Global global, HashMap initializers) {
	super(global);
        this.initializers = initializers;
        this.subst = new SymbolSubstTypeMap();
    }

    /**
     * Returns the initializer corresponding to the given constructor.
     *
     * @param  constructor
     * @return  
     */
    private Symbol getInitializer(Symbol constructor) {
   	assert constructor.isConstructor(): Debug.show(constructor);
	Symbol initializer = (Symbol)initializers.get(constructor);
	if (initializer == null) {
	    assert !constructor.constructorClass().isInterface():
                "found interface constructor " + Debug.show(constructor);
	    int flags = constructor.isPrivate()
		? (constructor.flags & ~Modifiers.PRIVATE) | Modifiers.PROTECTED
		: constructor.flags;
	    initializer = constructor.constructorClass().newMethod(
                constructor.pos,
                flags & Modifiers.ACCESSFLAGS,
                constructor.name);
	    initializer.setInfo(
                Type.MethodType(
                    constructor.valueParams(),
                    global.definitions.void_TYPE())
                .cloneType(constructor, initializer));
            initializer.owner().members().enterOrOverload(initializer);
	    initializers.put(constructor, initializer);
	}
	return initializer;
    }

    /**
     * Process the tree.
     */
    public Tree transform(Tree tree) {
        return transform(tree, false);
    }

    /**
     * ...
     *
     * @param  tree
     * @param  inNew
     * @return 
     */
    private Tree transform(Tree tree, boolean inNew) {
	switch (tree) {
	case ClassDef(_, _, _, _, _, Template impl):
            Symbol clasz = tree.symbol();
	    if (clasz.isInterface())
		return gen.ClassDef(clasz, transform(impl.body));

	    // expressions that go before the call to the super constructor
	    final ArrayList constrBody = new ArrayList();

	    // expressions that go after the call to the super constructor
	    final ArrayList constrBody2 = new ArrayList();

	    // the body of the class after the transformation
	    final ArrayList classBody = new ArrayList();

            Symbol local = impl.symbol();
	    for (int i = 0; i < impl.body.length; i++) {
                Tree member = impl.body[i];
		if (member.definesSymbol() && member.symbol().owner()!=local) {
		    switch (member) {
		    case ValDef(_, _, _, Tree rhs):
                        // move initialization code into initializer
                        Symbol field = member.symbol();
			if (rhs == Tree.Empty) break;
                        member = gen.ValDef(field, Tree.Empty);
                        Tree assign = gen.Assign(
                            gen.Select(gen.This(member.pos, clasz),field),rhs);
                        if (rhs.hasSymbol() && rhs.symbol().isParameter()) {
                            constrBody.add(assign);
                        } else {
                            constrBody2.add(assign);
                        }
		    }
		    classBody.add(member);
                } else {
		    // move class-level code into initializer
		    constrBody2.add(member);
		}
	    }

	    // inline the call to the super constructor
            for (int i = 0; i < impl.parents.length; i++) {
                switch (impl.parents[i]) {
                case Apply(TypeApply(Tree fun, Tree[] targs), Tree[] args):
                    assert fun.symbol().isConstructor(): impl.parents[i];
                    if (fun.symbol().constructorClass().isInterface()) continue;
                    int pos = impl.parents[i].pos;
                    Tree superConstr = gen.Select
                        (gen.Super(pos, clasz), getInitializer(fun.symbol()));
                    constrBody.add(gen.mkApply_V(superConstr, args));
                    break;
                case Apply(Tree fun, Tree[] args):
                    assert fun.symbol().isConstructor(): impl.parents[i];
                    if (fun.symbol().constructorClass().isInterface()) continue;
                    int pos = impl.parents[i].pos;
                    Tree superConstr = gen.Select
                        (gen.Super(pos, clasz), getInitializer(fun.symbol()));
                    constrBody.add(gen.mkApply_V(superConstr, args));
                    break;
                default:
                    throw Debug.abort("illegal case", impl.parents[i]);
                }
            }

	    // add valdefs and class-level expression to the constructorr body
	    constrBody.addAll(constrBody2);

            Tree constrTree = gen.mkUnitBlock(
                clasz.primaryConstructor().pos,
                (Tree[])constrBody.toArray(new Tree[constrBody.size()]));

	    classBody.add(gen.DefDef(clasz.primaryConstructor(), constrTree));

	    Tree[] newBody = (Tree[]) classBody.toArray(Tree.EMPTY_ARRAY);

	    // transform the bodies of all members in order to substitute
	    // the constructor references with the new ones
	    return gen.ClassDef(clasz, transform(newBody));

        case DefDef(_, _, _, _, _, Tree rhs):
            if (!tree.symbol().isConstructor()) return super.transform(tree);
            // replace constructor by initializer
            Symbol constructor = tree.symbol();
            Symbol initializer = getInitializer(constructor);
            subst.insertSymbol(
                constructor.typeParams(),
                constructor.constructorClass().typeParams());
            subst.insertSymbol(
                constructor.valueParams(),
                initializer.valueParams());
            if (constructor.isPrimaryConstructor())
                primaryInitializer = initializer;
            rhs = transform(rhs);
            primaryInitializer = null;
            subst.removeSymbol(constructor.valueParams());
            subst.removeSymbol(constructor.typeParams());
            // add consistent result expression
            rhs = gen.mkUnitBlock(rhs);
            return gen.DefDef(initializer, rhs);

        case ValDef(_, _, _, _):
        case LabelDef(_, _, _):
            Symbol symbol = tree.symbol();
            if (symbol.owner().isConstructor()) {
                // update symbols like x in these examples
                //    ex 1: class C { { val x = ...; ... } }
                //    ex 2: class C { def this(i: Int) { val x = i; ... } }
                symbol.setOwner(getInitializer(symbol.owner()));
                symbol.updateInfo(subst.apply(symbol.info()));
            }
            return super.transform(tree);

        case New(Tree init):
            return gen.New(transform(init, true));

 	case TypeApply(Tree fun, Tree[] args):
            if (!fun.symbol().isConstructor()) return super.transform(tree);
            if (!inNew) return transform(fun);
            assert fun instanceof Tree.Ident: tree;
            return transform(tree, fun.symbol(), transform(args));

 	case Apply(Tree fun, Tree[] args):
            return gen.Apply(transform(fun, inNew), transform(args));

        case Ident(_):
            Symbol symbol = tree.symbol();
            if (inNew) return transform(tree, symbol, Tree.EMPTY_ARRAY);
            if (symbol.isConstructor()) {
                symbol = getInitializer(symbol);
                Symbol clasz = symbol.owner();
                return gen.Select(gen.This(tree.pos, clasz), symbol);
            } else if (symbol.owner().isConstructor()) {
                symbol = subst.lookupSymbol(symbol);
            }
            return gen.Ident(tree.pos, symbol);

        case TypeTerm():
            return gen.TypeTerm(tree.pos, subst.apply(tree.getType()));

        default:
            return super.transform(tree);
	} // switch(tree)

    } // transform()

    /**
     * Transforms the new instance creation.
     *
     * @param  tree
     * @param  constructor
     * @param  targs
     * @return 
     */
    private Tree transform(Tree tree, Symbol constructor, Tree[] targs) {
        assert constructor.isConstructor(): tree;
        Symbol initializer = getInitializer(constructor);
        Symbol clasz = initializer.owner();
        Tree instance = gen.Create(tree.pos, Tree.Empty, clasz, targs);
        return gen.Select(tree.pos, instance, initializer);
    }

} // class AddConstructors
