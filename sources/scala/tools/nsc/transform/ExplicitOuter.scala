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

abstract class ExplicitOuter extends InfoTransform {
  import global._;                  
  import definitions._;             
  import posAssigner.atPos;         

  override def phaseNewFlags: long = notPRIVATE | notPROTECTED;

  /** the following two members override abstract members in Transform */
  val phaseName: String = "explicitouter";  
  override def changesBaseClasses = false;

  protected def newTransformer(unit: CompilationUnit): Transformer = 
    new ExplicitOuterTransformer(unit);

  /** The type transformation method: 
   *  1. Add an outer paramter to the formal parameters of a constructor or mixin constructor
   *     in a non-static class; 
   *  2. Add a mixin constructor $init$ to all traits except interfaces
   *  Leave all other types unchanged.
   */
  def transformInfo(sym: Symbol, tp: Type): Type = tp match {
    case MethodType(formals, restpe) =>
      if (sym.owner.isTrait && (sym hasFlag PROTECTED)) sym setFlag notPROTECTED;
      if (sym.isConstructor && !sym.owner.isStatic)
	MethodType(formals ::: List(sym.owner.owner.enclClass.toInterface.thisType), restpe)
      else tp;
    case ClassInfoType(parents, decls, clazz) =>
      var decls1 = decls;
      if (!(clazz hasFlag INTERFACE)) {
	if (!clazz.isStatic) {
	  decls1 = new Scope(decls1.toList);
	  val outerType = clazz.owner.enclClass.thisType;
	  val outerAcc = clazz.newMethod(clazz.pos, nme.OUTER);
	  if ((clazz hasFlag TRAIT) || (decls.toList exists (.isClass))) 
            outerAcc.expandName(clazz);
	  decls1 enter (
	    outerAcc setFlag (PARAMACCESSOR | ACCESSOR | STABLE)
		     setInfo MethodType(List(), outerType));
	  decls1 enter (clazz.newValue(clazz.pos, nme.getterToLocal(outerAcc.name))
	    setFlag (LOCAL | PRIVATE | PARAMACCESSOR | (outerAcc getFlag EXPANDEDNAME))
	    setInfo outerType);
	}
	if (clazz.isTrait) {
	  decls1 = new Scope(decls1.toList);
	  decls1 enter makeMixinConstructor(clazz);
	}
      }
      if (decls1 eq decls) tp else ClassInfoType(parents, decls1, clazz)
    case PolyType(tparams, restp) =>
      val restp1 = transformInfo(sym, restp);
      if (restp eq restp1) tp else PolyType(tparams, restp1)
    case _ =>
      tp
  }

  private def outerMember(tp: Type): Symbol = {
    var e = tp.decls.elems;
    while (e != null && !(e.sym.originalName.startsWith(nme.OUTER) && (e.sym hasFlag ACCESSOR))) 
      e = e.next;
    assert(e != null, tp);
    e.sym
  }

  private def makeMixinConstructor(clazz: Symbol): Symbol =
    clazz.newMethod(clazz.pos, nme.MIXIN_CONSTRUCTOR) setInfo MethodType(List(), UnitClass.tpe);

  /** A base class for transformers that maintain `outerParam' values for 
   *  outer parameters of constructors. 
   *  The class provides methods for referencing via outer.
   */
  class OuterPathTransformer extends Transformer {

    /** The directly enclosing outer parameter, if we are in a constructor */
    protected var outerParam: Symbol = NoSymbol;

    /** The first outer selection from currently transformed tree
     */
    protected def outerValue: Tree =
      if (outerParam != NoSymbol) gen.Ident(outerParam)
      else outerSelect(gen.This(currentOwner.enclClass));

    /** The path 
     *     `base'.$outer ... .$outer
     *  which refers to the outer instance `to' of value `base
     */
    protected def outerPath(base: Tree, to: Symbol): Tree =
      if (base.tpe.symbol == to) base else outerPath(outerSelect(base), to);
    
    /** Select and apply outer accessor from `base'
     */
    private def outerSelect(base: Tree): Tree = {
      assert(base.tpe != null, base);
      assert(base.tpe.symbol != null, base);
      val clazz = base.tpe.symbol.owner.enclClass;
      Apply(
	Select(base, outerMember(base.tpe)) setType MethodType(List(), clazz.thisType),
	List()) setType clazz.thisType
    }

