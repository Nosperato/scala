/* NSC -- new scala compiler
 * Copyright 2005 LAMP/EPFL
 * @author  Martin Odersky
 */
// $Id$
package scala.tools.nsc.typechecker;

[_trait_] abstract class Infer: Analyzer {
  import symtab.Flags._;
  import global._;
  import definitions._;
  import posAssigner.atPos;
  import util.ListBuffer;

  var normM = 0;
  var normP = 0;
  var normO = 0;

/* -- Type parameter inference utility functions -------------------------------------- */

  /** The formal parameter types corresponding to `formals'.
   *  If `formals' has a repeated last parameter, a list of 
   *  (nargs - params.length + 1) copies of its type is returned. */
  def formalTypes(formals: List[Type], nargs: int): List[Type] = {
    val formals1 = formals map {
      case TypeRef(_, sym, List(arg)) if (sym == ByNameParamClass) => arg
      case formal => formal
    }
    if (!formals1.isEmpty && (formals1.last.symbol == RepeatedParamClass)) {
      val ft = formals1.last.typeArgs.head;
      formals1.init ::: (for (val i <- List.range(formals1.length - 1, nargs)) yield ft)
    } else formals1
  }

  /** A fresh type varable with given type parameter as origin. */
  def freshVar(tparam: Symbol): TypeVar = new TypeVar(tparam.tpe, new TypeConstraint);

  //todo: remove comments around following privates; right now they cause an IllegalAccess
  // error when built with scalac

  /*private*/ class NoInstance(msg: String) extends RuntimeException(msg);

  /*private*/ class DeferredNoInstance(getmsg: () => String) extends NoInstance("") {
    override def getMessage(): String = getmsg()
  }
  
  /*private*/ object instantiateMap extends TypeMap {
    def apply(t: Type): Type = instantiate(t)
  }

  /** map every TypeVar to its constraint.inst field. 
   *  throw a NoInstance exception if a NoType or WildcardType is encountered. 
   *  @throws   NoInstance
   */
  def instantiate(tp: Type): Type = tp match {
    case WildcardType | NoType => 
      throw new NoInstance("undetermined type");
    case TypeVar(origin, constr) =>
      assert(constr.inst != null);//debug
      if (constr.inst != NoType) instantiate(constr.inst)
      else throw new DeferredNoInstance(() =>
	"no unique instantiation of type variable " + origin + " could be found");
    case _ =>
      instantiateMap.mapOver(tp)
  }

  /** Is type fully defined, i.e. no embedded anytypes or wildcards in it? */
  def isFullyDefined(tp: Type): boolean = try {
    instantiate(tp); true
  } catch {
    case ex: NoInstance => false
  }

  /** Do type arguments `targs' conform to formal parameters `tparams'? */
  private def isWithinBounds(tparams: List[Symbol], targs: List[Type]): boolean = {
    val bounds = tparams map (.info.subst(tparams, targs).bounds);
    List.map2(bounds, targs)((bound, targ) => bound containsType targ) forall (x => x)
  }

  /** Solve constraint collected in types `tvars'
   *  @param tvars      All type variables to be instantiated.
   *  @param tparams    The type parameters corresponding to `tvars'
   *  @param variances  The variances of type parameters; need to reverse
   *                    solution direction for all contravariant variables.
   *  @param upper      When `true' search for max solution else min.
   *  @throws NoInstance
   */
  private def solve(tvars: List[TypeVar], tparams: List[Symbol], variances: List[int], 
		    upper: boolean): List[Type] = {
    val config = tvars zip (tparams zip variances);

    def solveOne(tvar: TypeVar, tparam: Symbol, variance: int): unit = {
      if (tvar.constr.inst == NoType) {
	val up = if (variance != CONTRAVARIANT) upper else !upper;
	tvar.constr.inst = null;
	val bound: Type = if (up) tparam.info.bounds.hi else tparam.info.bounds.lo;
	var cyclic = false;
	for (val Pair(tvar2, Pair(tparam2, variance2)) <- config) {
	  if (tparam2 != tparam &&
              ((bound contains tparam2) ||
	       up && (tparam2.info.bounds.lo =:= tparam.tpe) ||
	       !up && (tparam2.info.bounds.hi =:= tparam.tpe))) {
	    if (tvar2.constr.inst == null) cyclic = true;
	    solveOne(tvar2, tparam2, variance2);
          }
	}
	if (!cyclic) {
	  if (up) {
	    if (bound.symbol != AnyClass) {
	      tvar.constr.hibounds = 
		bound.subst(tparams, tvars) :: tvar.constr.hibounds;
	    }
	    for (val tparam2 <- tparams)
	      if (tparam2.info.bounds.lo =:= tparam.tpe)
		tvar.constr.hibounds = 
		  tparam2.tpe.subst(tparams, tvars) :: tvar.constr.hibounds;
	  } else {
	    if (bound.symbol != AllClass && bound.symbol != tparam) {
	      tvar.constr.lobounds = 
		bound.subst(tparams, tvars) :: tvar.constr.lobounds;
	    }
	    for (val tparam2 <- tparams)
	      if (tparam2.info.bounds.hi =:= tparam.tpe)
		tvar.constr.lobounds =
		  tparam2.tpe.subst(tparams, tvars) :: tvar.constr.lobounds;
	  }
	}
	tvar.constr.inst = if (up) glb(tvar.constr.hibounds) else lub(tvar.constr.lobounds)
      }
    }
    for (val Pair(tvar, Pair(tparam, variance)) <- config) solveOne(tvar, tparam, variance);
    tvars map instantiate;
  }

  def skipImplicit(tp: Type) = if (tp.isInstanceOf[ImplicitMethodType]) tp.resultType else tp;

  /** Automatically perform the following conversions on expression types:
   *  A method type becomes the corresponding function type.
   *  A nullary method type becomes its result type.
   *  Implicit parameters are skipped.
   */
  def normalize(tp: Type): Type = skipImplicit(tp) match {
    case MethodType(formals, restpe) => 
      if (util.Statistics.enabled) normM = normM + 1;
      functionType(formals, normalize(restpe))
    case PolyType(List(), restpe) => 
      if (util.Statistics.enabled) normP = normP + 1;
      normalize(restpe);
    case tp1 => 
      if (util.Statistics.enabled) normO = normO + 1;
      tp1
  }

  /** The context-dependent inferencer part */
  class Inferencer(context: Context) {

    /* -- Error Messages ----------------------------------------------------- */

    def setError[T <: Tree](tree: T): T = {
      val name = newTermName("<error: " + tree + ">");
      if (tree.hasSymbol)
	tree.setSymbol(
	  if (tree.isType) context.owner.newErrorClass(name.toTypeName)
	  else context.owner.newErrorValue(name));
      tree.setType(ErrorType)
    }

    def decode(name: Name): String = 
      (if (name.isTypeName) "type " else "value ") + name.decode;

    def treeSymTypeMsg(tree: Tree): String = 
      if (tree.symbol == null) 
	"expression of type " + tree.tpe
      else if (tree.symbol.hasFlag(OVERLOADED)) 
	"overloaded method " + tree.symbol + " with alternatives " + tree.tpe
      else (
	tree.symbol.toString() + 
        (if (tree.tpe.paramSectionCount > 0) ": " else " of type ") + 
        tree.tpe
      );

    def applyErrorMsg(tree: Tree, msg: String, argtpes: List[Type], pt: Type) = (
      treeSymTypeMsg(tree) + msg + argtpes.mkString("(", ",", ")") + 
       (if (pt == WildcardType) "" else " with expected result type " + pt)
    );

    def foundReqMsg(found: Type, req: Type): String =
      ";\n found   : " + found.toLongString + "\n required: " + req;
	    
    def error(pos: int, msg: String): unit =
      context.error(pos, msg);

    def errorTree(tree: Tree, msg: String): Tree = {
      error(tree.pos, msg); 
      setError(tree)
    }

    def typeError(pos: int, found: Type, req: Type): unit =
      if (!found.isError && !req.isError) {
	error(pos,
          "type mismatch" + foundReqMsg(found, req) + 
   	  (if (!(found.resultType eq found) && isCompatible(found.resultType, req))
	    "\n possible cause: missing arguments for method or constructor"
	   else ""));
	if (settings.explaintypes.value)
	  explainTypes(found, req);
      }

    def typeErrorTree(tree: Tree, found: Type, req: Type): Tree = {
      typeError(tree.pos, found, req);
      setError(tree)
    }
	
    /* -- Tests & Checks-------------------------------------------------------- */

    /** Check that `sym' is defined and accessible as a member of tree `site' with type `pre'
     *  in current context. */
    def checkAccessible(tree: Tree, sym: Symbol, pre: Type, site: Tree): Tree = 
      if (sym.isError) {
	tree setSymbol sym setType ErrorType
      } else {
        sym.toplevelClass match {
	case clazz : ClassSymbol =>
	  // System.err.println("TOP: " + clazz + " " + clazz.sourceFile);
	  if (clazz.sourceFile != null) 
	    global.currentRun.currentUnit.depends += clazz.sourceFile;

	case _ => 
	}
	val sym1 = sym filter (alt => context.isAccessible(alt, pre, site.isInstanceOf[Super]));
	if (sym1 == NoSymbol) {
	  if (settings.debug.value) {
	    System.out.println(context);//debug
            System.out.println(tree);//debug
            System.out.println("" + pre + " " + sym.owner + " " + context.owner + " " + context.outer.enclClass.owner + " " + sym.owner.thisType + (pre =:= sym.owner.thisType));//debug
	  }
	  errorTree(tree, sym.toString() + " cannot be accessed in " + 
		    (if (sym.isClassConstructor) context.enclClass.owner else pre.widen))
	} else {
          var owntype = pre.memberType(sym1);
          if (pre.isInstanceOf[SuperType])
            owntype = owntype.substSuper(pre, site.symbol.thisType);
	  tree setSymbol sym1 setType owntype
	}
      }

    def isCompatible(tp: Type, pt: Type): boolean = {
      val tp1 = normalize(tp);
      (tp1 <:< pt) || isCoercible(tp, pt)
    }

    def isCoercible(tp: Type, pt: Type): boolean = false;

    def isCompatible(tps: List[Type], pts: List[Type]): boolean = 
      List.map2(tps, pts)((tp, pt) => isCompatible(tp, pt)) forall (x => x);

    /* -- Type instantiation------------------------------------------------------------ */

    /** Return inferred type arguments of polymorphic expression, given 
     *  its type parameters and result type and a prototype `pt'.
     *  If no minimal type variables exist that make the
     *  instantiated type a subtype of `pt', return null.
     */
    private def exprTypeArgs(tparams: List[Symbol], restpe: Type, pt: Type): List[Type] = {
      val tvars = tparams map freshVar;
      if (isCompatible(restpe.subst(tparams, tvars), pt)) {
	try {
	  solve(tvars, tparams, tparams map varianceInType(restpe), false);
	} catch {
	  case ex: NoInstance => null
	}
      } else null
    }

    /** Return inferred proto-type arguments of function, given 
    *  its type and value parameters and result type, and a 
    *  prototype `pt' for the function result.
    *  Type arguments need to be either determined precisely by
    *  the prototype, or they are maximized, if they occur only covariantly
    *  in the value parameter list.
    *  If instantiation of a type parameter fails, 
    *  take WildcardType for the proto-type argument. */
    def protoTypeArgs(tparams: List[Symbol], formals: List[Type], restpe: Type, 
		      pt: Type): List[Type] = {
      /** Map type variable to its instance, or, if `variance' is covariant/contravariant,
       *  to its upper/lower bound; */
      def instantiateToBound(tvar: TypeVar, variance: int): Type = try {
	if (tvar.constr.inst != NoType) {
	  instantiate(tvar.constr.inst)
	} else if ((variance & COVARIANT) != 0 && !tvar.constr.hibounds.isEmpty) { 
	  tvar.constr.inst = glb(tvar.constr.hibounds);
	  instantiate(tvar.constr.inst)
	} else if ((variance & CONTRAVARIANT) != 0 && !tvar.constr.lobounds.isEmpty) { 
	  tvar.constr.inst = lub(tvar.constr.lobounds);
	  instantiate(tvar.constr.inst)
	} else {
	  WildcardType
	}
      } catch {
	case ex: NoInstance => WildcardType
      }
      val tvars = tparams map freshVar;
      if (isCompatible(restpe.subst(tparams, tvars), pt))
	List.map2(tparams, tvars) ((tparam, tvar) =>
	  instantiateToBound(tvar, varianceInTypes(formals)(tparam)))
      else 
	tvars map (tvar => WildcardType)
    }

    /** Return inferred type arguments, given type parameters, formal parameters,
    *  argument types, result type and expected result type. 
    *  If this is not possible, throw a `NoInstance' exception.
    *  Undetermined type arguments are represented by `definitions.AllClass.tpe'. 
    *  No check that inferred parameters conform to their bounds is made here. 
    *  @param   tparams         the type parameters of the method
    *  @param   formals         the value parameter types of the method
    *  @param   restp           the result type of the method
    *  @param   argtpes         the argument types of the application
    *  @param   pt              the expected return type of the application
    *  @param   uninstantiated  a listbuffer receiving all uninstantiated type parameters
    *                           (type parameters mapped by the constraint solver to `scala.All' 
    *                            and not covariant in `restpe' are taken to be uninstantiated.
    *                            Maps all those type arguments to their corresponding type 
    *                            parameters).
    */
    private def methTypeArgs(tparams: List[Symbol], formals: List[Type], restpe: Type, 
			     argtpes: List[Type], pt: Type, 
			     uninstantiated: ListBuffer[Symbol]): List[Type] = {
      val tvars = tparams map freshVar;
      if (formals.length != argtpes.length) {
	throw new NoInstance("parameter lists differ in length");
      }
      // check first whether type variables can be fully defined from
      // expected result type.
      if (!isCompatible(restpe.subst(tparams, tvars), pt)) {
	throw new DeferredNoInstance(() =>
	  "result type " + normalize(restpe) + " is incompatible with expected type " + pt)
      }
      for (val tvar <- tvars)
	if (!isFullyDefined(tvar)) tvar.constr.inst = NoType;

      // Then define remaining type variables from argument types.
      List.map2(argtpes, formals) {(argtpe, formal) =>
	if (!isCompatible(argtpe.deconst.subst(tparams, tvars), 
			  formal.subst(tparams, tvars))) {
	  if (settings.explaintypes.value)
	    explainTypes(argtpe.deconst.subst(tparams, tvars), formal.subst(tparams, tvars));
	  throw new DeferredNoInstance(() =>
	    "argument expression's type is not compatible with formal parameter type" + 
	    foundReqMsg(argtpe.deconst.subst(tparams, tvars), formal.subst(tparams, tvars)))
	}
	()
      }
      val targs = solve(tvars, tparams, tparams map varianceInTypes(formals), false);
      List.map2(tparams, targs) {(tparam, targ) =>
	if (targ.symbol == AllClass && (varianceInType(restpe)(tparam) & COVARIANT) == 0) {
	  uninstantiated += tparam;
	  tparam.tpe
	} else targ}
    }


    /** Is there an instantiation of free type variables `undetparams' such that
     *  function type `ftpe' is applicable to `argtpes' and its result conform to `pt'? */
    def isApplicable(undetparams: List[Symbol], ftpe: Type, argtpes: List[Type], pt: Type): boolean =
      ftpe match {
	case MethodType(formals0, restpe) =>
	  val formals = formalTypes(formals0, argtpes.length);
	  if (undetparams.isEmpty) {
	    (formals.length == argtpes.length &&
	     isCompatible(argtpes, formals) && 
	     isCompatible(restpe, pt))
	  } else {
	    try {
	      val uninstantiated = new ListBuffer[Symbol];
	      val targs = methTypeArgs(undetparams, formals, restpe, argtpes, pt, uninstantiated);
	      val result = (
		exprTypeArgs(uninstantiated.toList, restpe.subst(undetparams, targs), pt) != null &&
		isWithinBounds(undetparams, targs)
              );
	      result
	    } catch {
	      case ex: NoInstance => false
	    }
	  }
	case PolyType(tparams, restpe) =>
	  val tparams1 = cloneSymbols(tparams);
	  isApplicable(tparams1 ::: undetparams, restpe.substSym(tparams, tparams1), argtpes, pt)
	case ErrorType =>
	  true
	case _ =>
	  false
      }

    /** Does type `ftpe1' specialize type `ftpe2' 
     *  when both are alternatives in an overloaded function? */
    def specializes(ftpe1: Type, ftpe2: Type): boolean = ftpe1 match {
      case MethodType(formals, _) =>
	isApplicable(List(), ftpe2, formals, WildcardType)
      case PolyType(tparams, MethodType(formals, _)) =>
	isApplicable(List(), ftpe2, formals, WildcardType)
      case ErrorType =>
	true
      case _ =>
	false
    }

    /** error if arguments not within bounds. */
    def checkBounds(pos: int, tparams: List[Symbol], targs: List[Type], prefix: String): unit =
      if (!isWithinBounds(tparams, targs)) {
	error(pos, 
	      prefix + "type arguments " + targs.mkString("[", ",", "]") + 
	      " do not conform to " + tparams.head.owner + "'s type parameter bounds " + 
	      (tparams map (.defString)).mkString("[", ",", "]"));
        if (settings.explaintypes.value) {
          val bounds = tparams map (.info.subst(tparams, targs).bounds);
          List.map2(targs, bounds)((targ, bound) => explainTypes(bound.lo, targ));
          List.map2(targs, bounds)((targ, bound) => explainTypes(targ, bound.hi));
          ()
        }
      }
      
    /** Substitite free type variables `undetparams' of polymorphic argument expression `tree', 
     *  given two prototypes `strictPt', and `lenientPt'.
     *  `strictPt' is the first attempt prototype where type parameters
     *  are left unchanged. `lenientPt' is the fall-back prototype where type parameters
     *  are replaced by `WildcardType's. We try to instantiate first to `strictPt' and then,
     *  if this fails, to `lenientPt'. If both attempts fail, an error is produced.
     */
    def inferArgumentInstance(tree: Tree, undetparams: List[Symbol], strictPt: Type, lenientPt: Type): unit = {
      var targs = exprTypeArgs(undetparams, tree.tpe, strictPt);
      if (targs == null) targs = exprTypeArgs(undetparams, tree.tpe, lenientPt);
      substExpr(tree, undetparams, targs, lenientPt)
    }

    /** Substitite free type variables `undetparams; of polymorphic expression `tree', 
     *  given prototype `pt'. */
    def inferExprInstance(tree: Tree, undetparams: List[Symbol], pt: Type): unit =
      substExpr(tree, undetparams, exprTypeArgs(undetparams, tree.tpe, pt), pt);

    /** Substitite free type variables `undetparams' of polymorphic argument expression `tree' to
     *  `targs', Error if `targs' is null */
    private def substExpr(tree: Tree, undetparams: List[Symbol], targs: List[Type], pt: Type): unit =
      if (targs == null) {
	error(tree.pos, "polymorphic expression cannot be instantiated to expected type" + 
	      foundReqMsg(PolyType(undetparams, skipImplicit(tree.tpe)), pt));
      } else {
	checkBounds(tree.pos, undetparams, targs, "inferred ");
	new TreeTypeSubstituter(undetparams, targs).traverse(tree);
      }

    /** Substitite free type variables `undetparams' of application `fn(args)', given prototype `pt'.
     *  Return the list of type parameters that remain uninstantiated. */
    def inferMethodInstance(fn: Tree, undetparams: List[Symbol], args: List[Tree], pt: Type): List[Symbol] = fn.tpe match {
      case MethodType(formals, restpe) =>
	try {
	  val argtpes = args map (.tpe.deconst);
	  val uninstantiated = new ListBuffer[Symbol];
	  val targs = methTypeArgs(
	    undetparams, formalTypes(formals, argtpes.length), restpe, argtpes, pt, uninstantiated);
	  checkBounds(fn.pos, undetparams, targs, "inferred ");
	  val treeSubst = new TreeTypeSubstituter(undetparams, targs);
	  treeSubst.traverse(fn);
	  treeSubst.traverseTrees(args);
	  uninstantiated.toList;
	} catch {
	  case ex: NoInstance =>
	    errorTree(fn, 
	      "no type parameters for " + 
	      applyErrorMsg(
		fn, " exist so that it can be applied to arguments ", 
		args map (.tpe.widen), WildcardType) +
	      "\n --- because ---\n" + ex.getMessage());
	    List()
	}
    }

    /** Substitite free type variables `undetparams' of type constructor `tree' in pattern, 
     *  given prototype `pt'. 
     *  return type substitution for type parameters.
     */
    def inferConstructorInstance(tree: Tree, undetparams: List[Symbol], pt: Type): unit = {
      var restpe = skipImplicit(tree.tpe.resultType);
      var tvars = undetparams map freshVar;
      def computeArgs = 
	try {
	  val targs = solve(tvars, undetparams, undetparams map varianceInType(restpe), true);
          checkBounds(tree.pos, undetparams, targs, "inferred ");
	  new TreeTypeSubstituter(undetparams, targs).traverse(tree)
	} catch {
	  case ex: NoInstance => 
            errorTree(tree, "constructor of type " + restpe + 
		      " can be instantiated in more than one way to expected type " + pt + 
		      "\n --- because ---\n" + ex.getMessage()); 
	}
      def instError = {
	System.out.println("ici " + tree + " " + undetparams + " " + pt);//debug
	if (settings.explaintypes.value) explainTypes(restpe.subst(undetparams, tvars), pt);
        errorTree(tree, "constructor cannot be instantiated to expected type" +
                  foundReqMsg(restpe, pt))
      }
      if (restpe.subst(undetparams, tvars) <:< pt) {
	computeArgs
      } else if (isFullyDefined(pt)) {
	System.out.println("infer constr " + tree + ":" + restpe + ", pt = " + pt);//debug
	val ptparams = freeTypeParams.collect(pt);
	System.out.println("free type params = " + ptparams);//debug
	val ptWithWildcards = pt.subst(ptparams, ptparams map (ptparam => WildcardType));
        tvars = undetparams map freshVar;
	if (restpe.subst(undetparams, tvars) <:< ptWithWildcards) {
	  computeArgs;
	  restpe = skipImplicit(tree.tpe.resultType);
	  System.out.println("new tree = " + tree + ":" + restpe);//debug
	  val ptvars = ptparams map freshVar;
	  if (restpe <:< pt.subst(ptparams, ptvars)) {
	    for (val tvar <- ptvars) {
	      val tparam = tvar.origin.symbol;
	      val Pair(loBounds, hiBounds) = 
 		if (tvar.constr.inst != NoType && isFullyDefined(tvar.constr.inst))
		  Pair(List(tvar.constr.inst), List(tvar.constr.inst))
		else 
		  Pair(tvar.constr.lobounds, tvar.constr.hibounds);
	      if (!loBounds.isEmpty || !hiBounds.isEmpty) {
                context.nextEnclosing(.tree.isInstanceOf[CaseDef]).pushTypeBounds(tparam);
		tparam setInfo TypeBounds(
		  lub(tparam.info.bounds.lo :: loBounds),
		  glb(tparam.info.bounds.hi :: hiBounds));
		System.out.println("new bounds of " + tparam + " = " + tparam.info);//debug
	      }
	    }
	  } else { System.out.println("no instance: "); instError }
	} else { System.out.println("not a subtype " + restpe.subst(undetparams, tvars) + " of " + ptWithWildcards); instError }
      } else { System.out.println("not fuly defined: " + pt); instError }
    }

    /* -- Overload Resolution ----------------------------------------------------------- */

    /** Assign `tree' the symbol and type of the alternative which matches
     *  prototype `pt', if it exists.
     *  If several alternatives match `pt', take parameterless one.
     *  If no alternative matches `pt', take the parameterless one anyway.
     */
    def inferExprAlternative(tree: Tree, pt: Type): unit = tree.tpe match {
      case OverloadedType(pre, alts) => tryTwice {
	var alts1 = alts filter (alt => isCompatible(pre.memberType(alt), pt));
        if (alts1.isEmpty) alts1 = alts;
	def improves(sym1: Symbol, sym2: Symbol): boolean = (
	  sym2 == NoSymbol || 
	  ((sym1.owner isSubClass sym2.owner) &&
	     {val tp1 = pre.memberType(sym1);
	      val tp2 = pre.memberType(sym2);
	      (tp2 == ErrorType ||
	       !global.typer.infer.isCompatible(tp2, pt) && global.typer.infer.isCompatible(tp1, pt) ||
	       (tp2.paramSectionCount > 0) && (tp1.paramSectionCount == 0 || specializes(tp1, tp2))
             )})
        );
	val best = ((NoSymbol: Symbol) /: alts1) ((best, alt) => 
	  if (improves(alt, best)) alt else best);
	val competing = alts1 dropWhile (alt => best == alt || improves(best, alt));
	if (best == NoSymbol) {
	  tree match {//debug
	    case Select(qual, _) => 
	      System.out.println("qual: " + qual + ":" + qual.tpe + " with decls " + qual.tpe.decls + " with members " + qual.tpe.members + " with members " + qual.tpe.member(newTermName("$minus")));
	    case _ =>
	  }
	  typeErrorTree(tree, tree.symbol.tpe, pt)
	} else if (!competing.isEmpty) {
          if (!pt.isError)
	    context.ambiguousError(tree.pos, pre, best, competing.head, "expected type " + pt);
	  setError(tree);
          ()
	} else {
	  tree.setSymbol(best).setType(pre.memberType(best))
	}
      }
    }

    /** Assign `tree' the type of an alternative which is applicable to `argtpes', 
     *  and whose result type is compatible with `pt'.
     *  If several applicable alternatives exist, take the
     *  most specialized one. 
     *  If no applicable alternative exists, and pt != WildcardType, try again
     *  with pt = WildcardType.
     *  Otherwise, if there is no best alternative, error.
     */  
    def inferMethodAlternative(tree: Tree, undetparams: List[Symbol], argtpes: List[Type], pt: Type): unit = tree.tpe match {
      case OverloadedType(pre, alts) => tryTwice {
	if (settings.debug.value) log("infer method alt " + tree.symbol + " with alternatives " + (alts map pre.memberType) + ", argtpes = " + argtpes + ", pt = " + pt);//debug
	val alts1 = alts filter (alt => isApplicable(undetparams, pre.memberType(alt), argtpes, pt));
	def improves(sym1: Symbol, sym2: Symbol) = (
	  sym2 == NoSymbol || sym2.isError ||
	  ((sym1.owner isSubClass sym2.owner) &&
	   specializes(pre.memberType(sym1), pre.memberType(sym2)))
	);
	val best = ((NoSymbol: Symbol) /: alts1) ((best, alt) => 
	  if (improves(alt, best)) alt else best);
	val competing = alts1 dropWhile (alt => best == alt || improves(best, alt));
	if (best == NoSymbol) {
	  if (pt == WildcardType) {
	    errorTree(tree, applyErrorMsg(tree, " cannot be applied to ", argtpes, pt))
	  } else {
	    inferMethodAlternative(tree, undetparams, argtpes, WildcardType)
	  }
	} else if (!competing.isEmpty) {
          if (!(argtpes exists (.isError)) && !pt.isError)
	    context.ambiguousError(tree.pos, pre, best, competing.head, 
				   "argument types " + argtpes.mkString("(", ",", ")") + 
				   (if (pt == WildcardType) "" else " and expected result type " + pt));
	  setError(tree);
	  ()
	} else {
	  tree.setSymbol(best).setType(pre.memberType(best))
	}
      }
    }

    /** Try inference twice, once without views and once with views, unless views are already disabled.
     */
    def tryTwice(infer: => unit): unit = {
      if (context.reportGeneralErrors) {
	context.reportGeneralErrors = false;
	try {
	  infer
	} catch {
	  case ex: TypeError => 
	    context.reportGeneralErrors = true;
	    infer
	}
	context.reportGeneralErrors = true
      } else infer
    }

    /** Assign `tree' the type of unique polymorphic alternative with `nparams' 
     *  as the number of type parameters, if it exists.
     *  If several or none such polymorphic alternatives exist, error. 
     */
    def inferPolyAlternatives(tree: Tree, nparams: int): unit = tree.tpe match {
      case OverloadedType(pre, alts) => 
	val sym = tree.symbol filter (alt => alt.typeParams.length == nparams);
	if (sym == NoSymbol) {
	  errorTree(tree,
		    if (alts exists (alt => alt.typeParams.length > 0))
		      "wrong number of type parameters for " + treeSymTypeMsg(tree)
		    else treeSymTypeMsg(tree) + " does not take type parameters")
	} else if (sym.hasFlag(OVERLOADED)) {
	  val tparams = sym.alternatives.head.typeParams;
	  val tpe = 
	    PolyType(tparams, 
		     OverloadedType(AntiPolyType(pre, tparams map (.tpe)), sym.alternatives));
	  sym.setInfo(tpe);
	  tree.setSymbol(sym).setType(tpe);
	} else {
	  tree.setSymbol(sym).setType(pre.memberType(sym))
	}
    }
  }
}
