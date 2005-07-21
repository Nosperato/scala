/*     ____ ____  ____ ____  ______                                     *\
**    / __// __ \/ __// __ \/ ____/    SOcos COmpiles Scala             **
**  __\_ \/ /_/ / /__/ /_/ /\_ \       (c) 2002-2005, LAMP/EPFL         **
** /_____/\____/\___/\____/____/                                        **
\*                                                                      */

// $Id$

package scalac.transformer;

import scalac.*;
import scalac.ast.*;
import scalac.symtab.Kinds;
import scalac.symtab.Symbol;
import scalac.util.Name;
import Tree.*;


/** A default transformer class which also maintains owner information
 *
 *  @author     Martin Odersky
 *  @version    1.0
 */
public class OwnerTransformer extends Transformer {

    protected Symbol currentOwner;

    public OwnerTransformer(Global global) {
        super(global);
    }

    public void apply(CompilationUnit unit) {
	currentOwner = global.definitions.ROOT_CLASS;
        unit.body = transform(unit.body);
    }

    /** ..
     *
     *  @param tree
     *  @param owner
     *  @return
     */
    public Tree transform(Tree tree, Symbol owner) {
	Symbol prevOwner = currentOwner;
	currentOwner = owner;
	Tree tree1 = transform(tree);
	currentOwner = prevOwner;
	return tree1;
    }

    /** ..
     *
     *  @param params
     *  @param owner
     *  @return
     */
    public AbsTypeDef[] transform(AbsTypeDef[] params, Symbol owner) {
	Symbol prevOwner = currentOwner;
	currentOwner = owner;
	AbsTypeDef[] res = transform(params);
	currentOwner = prevOwner;
	return res;
    }

    /** ..
     *
     *  @param params
     *  @param owner
     *  @return
     */
    public ValDef[][] transform(ValDef[][] params, Symbol owner) {
	Symbol prevOwner = currentOwner;
	currentOwner = owner;
	ValDef[][] res = transform(params);
	currentOwner = prevOwner;
	return res;
    }

    /**  ..
     *
     *  @param templ
     *  @param owner
     *  @return
     */
    public Template transform(Template templ, Symbol owner) {
	Symbol prevOwner = currentOwner;
	if (owner.kind == Kinds.CLASS)
	    currentOwner = owner.primaryConstructor();
	Tree[] parents1 = transform(templ.parents);
	currentOwner = owner;
	Tree[] body1 = transformTemplateStats(templ.body, templ.symbol());
	currentOwner = prevOwner;
	return copy.Template(templ, parents1, body1);
    }

    /** ..
     *
     *  @param ts
     *  @param tsym
     *  @return
     */
    public Tree[] transformTemplateStats(Tree[] ts, Symbol tsym) {
	Tree[] ts1 = ts;
	for (int i = 0; i < ts.length; i++) {
            Tree t = transformTemplateStat(ts[i], tsym);
            if (t != ts[i] && ts1 == ts) {
                ts1 = new Tree[ts.length];
                System.arraycopy(ts, 0, ts1, 0, i);
	    }
	    ts1[i] = t;
        }
        return ts1;
    }

    /** ..
     *
     *  @param stat
     *  @param tsym
     *  @return
     */
    public Tree transformTemplateStat(Tree stat, Symbol tsym) {
	return transform(stat, tsym);
    }

    /** ..
     *
     *  @param tree
     *  @return
     */
    public Tree transform(Tree tree) {
	switch(tree) {
	case PackageDef(Tree packaged, Template impl):
	    return copy.PackageDef(
		tree,
                transform(packaged),
                transform(impl, packaged.symbol()));

	case ClassDef(_, _, AbsTypeDef[] tparams, ValDef[][] vparams, Tree tpe, Template impl):
            Symbol symbol = tree.symbol();
	    return copy.ClassDef(
		tree, symbol,
		transform(tparams, symbol.primaryConstructor()),
		transform(vparams, symbol.primaryConstructor()),
		transform(tpe, symbol),
		transform(impl, symbol));

	case ModuleDef(_, _, Tree tpe, Template impl):
            Symbol symbol = tree.symbol();
	    return copy.ModuleDef(
		tree, symbol,
                transform(tpe, symbol),
		transform(impl, symbol.moduleClass()));

	case DefDef(_, _, AbsTypeDef[] tparams, ValDef[][] vparams, Tree tpe, Tree rhs):
            Symbol symbol = tree.symbol();
	    return copy.DefDef(
		tree, symbol,
		transform(tparams, symbol),
		transform(vparams, symbol),
		transform(tpe, symbol),
		transform(rhs, symbol));

	case ValDef(_, _, Tree tpe, Tree rhs):
            Symbol symbol = tree.symbol();
	    return copy.ValDef(
		tree, symbol,
                transform(tpe),
		transform(rhs, symbol));

	case AbsTypeDef(int mods, Name name, Tree rhs, Tree lobound):
	    Symbol symbol = tree.symbol();
	    return copy.AbsTypeDef(
		tree, symbol, 
		transform(rhs, symbol),
		transform(lobound, symbol));

	case AliasTypeDef(int mods, Name name, AbsTypeDef[] tparams, Tree rhs):
	    Symbol symbol = tree.symbol();
	    return copy.AliasTypeDef(
		tree, symbol, 
		transform(tparams, symbol),
		transform(rhs, symbol));

	default:
	    return super.transform(tree);
	}
    }

}