    override def transform(tree: Tree): Tree = {
      try {//debug
	val savedOuterParam = outerParam;
	tree match {
	  case Template(_, _) =>
            outerParam = NoSymbol;
	  case DefDef(_, _, _, vparamss, _, _) =>
	    if (tree.symbol.isConstructor && !(tree.symbol.owner.isStatic)) {
	      val lastParam = vparamss.head.last;
	      assert(lastParam.name.startsWith(nme.OUTER), tree);
	      outerParam = lastParam.symbol
	    }
	  case _ =>
	}
	val result = super.transform(tree);
	outerParam = savedOuterParam;
	result
      } catch {//debug
	case ex: Throwable =>
	  System.out.println("exception when transforming " + tree);
	throw ex
      }
    }
  }

  class ExplicitOuterTransformer(unit: CompilationUnit) extends Transformer {

    /** The first step performs the following transformations:
     *   1. A class which is not an interface and is not static gets an outer link
     *      (@see outerDefs)
     *   2. A mixin which is not also an interface gets a mixin constructor
     *      (@see mixinConstructorDef)
     *   3. Constructor bodies are augmented by calls to supermixin constructors
     *      (@see addMixinConstructorCalls)
     *   4. A constructor of a class with an outer link gets an outer parameter.
     *   5. A reference C.this where C refers to an outer class is replaced by a selection 
     *        this.$outer ... .$outer (@see outerPath)
     *   7. A call to a constructor Q.<init>(args) or Q.$init$(args) where Q != this and
     *      the constructor belongs to a non-static class is augmented by an outer argument.
     *      E.g. Q.<init>(args, OUTER) where OUTER is the qualifier corresponding to the
     *      singleton type Q.
     *   8. A call to a constructor this.<init>(args) in a secondary constructor
     *      is augmented to this.<init>(args, OUTER) where OUTER is the last parameter
     *      of the secondary constructor.
     */
    private val firstTransformer = new OuterPathTransformer {

      var localTyper: analyzer.Typer = typer;

      /** The two definitions
       *    val outer : C.this.type _;
       *    def outer(): C.this.type  = outer ;
       *  Here, C is the class enclosing the class `clazz' containing the two definitions. 
       */
      def outerDefs(clazz: Symbol): List[Tree] = {
	val outerDef = outerMember(clazz.info);
	val outerVal = outerDef.accessed;
	List(
	  localTyper.typed {
	    atPos(clazz.pos) {
	      ValDef(outerVal)
	    }
	  },
	  localTyper.typed {
	    atPos(clazz.pos) {
	      DefDef(outerDef, vparamss => Select(This(clazz), outerVal))
	    }
	  })
      }

      /** The mixin constructor definition
       *    def $init$(): Unit = ()
       */
      def mixinConstructorDef(clazz: Symbol): Tree = 
        localTyper.typed {
          val constr = clazz.primaryConstructor;
	  atPhase(currentRun.explicitOuterPhase) {
            // necessary so that we do not include an outer parameter already here;
            // this will be added later in transform.
            DefDef(constr, vparamss => Literal(()))
          }
        }

      /** Add calls to supermixin constructors 
       *     super[mix].$init$()
       *  to `tree'. `tree' which is assumed to be the body of a constructor of class `clazz'.
       */
      def addMixinConstructorCalls(tree: Tree, clazz: Symbol): Tree = {
	def mixinConstructorCall(mixin: Symbol): Tree =
	  atPos(tree.pos) {
	    Apply(
	      localTyper.typedOperator {
		Select(Super(clazz, mixin.name), mixin.primaryConstructor)
	      }, 
	      List()) setType UnitClass.tpe; // don't type this with typed(...), 
 	                                     // as constructor arguments might be missing
	  }
	val mixinConstructorCalls = 
	  for (val mixin <- clazz.info.parents.tail; !(mixin.symbol hasFlag INTERFACE)) yield 
	    mixinConstructorCall(mixin.symbol);
	tree match {
	  case Block(supercall :: stats, expr) =>
            assert(supercall match { 
              case Apply(Select(Super(_, _), _), _) => true
              case _ => false
            });
	    copy.Block(tree, supercall :: mixinConstructorCalls ::: stats, expr);
	  case Block(_, _) =>
	    assert(false, tree);  tree
	  case expr =>
	    Block(mixinConstructorCalls, expr) setType expr.tpe setPos expr.pos;
	}
      }

      /** The first-step transformation method */
      override def transform(tree: Tree): Tree = {
	val sym = tree.symbol;
	val tree1 = tree match {
	  case Template(parents, decls) =>
	    val savedLocalTyper = localTyper;
	    localTyper = localTyper.atOwner(tree, currentOwner);
	    var decls1 = decls;
	    if (!(currentOwner hasFlag INTERFACE)) {
	      if (!currentOwner.isStatic) 
		decls1 = decls1 ::: outerDefs(currentOwner); // (1)
	      if (currentOwner.isTrait) 
		decls1 = decls1 ::: List(mixinConstructorDef(currentOwner)) // (2)
	    }
	    localTyper = savedLocalTyper;
	    copy.Template(tree, parents, decls1);
	  case constrDef @ DefDef(mods, name, tparams, vparamss, tpt, rhs)
	  if (sym.isConstructor) =>
	    val vparamss1 = 
	      if (sym.owner.isStatic) vparamss
	      else { // (4)
		val outerField = outerMember(sym.owner.info).accessed;
		val outerParam = sym.newValueParameter(sym.pos, nme.OUTER) setInfo outerField.info;
		List(vparamss.head ::: List(ValDef(outerParam) setType NoType))
	      }
	    val rhs1 = 
	      if ((sym.isPrimaryConstructor || sym.isMixinConstructor) && sym.owner != ArrayClass)
		addMixinConstructorCalls(rhs, sym.owner); // (3)
	      else rhs;
	    copy.DefDef(tree, mods, name, tparams, vparamss1, tpt, rhs1);
	  case This(qual) => 
	    if (sym == currentOwner.enclClass || (sym hasFlag MODULE) && sym.isStatic) tree 
	    else atPos(tree.pos)(outerPath(outerValue, sym)); // (5)
	  case Apply(sel @ Select(qual, name), args)
	  if ((name == nme.CONSTRUCTOR || name == nme.MIXIN_CONSTRUCTOR) 
	      && !sel.symbol.owner.isStatic) => 
	    val outerVal = 
              atPos(tree.pos) {
	        if (qual.isInstanceOf[This]) { assert(outerParam != NoSymbol); outerValue } // (8)
		else if (qual.tpe.prefix == NoPrefix) gen.This(currentOwner.enclClass.owner.enclClass)
                else gen.mkQualifier(qual.tpe.prefix); // (7) 
              }
	    copy.Apply(tree, sel, args ::: List(outerVal))
	  case _ =>
	    tree
	}
	super.transform(tree1)
      }
    }

