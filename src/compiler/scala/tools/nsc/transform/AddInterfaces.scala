/* NSC -- new scala compiler
 * Copyright 2005 LAMP/EPFL
 * @author
 */
// $Id$
package scala.tools.nsc.transform;

import symtab._;
import Flags._;
import collection.mutable.{HashMap, ListBuffer};

abstract class AddInterfaces extends InfoTransform {
  import global._;                  // the global environment
  import definitions._;             // standard classes and methods
  import posAssigner.atPos;         // for filling in tree positions 

  override def phaseNewFlags: long = lateDEFERRED | lateINTERFACE;

// Type transformation

  def erasedTypeRef(sym: Symbol): Type;

  private val implClassMap = new HashMap[Symbol, Symbol];
  private val implMethodMap = new HashMap[Symbol, Symbol];

  override def newPhase(prev: scala.tools.nsc.Phase): StdPhase = {
    implClassMap.clear;
    implMethodMap.clear;
    super.newPhase(prev)
  }

  private def needsImplMethod(sym: Symbol): boolean = (
    sym.isMethod && isInterfaceMember(sym) && 
    (!(sym hasFlag (DEFERRED | SUPERACCESSOR)) || (sym hasFlag lateDEFERRED))
  );

  private def isInterfaceMember(sym: Symbol): boolean = {
    sym.info; // to set lateMETHOD flag if necessary
    (sym.isType || 
     sym.isMethod && !(sym hasFlag (PRIVATE | BRIDGE | LABEL)) && 
     !sym.isConstructor && !sym.isImplOnly)
  }

  def implClass(iface: Symbol): Symbol = implClassMap.get(iface) match {
    case Some(c) => c
    case None => 
      atPhase(currentRun.erasurePhase) {
        val implName = nme.implClassName(iface.name);
        var impl = if (iface.owner.isClass) iface.owner.info.decl(implName) else NoSymbol;
        if (impl == NoSymbol) {
          impl = iface.cloneSymbolImpl(iface.owner);
          impl.name = implName;
          if (iface.owner.isClass) iface.owner.info.decls enter impl
        }
        if (currentRun.compiles(iface)) currentRun.symSource(impl) = iface.sourceFile;
        impl setPos iface.pos;
        impl.flags = iface.flags & ~(INTERFACE | lateINTERFACE) | IMPLCLASS;
	impl setInfo new LazyImplClassType(iface);
        implClassMap(iface) = impl;
        if (settings.debug.value) log("generating impl class " + impl + " in " + iface.owner);//debug
        impl
      }
  }

  private class LazyImplClassType(iface: Symbol) extends LazyType {

    def implDecls(implClass: Symbol, ifaceDecls: Scope): Scope = {
      val decls = new Scope();
      for (val sym <- ifaceDecls.elements) {
        if (isInterfaceMember(sym)) {
          if (needsImplMethod(sym)) {
	    val impl = sym.cloneSymbol(implClass).setInfo(sym.info).resetFlag(lateDEFERRED);
	    if (currentRun.compiles(implClass)) implMethodMap(sym) = impl;
	    decls enter impl;
	    sym setFlag lateDEFERRED
          }
        } else {
	  sym.owner = implClass;
          decls enter sym;
	}
      }
      decls
    }

    override def complete(sym: Symbol): unit = {
      def implType(tp: Type): Type = tp match {
	case ClassInfoType(parents, decls, _) =>
	  //ClassInfoType(mixinToImplClass(parents) ::: List(iface.tpe), implDecls(sym, decls), sym)
	  ClassInfoType(
            ObjectClass.tpe :: (parents.tail map mixinToImplClass) ::: List(iface.tpe),
            implDecls(sym, decls), 
            sym)
	case PolyType(tparams, restpe) => 
	  PolyType(tparams, implType(restpe))
      }
      sym.setInfo(atPhase(currentRun.erasurePhase)(implType(iface.info)));
    }

    override def load(clazz: Symbol): unit = complete(clazz)
  }

  private def mixinToImplClass(tp: Type): Type = tp match {
    case TypeRef(pre, sym, args) if (sym.needsImplClass) =>
      typeRef(pre, implClass(sym), args)
    case _ =>
      tp
  }

  def transformMixinInfo(tp: Type): Type = tp match {
    case ClassInfoType(parents, decls, clazz) =>
      if (clazz.needsImplClass) {
        clazz setFlag lateINTERFACE;
        implClass(clazz) // generate an impl class
      }
      val parents1 = 
        if (parents.isEmpty) List()
        else {
          assert(!parents.head.symbol.isMixin || clazz == RepeatedParamClass, clazz);
          if (clazz hasFlag INTERFACE) erasedTypeRef(ObjectClass) :: parents.tail
          else if (clazz.isImplClass || clazz == ArrayClass) parents
	  else parents map mixinToImplClass
        }
      val decls1 = if (clazz hasFlag INTERFACE) new Scope(decls.toList filter isInterfaceMember)
                   else decls;
      //if (!clazz.isPackageClass) System.out.println("Decls of "+clazz+" after explicitOuter = " + decls1);//DEBUG
      if ((parents1 eq parents) && (decls1 eq decls)) tp
      else ClassInfoType(parents1, decls1, clazz)
    case _ =>
      tp
  }

// Tree transformation --------------------------------------------------------------

