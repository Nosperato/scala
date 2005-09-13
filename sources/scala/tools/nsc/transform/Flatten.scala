/* NSC -- new scala compiler
 * Copyright 2005 LAMP/EPFL
 * @author
 */
// $Id$
package scala.tools.nsc.transform;

import symtab._;
import Flags._;
import util.ListBuffer;
import collection.mutable.HashMap;

abstract class Flatten extends InfoTransform {
  import global._;                  
  import definitions._;             

  /** the following two members override abstract members in Transform */
  val phaseName: String = "flatten";  

  private def liftClass(sym: Symbol): unit =
    if (!(sym hasFlag LIFTED)) {
      sym setFlag LIFTED;
      atPhase(phase.next) {
	if (settings.debug.value) log("re-enter " + sym + " in " + sym.owner);
	assert(sym.owner.isPackageClass, sym);//debug
	val scope = sym.owner.info.decls;
	val old = scope lookup sym.name;
	if (old != NoSymbol) scope unlink old;
	scope enter sym;
      }
    }

  private val flattened = new TypeMap {
    def apply(tp: Type): Type = tp match {
      case TypeRef(pre, sym, args) if (pre.symbol.isClass && !pre.symbol.isPackageClass) =>
        assert(args.isEmpty);
        typeRef(sym.toplevelClass.owner.thisType, sym, args)
      case ClassInfoType(parents, decls, clazz) =>
	var parents1 = parents;
	val decls1 = new Scope();
        if (clazz.isPackageClass) {
	  atPhase(phase.next)(decls.toList foreach (decls1 enter))
	} else {
	  atPhase(phase.next)(clazz.owner.info);
	  parents1 = List.mapConserve(parents)(this);
	  for (val sym <- decls.toList) {
	    if (sym.isTerm) decls1 enter sym
	    else if (sym.isClass) {
	      liftClass(sym);
	      if (sym.needsImplClass) liftClass(erasure.implClass(sym))
	    }
	  }
	} 
	ClassInfoType(parents1, decls1, clazz)
      case PolyType(tparams, restp) =>
	val restp1 = apply(restp);
	if (restp1 eq restp) tp else PolyType(tparams, restp1)
      case _ =>
        mapOver(tp)
    }
  }

  def transformInfo(sym: Symbol, tp: Type): Type = flattened(tp);

  protected def newTransformer(unit: CompilationUnit): Transformer = new Flattener;

  class Flattener extends Transformer {
    
    /** Buffers for lifted out classes */ 
    private val liftedDefs = new HashMap[Symbol, ListBuffer[Tree]];

    override def transform(tree: Tree): Tree = {
      tree match {
      	case PackageDef(_, _) =>
          liftedDefs(tree.symbol.moduleClass) = new ListBuffer;
	case _ =>
      }
      postTransform(super.transform(tree))
    }

    private def postTransform(tree: Tree): Tree = {
      val sym = tree.symbol;
      val tree1 = tree match {
        case ClassDef(_, _, _, _, _) if sym.isNestedClass =>
	  liftedDefs(sym.toplevelClass.owner) += tree;
	  EmptyTree 	
	case Super(qual, mix) if (mix != nme.EMPTY.toTypeName) =>
	  val ps = tree.symbol.info.parents dropWhile (p => p.symbol.name != mix);
	  assert(!ps.isEmpty, tree);
	  val mix1 = if (ps.head.symbol.isNestedClass) atPhase(phase.next)(ps.head.symbol.name)
		     else mix;
	  copy.Super(tree, qual, mix1)
        case _ =>
          tree
      }
      tree1 setType flattened(tree1.tpe);
      if (sym != null && sym.isNestedClass && !(sym hasFlag LIFTED)) {
	liftClass(sym);//todo: remove
	if (sym.implClass != NoSymbol) liftClass(sym.implClass);
      }
      tree1
    }

    /** Transform statements and add lifted definitions to them. */
    override def transformStats(stats: List[Tree], exprOwner: Symbol): List[Tree] = {
      val stats1 = super.transformStats(stats, exprOwner);
      if (currentOwner.isPackageClass && liftedDefs(currentOwner).hasNext) 
        stats1 ::: liftedDefs(currentOwner).toList
      else
        stats1
    }
  }
}