    /** The second step performs the following transformations:
     *   2. Remove private modifiers from members M of mixins T. (@see makeNotPrivate)
     *   3. Remove `private' modifier from class members M that are accessed from an inner class.
     *   4. Remove `protected' modifier from class members M that are accessed
     *      without a super qualifier accessed from an inner class. (@see makeNotPrivate)
     *   5. Remove `private' and `protected' modifiers from type symbols
     */  
    private val secondTransformer = new Transformer {
      
      /** The second-step transformation method */
      override def transform(tree: Tree): Tree = {
	val sym = tree.symbol;
	val tree1 = super.transform(tree);
	tree1 match {
          case DefDef(_, _, _, _, _, _) =>
            if (sym.owner.isTrait && (sym hasFlag (ACCESSOR | SUPERACCESSOR)))
              sym.makeNotPrivate(sym.owner);
            tree1
	  case Select(qual, name) =>
	    if (currentOwner.enclClass != sym.owner) // (3)
              sym.makeNotPrivate(sym.owner);
	    if ((sym hasFlag PROTECTED) && 
		!(qual.isInstanceOf[Super] || 
		  (qual.tpe.widen.symbol isSubClass currentOwner.enclClass)))
	      sym setFlag notPROTECTED;
	    tree1
	  case _ =>
	    if (sym != null && sym.isType) {
	      if (sym hasFlag PRIVATE) sym setFlag notPRIVATE;
	      if (sym hasFlag PROTECTED) sym setFlag notPROTECTED;
	    }
	    tree1
	}
      }
    }

    /** The main transformation method:
     *  First, perform step 1 on whole tree of compilation unit.
     *  Then, perform step 2 on resulting tree
     */
    override def transform(tree: Tree) = 
      atPhase(phase.next) {
	secondTransformer.transform(firstTransformer.transform(tree))
      }
  }
}