  private class ChangeOwnerAndReturnTraverser(oldowner: Symbol, newowner: Symbol) 
          extends ChangeOwnerTraverser(oldowner, newowner) {
    override def traverse(tree: Tree): unit = {
      tree match {
        case Return(expr) =>
          if (tree.symbol == oldowner) tree.symbol = newowner;
        case _ =>
      }
      super.traverse(tree)
    }
  }        

  private def ifaceMemberDef(tree: Tree): Tree =
    if (!tree.isDef || !isInterfaceMember(tree.symbol)) EmptyTree
    else if (needsImplMethod(tree.symbol)) DefDef(tree.symbol, vparamss => EmptyTree)
    else tree;

  private def ifaceTemplate(templ: Template): Template =
    copy.Template(templ, templ.parents, templ.body map ifaceMemberDef);

  private def implMethodDef(tree: Tree, ifaceMethod: Symbol): Tree = 
    implMethodMap.get(ifaceMethod) match {
      case Some(implMethod) =>
        tree.symbol = implMethod;
        new ChangeOwnerAndReturnTraverser(ifaceMethod, implMethod)(tree)
      case None =>
        throw new Error("implMethod missing for " + ifaceMethod)
    }

  private def implMemberDef(tree: Tree): Tree = 
    if (!tree.isDef || !isInterfaceMember(tree.symbol)) tree
    else if (needsImplMethod(tree.symbol)) implMethodDef(tree, tree.symbol)
    else EmptyTree;

  private def implTemplate(clazz: Symbol, templ: Template): Template = atPos(templ.pos){
    val templ1 = Template(templ.parents, templ.body map implMemberDef)
      .setPos(templ.pos)
      .setSymbol(clazz.newLocalDummy(templ.pos));
    new ChangeOwnerTraverser(templ.symbol.owner, clazz)(
      new ChangeOwnerTraverser(templ.symbol, templ1.symbol)(templ1))
  }
      
  def implClassDefs(trees: List[Tree]): List[Tree] = {
    val buf = new ListBuffer[Tree];
    for (val tree <- trees)
      tree match {
	case ClassDef(_, _, _, _, impl) =>
	  if (tree.symbol.needsImplClass) 
            buf += {
              val clazz = implClass(tree.symbol).initialize;
              ClassDef(clazz, implTemplate(clazz, impl))
            }
	case _ =>
      }
    buf.toList
  }

  protected val mixinTransformer = new Transformer {
    override def transformStats(stats: List[Tree], exprOwner: Symbol): List[Tree] =
      (super.transformStats(stats, exprOwner) ::: 
       super.transformStats(implClassDefs(stats), exprOwner));
    override def transform(tree: Tree): Tree = {
      val tree1 = tree match {
	case ClassDef(mods, name, tparams, tpt, impl) =>
	  if (tree.symbol.needsImplClass) {
            implClass(tree.symbol).initialize; // to force lateDEFERRED flags
	    copy.ClassDef(tree, mods | INTERFACE, name, tparams, tpt, ifaceTemplate(impl))
          }
	  else tree
	case Template(parents, body) =>
          val parents1 = tree.symbol.owner.info.parents map (t => TypeTree(t) setPos tree.pos);
          copy.Template(tree, parents1, body)
	case This(_) =>
	  if (tree.symbol.needsImplClass) {
            val impl = implClass(tree.symbol);
            var owner = currentOwner;
            while (owner != tree.symbol && owner != impl) owner = owner.owner;
            if (owner == impl) This(impl) setPos tree.pos
            else tree
          } else tree
	case Super(qual, mix) =>
	  val mix1 = 
	    if (mix == nme.EMPTY.toTypeName) mix
	    else {
	      val ps = atPhase(currentRun.erasurePhase) {
                tree.symbol.info.parents dropWhile (p => p.symbol.name != mix)
              }
	      assert(!ps.isEmpty, tree);
	      if (ps.head.symbol.needsImplClass) implClass(ps.head.symbol).name 
	      else mix
	    }
	  if (tree.symbol.needsImplClass) Super(implClass(tree.symbol), mix1) setPos tree.pos
	  else copy.Super(tree, qual, mix1)
	case _ =>
	  tree
      }
      super.transform(tree1)
    }
  }
}
/*
    val ensureNoEscapes = new TypeTraverser {
      def ensureNoEscape(sym: Symbol): unit = {
        if (sym.hasFlag(PRIVATE)) {
          var o = currentOwner;
          while (o != NoSymbol && o != sym.owner && !o.isLocal && !o.hasFlag(PRIVATE))
          o = o.owner;
          if (o == sym.owner) sym.makeNotPrivate(base);
        }
      }     
      def traverse(t: Type): TypeTraverser = {
        t match {
          case TypeRef(qual, sym, args) => 
            ensureNoEscape(sym); 
            mapOver(t);
          case ClassInfoType(parents, decls, clazz) => 
            parents foreach { p => traverse; () }
            traverse(t.typeOfThis);
          case _ => 
            mapOver(t)
        }
        this
      }
    }

*/
