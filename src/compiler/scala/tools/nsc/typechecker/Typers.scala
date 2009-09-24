/* NSC -- new Scala compiler
 * Copyright 2005-2009 LAMP/EPFL
 * @author  Martin Odersky
 */
// $Id$

//todo: rewrite or disllow new T where T is a mixin (currently: <init> not a member of T)
//todo: use inherited type info also for vars and values
//todo: disallow C#D in superclass
//todo: treat :::= correctly
package scala.tools.nsc
package typechecker

import scala.collection.mutable.{HashMap, ListBuffer}
import scala.util.control.ControlException
import scala.compat.Platform.currentTime
import scala.tools.nsc.interactive.RangePositions
import scala.tools.nsc.util.{HashSet, Position, Set, NoPosition, SourceFile}
import symtab.Flags._
import util.HashSet
 
// Suggestion check whether we can do without priming scopes with symbols of outer scopes,
// like the IDE does. 
/** This trait provides methods to assign types to trees.
 *
 *  @author  Martin Odersky
 *  @version 1.0
 */
trait Typers { self: Analyzer =>
  import global._
  import definitions._

  var appcnt = 0
  var idcnt = 0
  var selcnt = 0
  var implcnt = 0
  var impltime = 0l

  var failedApplies = 0L
  var failedOpEqs = 0L
  var failedSilent = 0L

  // namer calls typer.computeType(rhs) on DefDef / ValDef when tpt is empty. the result
  // is cached here and re-used in typedDefDef / typedValDef
  private val transformed = new HashMap[Tree, Tree]

  // currently not used at all (March 09)
  private val superDefs = new HashMap[Symbol, ListBuffer[Tree]]

  def resetTyper() {
    resetContexts
    resetNamer()
    resetImplicits()
    transformed.clear
    superDefs.clear
  }

  object UnTyper extends Traverser {
    override def traverse(tree: Tree) = {
      if (tree != EmptyTree) tree.tpe = null
      if (tree.hasSymbol) tree.symbol = NoSymbol
      super.traverse(tree)
    }
  } 
/* needed for experimental version where eraly types can be type arguments
  class EarlyMap(clazz: Symbol) extends TypeMap {
    def apply(tp: Type): Type = tp match {
      case TypeRef(NoPrefix, sym, List()) if (sym hasFlag PRESUPER) =>
        TypeRef(ThisType(clazz), sym, List())
      case _ =>
        mapOver(tp)
    }
  }
*/
  // IDE hooks
  def newTyper(context: Context): Typer = new NormalTyper(context)
  private class NormalTyper(context : Context) extends Typer(context)
  // hooks for auto completion

  // Mode constants

  /** The three mode <code>NOmode</code>, <code>EXPRmode</code>
   *  and <code>PATTERNmode</code> are mutually exclusive.
   */
  val NOmode        = 0x000
  val EXPRmode      = 0x001
  val PATTERNmode   = 0x002
  val TYPEmode      = 0x004
 
  /** The mode <code>SCCmode</code> is orthogonal to above. When set we are
   *  in the this or super constructor call of a constructor.
   */
  val SCCmode       = 0x008

  /** The mode <code>FUNmode</code> is orthogonal to above.
   *  When set we are looking for a method or constructor.
   */
  val FUNmode       = 0x010

  /** The mode <code>POLYmode</code> is orthogonal to above.
   *  When set expression types can be polymorphic.
   */
  val POLYmode      = 0x020

  /** The mode <code>QUALmode</code> is orthogonal to above. When set
   *  expressions may be packages and Java statics modules.
   */
  val QUALmode      = 0x040

  /** The mode <code>TAPPmode</code> is set for the function/type constructor
   *  part of a type application. When set we do not decompose PolyTypes.
   */
  val TAPPmode      = 0x080

  /** The mode <code>SUPERCONSTRmode</code> is set for the <code>super</code>
   *  in a superclass constructor call <code>super.&lt;init&gt;</code>.
   */
  val SUPERCONSTRmode = 0x100

  /** The mode <code>SNDTRYmode</code> indicates that an application is typed
   *  for the 2nd time. In that case functions may no longer be coerced with
   *  implicit views.
   */
  val SNDTRYmode    = 0x200

  /** The mode <code>LHSmode</code> is set for the left-hand side of an
   *  assignment.
   */
  val LHSmode       = 0x400

  /** The mode <code>REGPATmode</code> is set when regular expression patterns
   *  are allowed. 
   */
  val REGPATmode    = 0x1000

  /** The mode <code>ALTmode</code> is set when we are under a pattern alternative */
  val ALTmode       = 0x2000
  
  /** The mode <code>HKmode</code> is set when we are typing a higher-kinded type
   * adapt should then check kind-arity based on the prototypical type's kind arity
   * type arguments should not be inferred
   */
  val HKmode        = 0x4000 // @M: could also use POLYmode | TAPPmode 

  /** The mode <code>TYPEPATmode</code> is set when we are typing a type in a pattern
   */
  val TYPEPATmode   = 0x10000

  private val stickyModes: Int  = EXPRmode | PATTERNmode | TYPEmode | ALTmode

  private def funMode(mode: Int) = mode & (stickyModes | SCCmode) | FUNmode | POLYmode

  private def typeMode(mode: Int) = 
    if ((mode & (PATTERNmode | TYPEPATmode)) != 0) TYPEmode | TYPEPATmode
    else TYPEmode

  private def argMode(fun: Tree, mode: Int) =
    if (treeInfo.isSelfOrSuperConstrCall(fun)) mode | SCCmode 
    else mode 

  abstract class Typer(context0: Context) {
    import context0.unit

    val infer = new Inferencer(context0) {
      override def isCoercible(tp: Type, pt: Type): Boolean =
        tp.isError || pt.isError ||
        context0.implicitsEnabled && // this condition prevents chains of views
        inferView(EmptyTree, tp, pt, false) != EmptyTree
    }

    /** Find implicit arguments and pass them to given tree.
     */
    def applyImplicitArgs(fun: Tree): Tree = fun.tpe match {
      case MethodType(params, _) =>
        var positional = true
        val argResults = params map (p => inferImplicit(fun, p.tpe, true, false, context))
        val args = argResults.zip(params) flatMap {
          case (arg, param) =>
            if (arg != SearchFailure) {
              if (positional) List(arg.tree)
              else List(atPos(arg.tree.pos)(new AssignOrNamedArg(Ident(param.name), (arg.tree))))
            } else {
              if (!param.hasFlag(DEFAULTPARAM))
                context.error(
                  fun.pos, "could not find implicit value for "+
                  (if (param.name startsWith nme.EVIDENCE_PARAM_PREFIX) "evidence parameter of type "
                   else "parameter "+param.name+": ")+param.tpe)
              positional = false
              Nil
            }
        }
        for (s <- argResults map (_.subst)) {
          s traverse fun
          for (arg <- args) s traverse arg
        }
        Apply(fun, args) setPos fun.pos
      case ErrorType =>
        fun
    }

    /** Infer an implicit conversion (``view'') between two types.
     *  @param tree             The tree which needs to be converted.
     *  @param from             The source type of the conversion
     *  @param to               The target type of the conversion
     *  @param reportAmbiguous  Should ambiguous implicit errors be reported?
     *                          False iff we search for a view to find out
     *                          whether one type is coercible to another.
     */
    def inferView(tree: Tree, from: Type, to: Type, reportAmbiguous: Boolean): Tree = {
      if (settings.debug.value) log("infer view from "+from+" to "+to)//debug
      if (phase.id > currentRun.typerPhase.id) EmptyTree
      else from match {
        case MethodType(_, _) => EmptyTree
        case OverloadedType(_, _) => EmptyTree
        case PolyType(_, _) => EmptyTree
        case _ =>
          def wrapImplicit(from: Type): Tree = {
            val result = inferImplicit(tree, functionType(List(from), to), reportAmbiguous, true, context)
            if (result.subst != EmptyTreeTypeSubstituter) result.subst traverse tree
            result.tree
          }
          val result = wrapImplicit(from)
          if (result != EmptyTree) result
          else wrapImplicit(appliedType(ByNameParamClass.typeConstructor, List(from)))
      }
    }

    /** Infer an implicit conversion (``view'') that makes a member available.
     *  @param tree             The tree which needs to be converted.
     *  @param from             The source type of the conversion
     *  @param name             The name of the member that needs to be available
     *  @param tp               The expected type of the member that needs to be available
     */
    def inferView(tree: Tree, from: Type, name: Name, tp: Type): Tree = {
      val to = refinedType(List(WildcardType), NoSymbol)
      var psym = if (name.isTypeName) to.typeSymbol.newAbstractType(tree.pos, name) 
                 else to.typeSymbol.newValue(tree.pos, name)
      psym = to.decls enter psym
      psym setInfo tp
      inferView(tree, from, to, true)
    }

    import infer._

    private var namerCache: Namer = null
    def namer = {
      if ((namerCache eq null) || namerCache.context != context)
        namerCache = newNamer(context)
      namerCache
    }

    private[typechecker] var context = context0
    def context1 = context

    /** Report a type error.
     *
     *  @param pos0   The position where to report the error
     *  @param ex     The exception that caused the error
     */
    def reportTypeError(pos0: Position, ex: TypeError) {
      if (settings.debug.value) ex.printStackTrace()
      val pos = if (ex.pos == NoPosition) pos0 else ex.pos
      ex match {
        case CyclicReference(sym, info: TypeCompleter) =>
          val msg = 
            info.tree match {
              case ValDef(_, _, tpt, _) if (tpt.tpe eq null) =>
                "recursive "+sym+" needs type"
              case DefDef(_, _, _, _, tpt, _) if (tpt.tpe eq null) =>
                (if (sym.owner.isClass && sym.owner.info.member(sym.name).hasFlag(OVERLOADED)) "overloaded "
                 else "recursive ")+sym+" needs result type"
              case _ =>
                ex.getMessage()
            }
          context.error(pos, msg)
          if (sym == ObjectClass) 
            throw new FatalError("cannot redefine root "+sym)
        case _ =>
          context.error(pos, ex)
      }
    }

    /** Check that <code>tree</code> is a stable expression.
     *
     *  @param tree ...
     *  @return     ...
     */
    def checkStable(tree: Tree): Tree =
      if (treeInfo.isPureExpr(tree)) tree
      else errorTree(
        tree, 
        "stable identifier required, but "+tree+" found."+
        (if (isStableExceptVolatile(tree)) {
          val tpe = tree.symbol.tpe match {
            case PolyType(_, rtpe) => rtpe
            case t => t
          }
          "\n Note that "+tree.symbol+" is not stable because its type, "+tree.tpe+", is volatile."
      } else ""))

    /** Would tree be a stable (i.e. a pure expression) if the type
     *  of its symbol was not volatile?
     */
    private def isStableExceptVolatile(tree: Tree) = {
      tree.hasSymbol && tree.symbol != NoSymbol && tree.tpe.isVolatile &&
      { val savedTpe = tree.symbol.info
        val savedSTABLE = tree.symbol getFlag STABLE
        tree.symbol setInfo AnyRefClass.tpe
        tree.symbol setFlag STABLE
       val result = treeInfo.isPureExpr(tree)
        tree.symbol setInfo savedTpe
        tree.symbol setFlag savedSTABLE
        result
      }
    }

    /** Check that `tpt' refers to a non-refinement class type */
    def checkClassType(tpt: Tree, existentialOK: Boolean) {
      def check(tpe: Type): Unit = tpe.normalize match {
        case TypeRef(_, sym, _) if sym.isClass && !sym.isRefinementClass => ;
        case ErrorType => ;
        case PolyType(_, restpe) => check(restpe)
        case ExistentialType(_, restpe) if existentialOK => check(restpe)
        case AnnotatedType(_, underlying, _) => check(underlying)
        case t => error(tpt.pos, "class type required but "+t+" found")
      }
      check(tpt.tpe)
    }

    /** Check that type <code>tp</code> is not a subtype of itself.
     *
     *  @param pos ...
     *  @param tp  ...
     *  @return    <code>true</code> if <code>tp</code> is not a subtype of itself.
     */
    def checkNonCyclic(pos: Position, tp: Type): Boolean = {
      def checkNotLocked(sym: Symbol): Boolean = {
        sym.initialize
        sym.lockOK || {error(pos, "cyclic aliasing or subtyping involving "+sym); false}
      }
      tp match {
        case TypeRef(pre, sym, args) =>
          (checkNotLocked(sym)) && (
            !sym.isTypeMember ||
            checkNonCyclic(pos, appliedType(pre.memberInfo(sym), args), sym)   // @M! info for a type ref to a type parameter now returns a polytype
            // @M was: checkNonCyclic(pos, pre.memberInfo(sym).subst(sym.typeParams, args), sym)
          )
        case SingleType(pre, sym) =>
          checkNotLocked(sym)
/*
        case TypeBounds(lo, hi) =>
          var ok = true
          for (t <- lo) ok = ok & checkNonCyclic(pos, t)
          ok
*/
        case st: SubType =>
          checkNonCyclic(pos, st.supertype)
        case ct: CompoundType =>
          var p = ct.parents
          while (!p.isEmpty && checkNonCyclic(pos, p.head)) p = p.tail
          p.isEmpty
        case _ =>
          true
      }
    }

    def checkNonCyclic(pos: Position, tp: Type, lockedSym: Symbol): Boolean = {
      lockedSym.lock {
        throw new TypeError("illegal cyclic reference involving " + lockedSym)
      }
      val result = checkNonCyclic(pos, tp)
      lockedSym.unlock()
      result
    }

    def checkNonCyclic(sym: Symbol) {
      if (!checkNonCyclic(sym.pos, sym.tpe)) sym.setInfo(ErrorType)
    }

    def checkNonCyclic(defn: Tree, tpt: Tree) {
      if (!checkNonCyclic(defn.pos, tpt.tpe, defn.symbol)) {
        tpt.tpe = ErrorType
        defn.symbol.setInfo(ErrorType)
      }
    }

    def checkParamsConvertible(pos: Position, tpe: Type) {
      tpe match {
        case MethodType(formals, restpe) =>
          /*
          if (formals.exists(_.typeSymbol == ByNameParamClass) && formals.length != 1)
            error(pos, "methods with `=>'-parameter can be converted to function values only if they take no other parameters")
          if (formals exists (isRepeatedParamType(_)))
            error(pos, "methods with `*'-parameters cannot be converted to function values");
          */
          if (restpe.isDependent)
            error(pos, "method with dependent type "+tpe+" cannot be converted to function value");
          checkParamsConvertible(pos, restpe)
        case _ =>
      }
    }

    def checkRegPatOK(pos: Position, mode: Int) = 
      if ((mode & REGPATmode) == 0 && 
          phase.id <= currentRun.typerPhase.id) // fixes t1059
        error(pos, "no regular expression pattern allowed here\n"+
              "(regular expression patterns are only allowed in arguments to *-parameters)")

    /** Check that type of given tree does not contain local or private
     *  components.
     */
    object checkNoEscaping extends TypeMap {
      private var owner: Symbol = _
      private var scope: Scope = _
      private var hiddenSymbols: List[Symbol] = _

      /** Check that type <code>tree</code> does not refer to private
       *  components unless itself is wrapped in something private
       *  (<code>owner</code> tells where the type occurs).
       *
       *  @param owner ...
       *  @param tree  ...
       *  @return      ...
       */
      def privates[T <: Tree](owner: Symbol, tree: T): T =
        check(owner, EmptyScope, WildcardType, tree)

      /** Check that type <code>tree</code> does not refer to entities
       *  defined in scope <code>scope</code>.
       *
       *  @param scope ...
       *  @param pt    ...
       *  @param tree  ...
       *  @return      ...
       */
      def locals[T <: Tree](scope: Scope, pt: Type, tree: T): T =
        check(NoSymbol, scope, pt, tree)

      def check[T <: Tree](owner: Symbol, scope: Scope, pt: Type, tree: T): T = {
        this.owner = owner
        this.scope = scope
        hiddenSymbols = List()
        val tp1 = apply(tree.tpe)
        if (hiddenSymbols.isEmpty) tree setType tp1
        else if (hiddenSymbols exists (_.isErroneous)) setError(tree)
        else if (isFullyDefined(pt)) tree setType pt //todo: eliminate
        else if (tp1.typeSymbol.isAnonymousClass) // todo: eliminate
          check(owner, scope, pt, tree setType tp1.typeSymbol.classBound)
        else if (owner == NoSymbol)
          tree setType packSymbols(hiddenSymbols.reverse, tp1)
        else { // privates
          val badSymbol = hiddenSymbols.head
          error(tree.pos,
                (if (badSymbol hasFlag PRIVATE) "private " else "") + badSymbol +
                " escapes its defining scope as part of type "+tree.tpe)
          setError(tree)
        }
      }

      def addHidden(sym: Symbol) =
        if (!(hiddenSymbols contains sym)) hiddenSymbols = sym :: hiddenSymbols

      override def apply(t: Type): Type = {
        def checkNoEscape(sym: Symbol) {
          if (sym.hasFlag(PRIVATE)) {
            var o = owner
            while (o != NoSymbol && o != sym.owner && 
                   !o.isLocal && !o.hasFlag(PRIVATE) &&
                   !o.privateWithin.hasTransOwner(sym.owner))
              o = o.owner
            if (o == sym.owner) addHidden(sym)
          } else if (sym.owner.isTerm && !sym.isTypeParameterOrSkolem) {
            var e = scope.lookupEntryWithContext(sym.name)(context.owner)
            var found = false
            while (!found && (e ne null) && e.owner == scope) {
              if (e.sym == sym) {
                found = true
                addHidden(sym)
              } else {
                e = scope.lookupNextEntry(e)
              }
            }
          }
        }
        mapOver(
          t match {
            case TypeRef(_, sym, args) => 
              checkNoEscape(sym)
              if (!hiddenSymbols.isEmpty && hiddenSymbols.head == sym && 
                  sym.isAliasType && sym.typeParams.length == args.length) {
                hiddenSymbols = hiddenSymbols.tail
                t.normalize
              } else t
            case SingleType(_, sym) => 
              checkNoEscape(sym)
              t
            case _ =>
              t
          })
      }
    }

    def reenterValueParams(vparamss: List[List[ValDef]]) {
      for (vparams <- vparamss)
        for (vparam <- vparams)
          vparam.symbol = context.scope enter vparam.symbol
    }

    def reenterTypeParams(tparams: List[TypeDef]): List[Symbol] =
      for (tparam <- tparams) yield {
        tparam.symbol = context.scope enter tparam.symbol
        tparam.symbol.deSkolemize 
      } 

    /** The qualifying class
     *  of a this or super with prefix <code>qual</code>.
     */
    def qualifyingClass(tree: Tree, qual: Name, packageOK: Boolean): Symbol =
      context.enclClass.owner.ownerChain.find(o => qual.isEmpty || o.isClass && o.name == qual) match {
        case Some(c) if packageOK || !c.isPackageClass =>
          c
        case _ => 
          error(
            tree.pos, 
            if (qual.isEmpty) tree+" can be used only in a class, object, or template"
            else qual+" is not an enclosing class")
          NoSymbol
      }

    /** The typer for an expression, depending on where we are. If we are before a superclass 
     *  call, this is a typer over a constructor context; otherwise it is the current typer.
     */  
    def constrTyperIf(inConstr: Boolean): Typer =  
      if (inConstr) {
        assert(context.undetparams.isEmpty)
        newTyper(context.makeConstructorContext) 
      } else this

    /** The typer for a label definition. If this is part of a template we
     *  first have to enter the label definition.
     */
    def labelTyper(ldef: LabelDef): Typer = 
      if (ldef.symbol == NoSymbol) { // labeldef is part of template
        val typer1 = newTyper(context.makeNewScope(ldef, context.owner)(LabelScopeKind))
        typer1.enterLabelDef(ldef)
        typer1
      } else this

    final val xtypes = false

    /** Does the context of tree <code>tree</code> require a stable type?
     */
    private def isStableContext(tree: Tree, mode: Int, pt: Type) =  
      isNarrowable(tree.tpe) && ((mode & (EXPRmode | LHSmode)) == EXPRmode) && 
      (xtypes ||
      (pt.isStable ||
       (mode & QUALmode) != 0 && !tree.symbol.isConstant ||
       pt.typeSymbol.isAbstractType && pt.bounds.lo.isStable && !(tree.tpe <:< pt)) ||
       pt.typeSymbol.isRefinementClass && !(tree.tpe <:< pt))

    /** Make symbol accessible. This means:
     *  If symbol refers to package object, insert `.package` as second to last selector.
     *  (exception for some symbols in scala package which are dealiased immediately)
     *  Call checkAccessible, which sets tree's attributes.
     *  @return modified tree and new prefix type
     */
    private def makeAccessible(tree: Tree, sym: Symbol, pre: Type, site: Tree): (Tree, Type) =
      if (isInPackageObject(sym, pre.typeSymbol)) {
        if (pre.typeSymbol == ScalaPackageClass && sym.isTerm) {
          // short cut some aliases. It seems that without that pattern matching
          // fails to notice exhaustiveness and to generate good code when
          // List extractors are mixed with :: patterns. See Test5 in lists.scala.
          def dealias(sym: Symbol) =
            ({ val t = gen.mkAttributedRef(sym) ; t.setPos(tree.pos) ; t }, sym.owner.thisType)
          sym.name match {
            case nme.List => return dealias(ListModule)
            case nme.Seq => return dealias(SeqModule)
            case nme.Nil => return dealias(NilModule)
            case _ =>
          }
        }
        val qual = typedQualifier { atPos(tree.pos.focusStart) {
          tree match {
            case Ident(_) => Ident(nme.PACKAGEkw)
            case Select(qual, _) => Select(qual, nme.PACKAGEkw)
            case SelectFromTypeTree(qual, _) => Select(qual, nme.PACKAGEkw)
          }
        }}
        val tree1 = atPos(tree.pos) {
          tree match {
            case Ident(name) => Select(qual, name)
            case Select(_, name) => Select(qual, name)
            case SelectFromTypeTree(_, name) => SelectFromTypeTree(qual, name)
          }
        }
        (checkAccessible(tree1, sym, qual.tpe, qual), qual.tpe)
      } else {
        (checkAccessible(tree, sym, pre, site), pre)
      }

    private def isInPackageObject(sym: Symbol, pkg: Symbol) =
      pkg.isPackageClass &&
      sym.owner.isPackageObjectClass && 
      sym.owner.owner == pkg

    /** Post-process an identifier or selection node, performing the following:
     *  1. Check that non-function pattern expressions are stable
     *  2. Check that packages and static modules are not used as values
     *  3. Turn tree type into stable type if possible and required by context.
     *  </ol>
     */
    private def stabilize(tree: Tree, pre: Type, mode: Int, pt: Type): Tree = {
      def isNotAValue(sym: Symbol) =    // bug #1392
        !sym.isValue || (sym.isModule && isValueClass(sym.linkedClassOfModule))

      if (tree.symbol.hasFlag(OVERLOADED) && (mode & FUNmode) == 0)
        inferExprAlternative(tree, pt)
      val sym = tree.symbol
      if (tree.tpe.isError) tree
      else if ((mode & (PATTERNmode | FUNmode)) == PATTERNmode && tree.isTerm) { // (1)
        checkStable(tree)
      } else if ((mode & (EXPRmode | QUALmode)) == EXPRmode && isNotAValue(sym) && !phase.erasedTypes) { // (2)
        errorTree(tree, sym+" is not a value")
      } else {
        if (sym.isStable && pre.isStable && tree.tpe.typeSymbol != ByNameParamClass &&
            (isStableContext(tree, mode, pt) || sym.isModule && !sym.isMethod))
          tree.setType(singleType(pre, sym))
        else tree
      }
    }

    private def isNarrowable(tpe: Type): Boolean = tpe match {
      case TypeRef(_, _, _) | RefinedType(_, _) => true
      case ExistentialType(_, tpe1) => isNarrowable(tpe1)
      case AnnotatedType(_, tpe1, _) => isNarrowable(tpe1)
      case PolyType(_, tpe1) => isNarrowable(tpe1)
      case _ => !phase.erasedTypes
    } 

    private def stabilizedType(tree: Tree): Type = tree.tpe
/*{
      val sym = tree.symbol
      val res = tree match {
        case Ident(_) if (sym.isStable) =>
          val pre = if (sym.owner.isClass) sym.owner.thisType else NoPrefix 
          singleType(pre, sym)
        case Select(qual, _) if (qual.tpe.isStable && sym.isStable) =>
          singleType(qual.tpe, sym)
        case _ =>
          tree.tpe
      }
      res
    }
*/
    /**
     *  @param tree ...
     *  @param mode ...
     *  @param pt   ...
     *  @return     ...
     */
    def stabilizeFun(tree: Tree, mode: Int, pt: Type): Tree = {
      val sym = tree.symbol
      val pre = tree match {
        case Select(qual, _) => qual.tpe
        case _ => NoPrefix
      }
      if (tree.tpe.isInstanceOf[MethodType] && pre.isStable && sym.tpe.paramTypes.isEmpty &&
          (isStableContext(tree, mode, pt) || sym.isModule))
        tree.setType(MethodType(List(), singleType(pre, sym)))
      else tree
    }

    /** The member with given name of given qualifier tree */
    def member(qual: Tree, name: Name)(from : Symbol) = qual.tpe match {
      case ThisType(clazz) if (context.enclClass.owner.hasTransOwner(clazz)) =>
        qual.tpe.member(name)
      case _  =>
        if (phase.next.erasedTypes) qual.tpe.member(name)
        else qual.tpe.nonLocalMember(name)(from)
    }      

    def silent(op: Typer => Tree): AnyRef /* in fact, TypeError or Tree */ = { 
      val start = System.nanoTime()
      try {
      if (context.reportGeneralErrors) {
        val context1 = context.makeSilent(context.reportAmbiguousErrors)
        context1.undetparams = context.undetparams
        context1.savedTypeBounds = context.savedTypeBounds
        context1.namedApplyBlockInfo = context.namedApplyBlockInfo
        val typer1 = newTyper(context1)
        val result = op(typer1)
        context.undetparams = context1.undetparams
        context.savedTypeBounds = context1.savedTypeBounds
        context.namedApplyBlockInfo = context1.namedApplyBlockInfo
        result
      } else {
        op(this)
      }
    } catch {
      case ex: CyclicReference => throw ex
      case ex: TypeError =>
        failedSilent += System.nanoTime() - start
        ex
    }}

    /** Utility method: Try op1 on tree. If that gives an error try op2 instead.
     */
    def tryBoth(tree: Tree)(op1: (Typer, Tree) => Tree)(op2: (Typer, Tree) => Tree): Tree =
      silent(op1(_, tree.duplicate)) match {
        case result1: Tree => 
          result1
        case ex1: TypeError =>
          silent(op2(_, tree)) match {
            case result2: Tree =>
//              println("snd succeeded: "+result2)
              result2
            case ex2: TypeError =>
              reportTypeError(tree.pos, ex1)
              setError(tree)
          }
      }

    /** Perform the following adaptations of expression, pattern or type `tree' wrt to 
     *  given mode `mode' and given prototype `pt':
     *  (-1) For expressions with annotated types, let AnnotationCheckers decide what to do
     *  (0) Convert expressions with constant types to literals
     *  (1) Resolve overloading, unless mode contains FUNmode 
     *  (2) Apply parameterless functions
     *  (3) Apply polymorphic types to fresh instances of their type parameters and
     *      store these instances in context.undetparams, 
     *      unless followed by explicit type application.
     *  (4) Do the following to unapplied methods used as values:
     *  (4.1) If the method has only implicit parameters pass implicit arguments
     *  (4.2) otherwise, if `pt' is a function type and method is not a constructor,
     *        convert to function by eta-expansion,
     *  (4.3) otherwise, if the method is nullary with a result type compatible to `pt'
     *        and it is not a constructor, apply it to ()
     *  otherwise issue an error
     *  (5) Convert constructors in a pattern as follows:
     *  (5.1) If constructor refers to a case class factory, set tree's type to the unique
     *        instance of its primary constructor that is a subtype of the expected type.
     *  (5.2) If constructor refers to an exractor, convert to application of
     *        unapply or unapplySeq method.
     *
     *  (6) Convert all other types to TypeTree nodes.
     *  (7) When in TYPEmode but not FUNmode or HKmode, check that types are fully parameterized
     *      (7.1) In HKmode, higher-kinded types are allowed, but they must have the expected kind-arity
     *  (8) When in both EXPRmode and FUNmode, add apply method calls to values of object type.
     *  (9) If there are undetermined type variables and not POLYmode, infer expression instance
     *  Then, if tree's type is not a subtype of expected type, try the following adaptations:
     *  (10) If the expected type is Byte, Short or Char, and the expression
     *      is an integer fitting in the range of that type, convert it to that type. 
     *  (11) Widen numeric literals to their expected type, if necessary
     *  (12) When in mode EXPRmode, convert E to { E; () } if expected type is scala.Unit.
     *  (13) When in mode EXPRmode, apply a view
     *  If all this fails, error
     */
    protected def adapt(tree: Tree, mode: Int, pt: Type): Tree = tree.tpe match {
      case atp @ AnnotatedType(_, _, _) if canAdaptAnnotations(tree, mode, pt) => // (-1)
        adaptAnnotations(tree, mode, pt)
      case ct @ ConstantType(value) if ((mode & (TYPEmode | FUNmode)) == 0 && (ct <:< pt) && !onlyPresentation) => // (0)
        treeCopy.Literal(tree, value)
      case OverloadedType(pre, alts) if ((mode & FUNmode) == 0) => // (1)
        inferExprAlternative(tree, pt)
        adapt(tree, mode, pt)
      case PolyType(List(), restpe) => // (2)
        adapt(tree setType restpe, mode, pt)
      case TypeRef(_, sym, List(arg))
      if ((mode & EXPRmode) != 0 && sym == ByNameParamClass) => // (2)
        adapt(tree setType arg, mode, pt)
      case tr @ TypeRef(_, sym, _) 
      if sym.isAliasType && tr.normalize.isInstanceOf[ExistentialType] &&
        ((mode & (EXPRmode | LHSmode)) == EXPRmode) =>
        adapt(tree setType tr.normalize.skolemizeExistential(context.owner, tree), mode, pt)
      case et @ ExistentialType(_, _) if ((mode & (EXPRmode | LHSmode)) == EXPRmode) =>
        adapt(tree setType et.skolemizeExistential(context.owner, tree), mode, pt)
      case PolyType(tparams, restpe) if ((mode & (TAPPmode | PATTERNmode | HKmode)) == 0) => // (3)
        // assert((mode & HKmode) == 0) //@M a PolyType in HKmode represents an anonymous type function,
        // we're in HKmode since a higher-kinded type is expected --> hence, don't implicitly apply it to type params!
        // ticket #2197 triggered turning the assert into a guard
        // I guess this assert wasn't violated before because type aliases weren't expanded as eagerly
        //  (the only way to get a PolyType for an anonymous type function is by normalisation, which applies eta-expansion)
          // -- are we sure we want to expand aliases this early?
          // -- what caused this change in behaviour??
        val tparams1 = cloneSymbols(tparams)
        val tree1 = if (tree.isType) tree 
                    else TypeApply(tree, tparams1 map (tparam => 
                      TypeTree(tparam.tpe) setPos tree.pos.focus)) setPos tree.pos
        context.undetparams = context.undetparams ::: tparams1
        adapt(tree1 setType restpe.substSym(tparams, tparams1), mode, pt)
      case mt: ImplicitMethodType if ((mode & (EXPRmode | FUNmode | LHSmode)) == EXPRmode) => // (4.1)
        if (!context.undetparams.isEmpty/* && (mode & POLYmode) == 0 disabled to make implicits in new collection work; we should revisit this. */) { // (9)
          context.undetparams = inferExprInstance(
            tree, context.extractUndetparams(), pt, mt.paramTypes exists isManifest)
              // if we are looking for a manifest, instantiate type to Nothing anyway,
              // as we would get amnbiguity errors otherwise. Example
              // Looking for a manifest of Nil: This mas many potential types,
              // so we need to instantiate to minimal type List[Nothing].
        } 
        val typer1 = constrTyperIf(treeInfo.isSelfOrSuperConstrCall(tree))
        typer1.typed(typer1.applyImplicitArgs(tree), mode, pt)
      case mt: MethodType
      if (((mode & (EXPRmode | FUNmode | LHSmode)) == EXPRmode) && 
          (context.undetparams.isEmpty || (mode & POLYmode) != 0)) =>

        val meth = tree match {
          // a partial named application is a block (see comment in EtaExpansion)
          case Block(_, tree1) => tree1.symbol
          case _ => tree.symbol
        }
        if (!meth.isConstructor && 
            //isCompatible(tparamsToWildcards(mt, context.undetparams), pt) &&
            isFunctionType(pt))/* &&
            (pt <:< functionType(mt.paramTypes map (t => WildcardType), WildcardType)))*/ { // (4.2)
          if (settings.debug.value) log("eta-expanding "+tree+":"+tree.tpe+" to "+pt)
          checkParamsConvertible(tree.pos, tree.tpe)
          val tree1 = etaExpand(context.unit, tree)
          //println("eta "+tree+" ---> "+tree1+":"+tree1.tpe)
          typed(tree1, mode, pt)
        } else if (!meth.isConstructor && mt.params.isEmpty) { // (4.3)
          adapt(typed(Apply(tree, List()) setPos tree.pos), mode, pt)
        } else if (context.implicitsEnabled) {
          errorTree(tree, "missing arguments for "+meth+meth.locationString+
                    (if (meth.isConstructor) ""
                     else ";\nfollow this method with `_' if you want to treat it as a partially applied function"))
        } else {
          setError(tree)
        }
      case _ =>
        def applyPossible = {
          def applyMeth = member(adaptToName(tree, nme.apply), nme.apply)(context.owner)
          if ((mode & TAPPmode) != 0)
            tree.tpe.typeParams.isEmpty && applyMeth.filter(! _.tpe.typeParams.isEmpty) != NoSymbol
          else 
            applyMeth.filter(_.tpe.paramSectionCount > 0) != NoSymbol
        }
        if (tree.isType) {
          if ((mode & FUNmode) != 0) {
            tree
          } else if (tree.hasSymbol && !tree.symbol.typeParams.isEmpty && (mode & HKmode) == 0 &&
                     !(tree.symbol.hasFlag(JAVA) && context.unit.isJava)) { // (7) 
            // @M When not typing a higher-kinded type ((mode & HKmode) == 0) 
            // or raw type (tree.symbol.hasFlag(JAVA) && context.unit.isJava), types must be of kind *, 
            // and thus parameterised types must be applied to their type arguments
            // @M TODO: why do kind-* tree's have symbols, while higher-kinded ones don't?
            errorTree(tree, tree.symbol+" takes type parameters")
            tree setType tree.tpe
          } else if ( // (7.1) @M: check kind-arity 
                    // @M: removed check for tree.hasSymbol and replace tree.symbol by tree.tpe.symbol (TypeTree's must also be checked here, and they don't directly have a symbol)
                     ((mode & HKmode) != 0) && 
                    // @M: don't check tree.tpe.symbol.typeParams. check tree.tpe.typeParams!!! 
                    // (e.g., m[Int] --> tree.tpe.symbol.typeParams.length == 1, tree.tpe.typeParams.length == 0!)
                     tree.tpe.typeParams.length != pt.typeParams.length && 
                     !(tree.tpe.typeSymbol==AnyClass || 
                       tree.tpe.typeSymbol==NothingClass || 
                       pt == WildcardType )) {
              // Check that the actual kind arity (tree.symbol.typeParams.length) conforms to the expected
              // kind-arity (pt.typeParams.length). Full checks are done in checkKindBounds in Infer.
              // Note that we treat Any and Nothing as kind-polymorphic. 
              // We can't perform this check when typing type arguments to an overloaded method before the overload is resolved 
              // (or in the case of an error type) -- this is indicated by pt == WildcardType (see case TypeApply in typed1).
              errorTree(tree, tree.tpe+" takes "+reporter.countElementsAsString(tree.tpe.typeParams.length, "type parameter")+
                              ", expected: "+reporter.countAsString(pt.typeParams.length))
              tree setType tree.tpe
          } else tree match { // (6)
            case TypeTree() => tree
            case _ => TypeTree(tree.tpe) setOriginal(tree)
          }
        } else if ((mode & (PATTERNmode | FUNmode)) == (PATTERNmode | FUNmode)) { // (5)
          val extractor = tree.symbol.filter(sym => unapplyMember(sym.tpe).exists)
          if (extractor != NoSymbol) {
            tree setSymbol extractor
            val unapply = unapplyMember(extractor.tpe)
            val clazz = if (unapply.tpe.paramTypes.length == 1) unapply.tpe.paramTypes.head.typeSymbol 
                        else NoSymbol
            if ((unapply hasFlag CASE) && (clazz hasFlag CASE) && 
                !(clazz.info.baseClasses.tail exists (_ hasFlag CASE))) {
              if (!phase.erasedTypes) checkStable(tree) //todo: do we need to demand this?
              // convert synthetic unapply of case class to case class constructor
              val prefix = tree.tpe.prefix
              val tree1 = TypeTree(clazz.primaryConstructor.tpe.asSeenFrom(prefix, clazz.owner))
                  .setOriginal(tree)
              try {
                inferConstructorInstance(tree1, clazz.typeParams, pt)
              } catch {
                case tpe : TypeError => throw tpe
                case t : Exception =>
                  logError("CONTEXT: " + (tree.pos).dbgString, t)
                  throw t
              }
              tree1
            } else {
              tree
            }
          } else {
            errorTree(tree, tree.symbol + " is not a case class constructor, nor does it have an unapply/unapplySeq method")
          }
        } else if ((mode & (EXPRmode | FUNmode)) == (EXPRmode | FUNmode) && 
                   !tree.tpe.isInstanceOf[MethodType] && 
                   !tree.tpe.isInstanceOf[OverloadedType] && 
                   applyPossible) {
          assert((mode & HKmode) == 0) //@M
          val qual = adaptToName(tree, nme.apply) match {
            case id @ Ident(_) =>
              val pre = if (id.symbol.owner.isPackageClass) id.symbol.owner.thisType
                        else if (id.symbol.owner.isClass) 
                          context.enclosingSubClassContext(id.symbol.owner).prefix
                        else NoPrefix
              stabilize(id, pre, EXPRmode | QUALmode, WildcardType)
            case sel @ Select(qualqual, _) => 
              stabilize(sel, qualqual.tpe, EXPRmode | QUALmode, WildcardType)
            case other => 
              other
          }
          typed(atPos(tree.pos)(Select(qual, nme.apply)), mode, pt)
        } else if (!context.undetparams.isEmpty && (mode & POLYmode) == 0) { // (9)
          assert((mode & HKmode) == 0) //@M
          instantiate(tree, mode, pt)
        } else if (tree.tpe <:< pt) {
          tree
        } else {
          if ((mode & PATTERNmode) != 0) {
            if ((tree.symbol ne null) && tree.symbol.isModule)
              inferModulePattern(tree, pt)
            if (isPopulated(tree.tpe, approximateAbstracts(pt)))
              return tree
          }
          val tree1 = constfold(tree, pt) // (10) (11)
          if (tree1.tpe <:< pt) adapt(tree1, mode, pt)
          else {
            if ((mode & (EXPRmode | FUNmode)) == EXPRmode) {
              pt.normalize match {
                case TypeRef(_, sym, _) =>
                  // note: was if (pt.typeSymbol == UnitClass) but this leads to a potentially
                  // infinite expansion if pt is constant type ()
                  if (sym == UnitClass && tree.tpe <:< AnyClass.tpe) // (12)
                    return typed(atPos(tree.pos)(Block(List(tree), Literal(()))), mode, pt)
                case _ =>
              }
              if (!context.undetparams.isEmpty) {
                return instantiate(tree, mode, pt)
              }
              if (context.implicitsEnabled && !tree.tpe.isError && !pt.isError) { 
                // (13); the condition prevents chains of views 
                if (settings.debug.value) log("inferring view from "+tree.tpe+" to "+pt)
                val coercion = inferView(tree, tree.tpe, pt, true)
                // convert forward views of delegate types into closures wrapped around
                // the delegate's apply method (the "Invoke" method, which was translated into apply)
                if (forMSIL && coercion != null && isCorrespondingDelegate(tree.tpe, pt)) {
                  val meth: Symbol = tree.tpe.member(nme.apply)
                  if(settings.debug.value)
                    log("replacing forward delegate view with: " + meth + ":" + meth.tpe)
                  return typed(Select(tree, meth), mode, pt)
                }
                if (coercion != EmptyTree) {
                  if (settings.debug.value) log("inferred view from "+tree.tpe+" to "+pt+" = "+coercion+":"+coercion.tpe)
                  return newTyper(context.makeImplicit(context.reportAmbiguousErrors)).typed(
                    Apply(coercion, List(tree)) setPos tree.pos, mode, pt)
                }
              }
            }
            if (settings.debug.value) {
              log("error tree = "+tree)
              if (settings.explaintypes.value) explainTypes(tree.tpe, pt)
            }
            typeErrorTree(tree, tree.tpe, pt)
          }
        }
    }

    /**
     *  @param tree ...
     *  @param mode ...
     *  @param pt   ...
     *  @return     ...
     */
    def instantiate(tree: Tree, mode: Int, pt: Type): Tree = {
      inferExprInstance(tree, context.extractUndetparams(), pt, true)
      adapt(tree, mode, pt)
    }

    /**
     *  @param qual ...
     *  @param name ...
     *  @param tp   ...
     *  @return     ...
     */
    def adaptToMember(qual: Tree, name: Name, tp: Type): Tree = {
      val qtpe = qual.tpe.widen
      if (qual.isTerm && 
          ((qual.symbol eq null) || !qual.symbol.isTerm || qual.symbol.isValue) &&
          phase.id <= currentRun.typerPhase.id && !qtpe.isError && !tp.isError &&
          qtpe.typeSymbol != NullClass && qtpe.typeSymbol != NothingClass && qtpe != WildcardType && 
          context.implicitsEnabled) { // don't try to adapt a top-level type that's the subject of an implicit search
                                      // this happens because, if isView, typedImplicit tries to apply the "current" implicit value to 
                                      // a value that needs to be coerced, so we check whether the implicit value has an `apply` method
                                     // (if we allow this, we get divergence, e.g., starting at `conforms` during ant quick.bin) 
                                     // note: implicit arguments are still inferred (this kind of "chaining" is allowed)
        val coercion = inferView(qual, qtpe, name, tp)
        if (coercion != EmptyTree) 
          typedQualifier(atPos(qual.pos)(Apply(coercion, List(qual))))
        else qual
      } else qual
    }

    def adaptToName(qual: Tree, name: Name) =
      if (member(qual, name)(context.owner) != NoSymbol) qual
      else adaptToMember(qual, name, WildcardType)

    private def typePrimaryConstrBody(clazz : Symbol, cbody: Tree, tparams: List[Symbol], enclTparams: List[Symbol], vparamss: List[List[ValDef]]): Tree = {
      // XXX: see about using the class's symbol....
      enclTparams foreach (sym => context.scope.enter(sym))
      namer.enterValueParams(context.owner, vparamss)
      typed(cbody)
    }

    def parentTypes(templ: Template): List[Tree] = 
      if (templ.parents.isEmpty) List()
      else try {
        val clazz = context.owner

        // Normalize supertype and mixins so that supertype is always a class, not a trait.
        var supertpt = typedTypeConstructor(templ.parents.head)
        val firstParent = supertpt.tpe.typeSymbol
        var mixins = templ.parents.tail map typedType
        // If first parent is a trait, make it first mixin and add its superclass as first parent 
        while ((supertpt.tpe.typeSymbol ne null) && supertpt.tpe.typeSymbol.initialize.isTrait) {
          val supertpt1 = typedType(supertpt)
          if (!supertpt1.tpe.isError) {
            mixins = supertpt1 :: mixins
            supertpt = TypeTree(supertpt1.tpe.parents.head) setPos supertpt.pos.focus
          }
        }

        // Determine 
        //  - supertparams: Missing type parameters from supertype
        //  - supertpe: Given supertype, polymorphic in supertparams
        val supertparams = if (supertpt.hasSymbol) supertpt.symbol.typeParams else List()
        var supertpe = supertpt.tpe
        if (!supertparams.isEmpty)
          supertpe = PolyType(supertparams, appliedType(supertpe, supertparams map (_.tpe)))

        // A method to replace a super reference by a New in a supercall
        def transformSuperCall(scall: Tree): Tree = (scall: @unchecked) match {
          case Apply(fn, args) =>
            treeCopy.Apply(scall, transformSuperCall(fn), args map (_.duplicate))
          case Select(Super(_, _), nme.CONSTRUCTOR) =>
            treeCopy.Select(
              scall, 
              atPos(supertpt.pos.focus)(New(TypeTree(supertpe)) setType supertpe),
              nme.CONSTRUCTOR)
        }

        treeInfo.firstConstructor(templ.body) match {
          case constr @ DefDef(_, _, _, vparamss, _, cbody @ Block(cstats, cunit)) =>
            // Convert constructor body to block in environment and typecheck it
            val cstats1: List[Tree] = cstats map (_.duplicate)
            val scall = if (cstats.isEmpty) EmptyTree else cstats.last
            val cbody1 = scall match {
              case Apply(_, _) =>
                treeCopy.Block(cbody, cstats1.init, 
                           if (supertparams.isEmpty) cunit.duplicate 
                           else transformSuperCall(scall))
              case _ =>
                treeCopy.Block(cbody, cstats1, cunit.duplicate)
            }

            val outercontext = context.outer 
            assert(clazz != NoSymbol)
            val cscope = outercontext.makeNewScope(constr, outercontext.owner)(ParentTypesScopeKind(clazz))
            val cbody2 = newTyper(cscope) // called both during completion AND typing.
                .typePrimaryConstrBody(clazz,  
                  cbody1, supertparams, clazz.unsafeTypeParams, vparamss map (_.map(_.duplicate)))

            scall match {
              case Apply(_, _) =>
                val sarg = treeInfo.firstArgument(scall)
                if (sarg != EmptyTree && supertpe.typeSymbol != firstParent) 
                  error(sarg.pos, firstParent+" is a trait; does not take constructor arguments")
                if (!supertparams.isEmpty) supertpt = TypeTree(cbody2.tpe) setPos supertpt.pos.focus
              case _ =>
                if (!supertparams.isEmpty) error(supertpt.pos, "missing type arguments")
            }

            List.map2(cstats1, treeInfo.preSuperFields(templ.body)) {
              (ldef, gdef) => gdef.tpt.tpe = ldef.symbol.tpe
            }
          case _ =>
            if (!supertparams.isEmpty) error(supertpt.pos, "missing type arguments")
        }
/* experimental: early types as type arguments
        val hasEarlyTypes = templ.body exists (treeInfo.isEarlyTypeDef)
        val earlyMap = new EarlyMap(clazz)
        List.mapConserve(supertpt :: mixins){ tpt => 
          val tpt1 = checkNoEscaping.privates(clazz, tpt)
          if (hasEarlyTypes) tpt1 else tpt1 setType earlyMap(tpt1.tpe)
        }
*/

        //Console.println("parents("+clazz") = "+supertpt :: mixins);//DEBUG
        supertpt :: mixins mapConserve (tpt => checkNoEscaping.privates(clazz, tpt))
      } catch {
        case ex: TypeError =>
          templ.tpe = null
          reportTypeError(templ.pos, ex)
          List(TypeTree(AnyRefClass.tpe))
      }

    /** <p>Check that</p>
     *  <ul>
     *    <li>all parents are class types,</li>
     *    <li>first parent class is not a mixin; following classes are mixins,</li>
     *    <li>final classes are not inherited,</li>
     *    <li>
     *      sealed classes are only inherited by classes which are
     *      nested within definition of base class, or that occur within same
     *      statement sequence,
     *    </li>
     *    <li>self-type of current class is a subtype of self-type of each parent class.</li>
     *    <li>no two parents define same symbol.</li>
     *  </ul>
     */
    def validateParentClasses(parents: List[Tree], selfType: Type) {

      def validateParentClass(parent: Tree, superclazz: Symbol) {
        if (!parent.tpe.isError) {
          val psym = parent.tpe.typeSymbol.initialize
          checkClassType(parent, false)
          if (psym != superclazz) {
            if (psym.isTrait) {
              val ps = psym.info.parents
              if (!ps.isEmpty && !superclazz.isSubClass(ps.head.typeSymbol))
                error(parent.pos, "illegal inheritance; super"+superclazz+
                      "\n is not a subclass of the super"+ps.head.typeSymbol+
                      "\n of the mixin " + psym);
            } else {
              error(parent.pos, psym+" needs to be a trait to be mixed in")
            }
          } 
          if (psym hasFlag FINAL) {
            error(parent.pos, "illegal inheritance from final "+psym)
          } 
          // XXX I think this should issue a sharper warning of some kind like
          // "change your code now!" as there are material bugs (which will not be fixed)
          // associated with case class inheritance.
          if ((context.owner hasFlag CASE) && !phase.erasedTypes) {
            for (ancestor <- parent.tpe.baseClasses find (_ hasFlag CASE))
              unit.deprecationWarning(parent.pos, ( 
                "case class `%s' has case class ancestor `%s'.  This has been deprecated " +
                "for unduly complicating both usage and implementation.  You should instead " + 
                "use extractors for pattern matching on non-leaf nodes." ).format(context.owner, ancestor)                
              )
          }
          if (psym.isSealed && !phase.erasedTypes) {
            if (context.unit.source.file != psym.sourceFile)
              error(parent.pos, "illegal inheritance from sealed "+psym)
            else
              psym addChild context.owner
          }
          if (!(selfType <:< parent.tpe.typeOfThis) && 
              !phase.erasedTypes &&     
              !(context.owner hasFlag SYNTHETIC) && // don't do this check for synthetic concrete classes for virtuals (part of DEVIRTUALIZE)
              !(settings.suppressVTWarn.value))
          { 
            //Console.println(context.owner);//DEBUG
            //Console.println(context.owner.unsafeTypeParams);//DEBUG
            //Console.println(List.fromArray(context.owner.info.closure));//DEBUG
            error(parent.pos, "illegal inheritance;\n self-type "+
                  selfType+" does not conform to "+parent +
                  "'s selftype "+parent.tpe.typeOfThis)
            if (settings.explaintypes.value) explainTypes(selfType, parent.tpe.typeOfThis)
          }
          if (parents exists (p => p != parent && p.tpe.typeSymbol == psym && !psym.isError))
            error(parent.pos, psym+" is inherited twice")
        }
      }

      if (!parents.isEmpty && !parents.head.tpe.isError)
        for (p <- parents) validateParentClass(p, parents.head.tpe.typeSymbol)

/*
      if (settings.Xshowcls.value != "" &&
          settings.Xshowcls.value == context.owner.fullNameString)
        println("INFO "+context.owner+
                ", baseclasses = "+(context.owner.info.baseClasses map (_.fullNameString))+
                ", lin = "+(context.owner.info.baseClasses map (context.owner.thisType.baseType)))
*/ 
    }

    def checkFinitary(classinfo: ClassInfoType) {
      val clazz = classinfo.typeSymbol
      for (tparam <- clazz.typeParams) {
        if (classinfo.expansiveRefs(tparam) contains tparam) {
          error(tparam.pos, "class graph is not finitary because type parameter "+tparam.name+" is expansively recursive")
          val newinfo = ClassInfoType(
            classinfo.parents map (_.instantiateTypeParams(List(tparam), List(AnyRefClass.tpe))), 
            classinfo.decls, 
            clazz)
          clazz.setInfo {
            clazz.info match {
              case PolyType(tparams, _) => PolyType(tparams, newinfo)
              case _ => newinfo
            }
          }
        }
      }
    }

    /**
     *  @param cdef ...
     *  @return     ...
     */
    def typedClassDef(cdef: ClassDef): Tree = {
//      attributes(cdef)
      val clazz = cdef.symbol
      val typedMods = removeAnnotations(cdef.mods)
      assert(clazz != NoSymbol)
      reenterTypeParams(cdef.tparams)
      val tparams1 = cdef.tparams mapConserve (typedTypeDef)
      val impl1 = newTyper(context.make(cdef.impl, clazz, scopeFor(cdef.impl, TypedDefScopeKind)))
        .typedTemplate(cdef.impl, parentTypes(cdef.impl))
      val impl2 = addSyntheticMethods(impl1, clazz, context)
      if ((clazz != ClassfileAnnotationClass) &&
          (clazz isNonBottomSubClass ClassfileAnnotationClass))
        unit.warning (cdef.pos,
          "implementation restriction: subclassing Classfile does not\n"+
          "make your annotation visible at runtime.  If that is what\n"+ 
          "you want, you must write the annotation class in Java.")
      treeCopy.ClassDef(cdef, typedMods, cdef.name, tparams1, impl2)
        .setType(NoType)
    }
 
    /**
     *  @param mdef ...
     *  @return     ...
     */
    def typedModuleDef(mdef: ModuleDef): Tree = {
      //Console.println("sourcefile of " + mdef.symbol + "=" + mdef.symbol.sourceFile)
//      attributes(mdef)
      // initialize all constructors of the linked class: the type completer (Namer.methodSig)
      // might add default getters to this object. example: "object T; class T(x: Int = 1)"
      val linkedClass = mdef.symbol.linkedClassOfModule
      if (linkedClass != NoSymbol)
        for (c <- linkedClass.info.decl(nme.CONSTRUCTOR).alternatives)
          c.initialize
      val clazz = mdef.symbol.moduleClass
      val typedMods = removeAnnotations(mdef.mods)
      assert(clazz != NoSymbol)
      val impl1 = newTyper(context.make(mdef.impl, clazz, scopeFor(mdef.impl, TypedDefScopeKind)))
        .typedTemplate(mdef.impl, parentTypes(mdef.impl))
      val impl2 = addSyntheticMethods(impl1, clazz, context)

      treeCopy.ModuleDef(mdef, typedMods, mdef.name, impl2) setType NoType
    }

    /**
     *  @param stat ...
     *  @return     ...
     */
    def addGetterSetter(stat: Tree): List[Tree] = stat match {
      case ValDef(mods, name, tpt, rhs) 
        if (mods.flags & (PRIVATE | LOCAL)) != (PRIVATE | LOCAL).toLong && !stat.symbol.isModuleVar =>
        val isDeferred = mods hasFlag DEFERRED
        val value = stat.symbol
        val getter = if (isDeferred) value else value.getter(value.owner)
        assert(getter != NoSymbol, stat)
        if (getter hasFlag OVERLOADED)
          error(getter.pos, getter+" is defined twice")

        // todo: potentially dangerous not to duplicate the trees and clone the symbols / types.
        getter.setAnnotations(value.annotations)

        if (value.hasFlag(LAZY)) List(stat)
        else {
          val vdef = treeCopy.ValDef(stat, mods | PRIVATE | LOCAL, nme.getterToLocal(name), tpt, rhs)
          val getterDef: DefDef = atPos(vdef.pos.focus) {
            if (isDeferred) {
              val r = DefDef(getter, EmptyTree)
              r.tpt.asInstanceOf[TypeTree].setOriginal(tpt) // keep type tree of original abstract field
              r
            } else {
              val rhs = gen.mkCheckInit(Select(This(value.owner), value))
              val r = typed {
                atPos(getter.pos.focus) {
                  DefDef(getter, rhs)
                }
              }.asInstanceOf[DefDef]
              r.tpt.setPos(tpt.pos.focus)
              r
            }
          }
          checkNoEscaping.privates(getter, getterDef.tpt)
          def setterDef(setter: Symbol): DefDef = {
            setter.setAnnotations(value.annotations)
            val result = typed {
              atPos(vdef.pos.focus) {
                DefDef(
                  setter,
                  if ((mods hasFlag DEFERRED) || (setter hasFlag OVERLOADED))
                    EmptyTree
                  else
                    Assign(Select(This(value.owner), value),
                           Ident(setter.paramss.head.head)))
              }
            }
            result.asInstanceOf[DefDef]
            // Martin: was 
            // treeCopy.DefDef(result, result.mods, result.name, result.tparams,
            //                result.vparamss, result.tpt, result.rhs)
            // but that's redundant, no?
          }

          val gs = new ListBuffer[DefDef]
          gs.append(getterDef)
          if (mods hasFlag MUTABLE) {
            val setter = getter.setter(value.owner)
            gs.append(setterDef(setter))
            if (!forMSIL && (value.hasAnnotation(BeanPropertyAttr) ||
                 value.hasAnnotation(BooleanBeanPropertyAttr))) {
              val beanSetterName = "set" + name(0).toString.toUpperCase +
                                   name.subName(1, name.length)
              val beanSetter = value.owner.info.decl(beanSetterName)
              gs.append(setterDef(beanSetter))
            }
          }
          if (mods hasFlag DEFERRED) gs.toList else vdef :: gs.toList
        }
      case DocDef(comment, defn) =>
        addGetterSetter(defn) map (stat => DocDef(comment, stat))

      case Annotated(annot, defn) =>
        addGetterSetter(defn) map (stat => Annotated(annot, stat))

      case _ =>
        List(stat)
    }

    protected def enterSyms(txt: Context, trees: List[Tree]) = {
      var txt0 = txt
      for (tree <- trees) txt0 = enterSym(txt0, tree)
    }

    protected def enterSym(txt: Context, tree: Tree): Context =
      if (txt eq context) namer.enterSym(tree)
      else newNamer(txt).enterSym(tree)

    /**
     *  @param templ    ...
     *  @param parents1 ...
     *    <li> <!-- 2 -->
     *      Check that inner classes do not inherit from Annotation
     *    </li>
     *  @return         ...
     */
    def typedTemplate(templ: Template, parents1: List[Tree]): Template = {
      val clazz = context.owner
      if (templ.symbol == NoSymbol)
        templ setSymbol newLocalDummy(clazz, templ.pos)
      val self1 = templ.self match {
        case vd @ ValDef(mods, name, tpt, EmptyTree) =>
          val tpt1 = checkNoEscaping.privates(clazz.thisSym, typedType(tpt))
          treeCopy.ValDef(vd, mods, name, tpt1, EmptyTree) setType NoType
      }
      if (self1.name != nme.WILDCARD) context.scope enter self1.symbol
      val selfType =
        if (clazz.isAnonymousClass && !phase.erasedTypes) 
          intersectionType(clazz.info.parents, clazz.owner)
        else clazz.typeOfThis
      // the following is necessary for templates generated later
      assert(clazz.info.decls != EmptyScope)
      enterSyms(context.outer.make(templ, clazz, clazz.info.decls), templ.body)
      validateParentClasses(parents1, selfType)
      if ((clazz isSubClass ClassfileAnnotationClass) && !clazz.owner.isPackageClass)
        unit.error(clazz.pos, "inner classes cannot be classfile annotations")
      if (!phase.erasedTypes && !clazz.info.resultType.isError) // @S: prevent crash for duplicated type members
        checkFinitary(clazz.info.resultType.asInstanceOf[ClassInfoType])
      val body = 
        if (phase.id <= currentRun.typerPhase.id && !reporter.hasErrors) 
          templ.body flatMap addGetterSetter
        else templ.body 
      val body1 = typedStats(body, templ.symbol)
      treeCopy.Template(templ, parents1, self1, body1) setType clazz.tpe
    }

    /** Remove definition annotations from modifiers (they have been saved
     *  into the symbol's ``annotations'' in the type completer / namer)
     */
    def removeAnnotations(mods: Modifiers): Modifiers =
      Modifiers(mods.flags, mods.privateWithin, Nil)

    /**
     *  @param vdef ...
     *  @return     ...
     */
    def typedValDef(vdef: ValDef): ValDef = {
//      attributes(vdef)
      val sym = vdef.symbol
      val typer1 = constrTyperIf(sym.hasFlag(PARAM) && sym.owner.isConstructor)
      val typedMods = removeAnnotations(vdef.mods)

      var tpt1 = checkNoEscaping.privates(sym, typer1.typedType(vdef.tpt))
      checkNonCyclic(vdef, tpt1)
      if (sym.hasAnnotation(definitions.VolatileAttr) && !sym.hasFlag(MUTABLE))
        error(vdef.pos, "values cannot be volatile")
      val rhs1 =
        if (vdef.rhs.isEmpty) {
          if (sym.isVariable && sym.owner.isTerm && phase.id <= currentRun.typerPhase.id)
            error(vdef.pos, "local variables must be initialized")
          vdef.rhs
        } else {
          val tpt2 = if (sym hasFlag DEFAULTPARAM) {
            // When typechecking default parameter, replace all type parameters in the expected type by Wildcarad.
            // This allows defining "def foo[T](a: T = 1)"
            val tparams =
              if (sym.owner.isConstructor) sym.owner.owner.info.typeParams
              else sym.owner.tpe.typeParams
            val subst = new SubstTypeMap(tparams, tparams map (_ => WildcardType)) {
              override def matches(sym: Symbol, sym1: Symbol) =
                if (sym.isSkolem) matches(sym.deSkolemize, sym1)
                else if (sym1.isSkolem) matches(sym, sym1.deSkolemize)
                else super[SubstTypeMap].matches(sym, sym1) 
            }
            // allow defaults on by-name parameters
            if (sym hasFlag BYNAMEPARAM)
              if (tpt1.tpe.typeArgs.isEmpty) WildcardType // during erasure tpt1 is Funciton0
              else subst(tpt1.tpe.typeArgs(0))
            else subst(tpt1.tpe)
          } else tpt1.tpe
          newTyper(typer1.context.make(vdef, sym)).transformedOrTyped(vdef.rhs, tpt2)
        }
      treeCopy.ValDef(vdef, typedMods, vdef.name, tpt1, checkDead(rhs1)) setType NoType
    }

    /** Enter all aliases of local parameter accessors.
     *
     *  @param clazz    ...
     *  @param vparamss ...
     *  @param rhs      ...
     */
    def computeParamAliases(clazz: Symbol, vparamss: List[List[ValDef]], rhs: Tree) {
      if (settings.debug.value) log("computing param aliases for "+clazz+":"+clazz.primaryConstructor.tpe+":"+rhs);//debug
      def decompose(call: Tree): (Tree, List[Tree]) = call match {
        case Apply(fn, args) =>
          val (superConstr, args1) = decompose(fn)
          val formals = fn.tpe.paramTypes
          val args2 = if (formals.isEmpty || !isRepeatedParamType(formals.last)) args
                      else args.take(formals.length - 1) ::: List(EmptyTree)
          if (args2.length != formals.length)
            assert(false, "mismatch " + clazz + " " + formals + " " + args2);//debug 
          (superConstr, args1 ::: args2)
        case Block(stats, expr) if !stats.isEmpty =>
          decompose(stats.last)
        case _ =>
          (call, List())
      }
      val (superConstr, superArgs) = decompose(rhs)
      assert(superConstr.symbol ne null)//debug
      
      // an object cannot be allowed to pass a reference to itself to a superconstructor
      // because of initialization issues; bug #473
      for { 
        arg <- superArgs
        val sym = arg.symbol
        if sym != null && sym.isModule && (sym.info.baseClasses contains clazz)
      } error(rhs.pos, "super constructor cannot be passed a self reference unless parameter is declared by-name")
      
      if (superConstr.symbol.isPrimaryConstructor) {
        val superClazz = superConstr.symbol.owner
        if (!superClazz.hasFlag(JAVA)) {
          val superParamAccessors = superClazz.constrParamAccessors
          if (superParamAccessors.length == superArgs.length) {
            List.map2(superParamAccessors, superArgs) { (superAcc, superArg) =>
              superArg match {
                case Ident(name) =>
                  if (vparamss.exists(_.exists(_.symbol == superArg.symbol))) {
                    var alias = superAcc.initialize.alias
                    if (alias == NoSymbol)
                      alias = superAcc.getter(superAcc.owner)
                    if (alias != NoSymbol &&
                        superClazz.info.nonPrivateMember(alias.name) != alias)
                      alias = NoSymbol
                    if (alias != NoSymbol) {
                      var ownAcc = clazz.info.decl(name).suchThat(_.hasFlag(PARAMACCESSOR))
                      if ((ownAcc hasFlag ACCESSOR) && !ownAcc.isDeferred)
                        ownAcc = ownAcc.accessed
                      if (!ownAcc.isVariable && !alias.accessed.isVariable) {
                        if (settings.debug.value)
                          log("" + ownAcc + " has alias "+alias + alias.locationString);//debug
                        ownAcc.asInstanceOf[TermSymbol].setAlias(alias)
                      }
                    }
                  }
                case _ =>
              }
              ()
            }
          }
        }
      }
    }

    private def checkStructuralCondition(refinement: Symbol, vparam: ValDef) {
      val tp = vparam.symbol.tpe
      if (tp.typeSymbol.isAbstractType && !(tp.typeSymbol.hasTransOwner(refinement)))
        error(vparam.tpt.pos,"Parameter type in structural refinement may not refer to abstract type defined outside that same refinement")
    }

    /**
     *  @param ddef ...
     *  @return     ...
     */
    def typedDefDef(ddef: DefDef): DefDef = {
      val meth = ddef.symbol
      reenterTypeParams(ddef.tparams)
      reenterValueParams(ddef.vparamss)
      val tparams1 = ddef.tparams mapConserve typedTypeDef
      val vparamss1 = ddef.vparamss mapConserve (_ mapConserve typedValDef)
      for (vparams1 <- vparamss1; if !vparams1.isEmpty; vparam1 <- vparams1.init) {
        if (isRepeatedParamType(vparam1.symbol.tpe))
          error(vparam1.pos, "*-parameter must come last")
      }
      var tpt1 = checkNoEscaping.privates(meth, typedType(ddef.tpt))           
      if (!settings.Xexperimental.value) {
        for (vparams <- vparamss1; vparam <- vparams) {
          checkNoEscaping.locals(context.scope, WildcardType, vparam.tpt); ()
        }
        checkNoEscaping.locals(context.scope, WildcardType, tpt1)
      }
      checkNonCyclic(ddef, tpt1)
      ddef.tpt.setType(tpt1.tpe)
      val typedMods = removeAnnotations(ddef.mods)
      var rhs1 = 
        if (ddef.name == nme.CONSTRUCTOR) {
          if (!meth.isPrimaryConstructor &&
              (!meth.owner.isClass ||
               meth.owner.isModuleClass ||
               meth.owner.isAnonymousClass ||
               meth.owner.isRefinementClass))
            error(ddef.pos, "constructor definition not allowed here")
          typed(ddef.rhs)
        } else {
          transformedOrTyped(ddef.rhs, tpt1.tpe)
        }
      if (meth.isPrimaryConstructor && meth.isClassConstructor && 
          phase.id <= currentRun.typerPhase.id && !reporter.hasErrors)
        computeParamAliases(meth.owner, vparamss1, rhs1)
      if (tpt1.tpe.typeSymbol != NothingClass && !context.returnsSeen) rhs1 = checkDead(rhs1)

      if (meth.owner.isRefinementClass && meth.allOverriddenSymbols.isEmpty)
        for (vparams <- ddef.vparamss; vparam <- vparams) 
          checkStructuralCondition(meth.owner, vparam)

      if (phase.id <= currentRun.typerPhase.id && meth.owner.isClass &&
          meth.paramss.exists(ps => ps.exists(_.hasFlag(DEFAULTPARAM)) && isRepeatedParamType(ps.last.tpe)))
        error(meth.pos, "a parameter section with a `*'-parameter is not allowed to have default arguments")

      treeCopy.DefDef(ddef, typedMods, ddef.name, tparams1, vparamss1, tpt1, rhs1) setType NoType
    }

    def typedTypeDef(tdef: TypeDef): TypeDef = {
      reenterTypeParams(tdef.tparams) // @M!
      val tparams1 = tdef.tparams mapConserve (typedTypeDef) // @M!
      val typedMods = removeAnnotations(tdef.mods)
      val rhs1 = checkNoEscaping.privates(tdef.symbol, typedType(tdef.rhs))
      checkNonCyclic(tdef.symbol)
      if (tdef.symbol.owner.isType) 
        rhs1.tpe match {
          case TypeBounds(lo1, hi1) =>
            if (!(lo1 <:< hi1))
              error(tdef.pos, "lower bound "+lo1+" does not conform to upper bound "+hi1)
          case _ =>
        }
      treeCopy.TypeDef(tdef, typedMods, tdef.name, tparams1, rhs1) setType NoType
    }

    private def enterLabelDef(stat: Tree) {
      stat match {
        case ldef @ LabelDef(_, _, _) =>
          if (ldef.symbol == NoSymbol)
            ldef.symbol = namer.enterInScope(
              context.owner.newLabel(ldef.pos, ldef.name) setInfo MethodType(List(), UnitClass.tpe))
        case _ =>
      }
    }

    def typedLabelDef(ldef: LabelDef): LabelDef = {
      val restpe = ldef.symbol.tpe.resultType
      val rhs1 = typed(ldef.rhs, restpe)
      ldef.params foreach (param => param.tpe = param.symbol.tpe)
      treeCopy.LabelDef(ldef, ldef.name, ldef.params, rhs1) setType restpe
    }

    protected def typedFunctionIDE(fun : Function, txt : Context) = {}
    
    /**
     *  @param block ...
     *  @param mode  ...
     *  @param pt    ...
     *  @return      ...
     */
    def typedBlock(block: Block, mode: Int, pt: Type): Block = {
      namer.enterSyms(block.stats)
      for (stat <- block.stats) {
        if (onlyPresentation && stat.isDef) {
          var e = context.scope.lookupEntry(stat.symbol.name)
          while ((e ne null) && (e.sym ne stat.symbol)) e = e.tail
          if (e eq null) context.scope.enter(stat.symbol)
        }
        enterLabelDef(stat)
      }
      val stats1 = typedStats(block.stats, context.owner)
      val expr1 = typed(block.expr, mode & ~(FUNmode | QUALmode), pt)
      val block1 = treeCopy.Block(block, stats1, expr1)
        .setType(if (treeInfo.isPureExpr(block)) expr1.tpe else expr1.tpe.deconst)
      //checkNoEscaping.locals(context.scope, pt, block1)
      block1
    }

    /**
     *  @param cdef   ...
     *  @param pattpe ...
     *  @param pt     ...
     *  @return       ...
     */
    def typedCase(cdef: CaseDef, pattpe: Type, pt: Type): CaseDef = {
      // verify no _* except in last position      
      for (Apply(_, xs) <- cdef.pat ; x <- xs dropRight 1 ; if treeInfo isStar x)
        error(x.pos, "_* may only come last")
        
      val pat1: Tree = typedPattern(cdef.pat, pattpe)
      val guard1: Tree = if (cdef.guard == EmptyTree) EmptyTree
                         else typed(cdef.guard, BooleanClass.tpe)
      var body1: Tree = typed(cdef.body, pt)
      if (!context.savedTypeBounds.isEmpty) {
        body1.tpe = context.restoreTypeBounds(body1.tpe)
        if (isFullyDefined(pt) && !(body1.tpe <:< pt)) {
          body1 =
            typed {
              atPos(body1.pos) {
                TypeApply(Select(body1, Any_asInstanceOf), List(TypeTree(pt))) // @M no need for pt.normalize here, is done in erasure
              }
            }
        }
      }
//    body1 = checkNoEscaping.locals(context.scope, pt, body1)
      treeCopy.CaseDef(cdef, pat1, guard1, body1) setType body1.tpe
    }

    def typedCases(tree: Tree, cases: List[CaseDef], pattp0: Type, pt: Type): List[CaseDef] = {
      var pattp = pattp0
      cases mapConserve (cdef => 
        newTyper(context.makeNewScope(cdef, context.owner)(TypedCasesScopeKind))
          .typedCase(cdef, pattp, pt))
/* not yet!
        cdef.pat match {
          case Literal(Constant(null)) => 
            if (!(pattp <:< NonNullClass.tpe))
              pattp = intersectionType(List(pattp, NonNullClass.tpe), context.owner)
          case _ =>
        }
        result
*/
    }

    /**
     *  @param fun  ...
     *  @param mode ...
     *  @param pt   ...
     *  @return     ...
     */
    def typedFunction(fun: Function, mode: Int, pt: Type): Tree = {
      val codeExpected = !forMSIL && (pt.typeSymbol isNonBottomSubClass CodeClass)
      
      if (fun.vparams.length > definitions.MaxFunctionArity)
        return errorTree(fun, "implementation restricts functions to " + definitions.MaxFunctionArity + " parameters")

      def decompose(pt: Type): (Symbol, List[Type], Type) =
        if ((isFunctionType(pt)
             || 
             pt.typeSymbol == PartialFunctionClass && 
             fun.vparams.length == 1 && fun.body.isInstanceOf[Match]) 
             && // see bug901 for a reason why next conditions are neeed
            (pt.normalize.typeArgs.length - 1 == fun.vparams.length 
             || 
             fun.vparams.exists(_.tpt.isEmpty)))
          (pt.typeSymbol, pt.normalize.typeArgs.init, pt.normalize.typeArgs.last)
        else
          (FunctionClass(fun.vparams.length), fun.vparams map (x => NoType), WildcardType)

      val (clazz, argpts, respt) = decompose(if (codeExpected) pt.normalize.typeArgs.head else pt)

      if (fun.vparams.length != argpts.length)
        errorTree(fun, "wrong number of parameters; expected = " + argpts.length)
      else {
        val vparamSyms = List.map2(fun.vparams, argpts) { (vparam, argpt) =>
          if (vparam.tpt.isEmpty) {
            vparam.tpt.tpe = 
              if (isFullyDefined(argpt)) argpt
              else {
                fun match {
                  case etaExpansion(vparams, fn, args) if !codeExpected =>
                    silent(_.typed(fn, funMode(mode), pt)) match {
                      case fn1: Tree if context.undetparams.isEmpty =>
                        // if context,undetparams is not empty, the function was polymorphic, 
                        // so we need the missing arguments to infer its type. See #871
                        //println("typing eta "+fun+":"+fn1.tpe+"/"+context.undetparams)
                        val ftpe = normalize(fn1.tpe) baseType FunctionClass(fun.vparams.length)
                        if (isFunctionType(ftpe) && isFullyDefined(ftpe))
                          return typedFunction(fun, mode, ftpe)
                      case _ =>
                    }
                  case _ =>
                }
                error(
                  vparam.pos, 
                  "missing parameter type"+
                  (if (vparam.mods.hasFlag(SYNTHETIC)) " for expanded function "+fun
                   else ""))
                ErrorType 
              }
            if (!vparam.tpt.pos.isDefined) vparam.tpt setPos vparam.pos.focus
          }
          enterSym(context, vparam)
          if (context.retyping) context.scope enter vparam.symbol
          vparam.symbol
        }

        val vparams = fun.vparams mapConserve (typedValDef)
//        for (vparam <- vparams) {
//          checkNoEscaping.locals(context.scope, WildcardType, vparam.tpt); ()
//        }
        var body = typed(fun.body, respt)
        val formals = vparamSyms map (_.tpe)
        val restpe = packedType(body, fun.symbol).deconst
        val funtpe = typeRef(clazz.tpe.prefix, clazz, formals ::: List(restpe))
//        body = checkNoEscaping.locals(context.scope, restpe, body)
        val fun1 = treeCopy.Function(fun, vparams, body).setType(funtpe)
        if (codeExpected) {
          val liftPoint = Apply(Select(Ident(CodeModule), nme.lift_), List(fun1))
          typed(atPos(fun.pos)(liftPoint))
        } else fun1
      }
    }

    def typedRefinement(stats: List[Tree]) {
      namer.enterSyms(stats)
      // need to delay rest of typedRefinement to avoid cyclic reference errors
      unit.toCheck += { () =>
        val stats1 = typedStats(stats, NoSymbol)
        for (stat <- stats1 if stat.isDef) {
          val member = stat.symbol
          if (!(context.owner.info.baseClasses.tail forall
                (bc => member.matchingSymbol(bc, context.owner.thisType) == NoSymbol))) {
                  member setFlag OVERRIDE
                }
        }
      }
    }

    def typedImport(imp : Import) : Import = imp

    def typedStats(stats: List[Tree], exprOwner: Symbol): List[Tree] = {
      val inBlock = exprOwner == context.owner
      def includesTargetPos(tree: Tree) = 
        tree.pos.isRange && context.unit != null && (tree.pos includes context.unit.targetPos)
      val localTarget = stats exists includesTargetPos
      def typedStat(stat: Tree): Tree = {
        if (context.owner.isRefinementClass && !treeInfo.isDeclaration(stat))
          errorTree(stat, "only declarations allowed here")
        else 
          stat match {
            case imp @ Import(_, _) =>
              val imp0 = typedImport(imp)
              if (imp0 ne null) {
                context = context.makeNewImport(imp0)
                imp0.symbol.initialize
              }
              EmptyTree
            case _ =>
              if (localTarget && !includesTargetPos(stat)) {
                stat
              } else {
                val localTyper = if (inBlock || (stat.isDef && !stat.isInstanceOf[LabelDef])) this
                                 else newTyper(context.make(stat, exprOwner))
                val result = checkDead(localTyper.typed(stat))
                if (treeInfo.isSelfOrSuperConstrCall(result)) {
                  context.inConstructorSuffix = true
                  if (treeInfo.isSelfConstrCall(result) && result.symbol.pos.offset.getOrElse(0) >= exprOwner.enclMethod.pos.offset.getOrElse(0))
                    error(stat.pos, "called constructor's definition must precede calling constructor's definition")
                }
                result
              }
          }
      }

      def accesses(accessor: Symbol, accessed: Symbol) = 
        (accessed hasFlag LOCAL) && (accessed hasFlag PARAMACCESSOR) ||
        (accessor hasFlag ACCESSOR) &&
        !(accessed hasFlag ACCESSOR) && accessed.isPrivateLocal

      def checkNoDoubleDefsAndAddSynthetics(stats: List[Tree]): List[Tree] = {
        val scope = if (inBlock) context.scope else context.owner.info.decls;
        val newStats = new ListBuffer[Tree]
        var needsCheck = true
        var moreToAdd = true
        while (moreToAdd) {
          val initSize = scope.size
          var e = scope.elems;
          while ((e ne null) && e.owner == scope) {

            // check no double def
            if (needsCheck) {
              var e1 = scope.lookupNextEntry(e);
              while ((e1 ne null) && e1.owner == scope) {
                if (!accesses(e.sym, e1.sym) && !accesses(e1.sym, e.sym) && 
                    (e.sym.isType || inBlock || (e.sym.tpe matches e1.sym.tpe)))
                  // default getters are defined twice when multiple overloads have defaults. an
                  // error for this is issued in RefChecks.checkDefaultsInOverloaded
                  if (!e.sym.isErroneous && !e1.sym.isErroneous && !e.sym.hasFlag(DEFAULTPARAM))
                    error(e.sym.pos, e1.sym+" is defined twice"+
                          {if(!settings.debug.value) "" else " in "+unit.toString})
                e1 = scope.lookupNextEntry(e1);
              }
            }

          // add synthetics
          context.unit.synthetics get e.sym match {
            case Some(tree) =>
              newStats += typedStat(tree) // might add even more synthetics to the scope
              context.unit.synthetics -= e.sym
            case _ =>
          }

          e = e.next
        }
        needsCheck = false
        // the type completer of a synthetic might add more synthetics. example: if the
        // factory method of a case class (i.e. the constructor) has a default.
        moreToAdd = initSize != scope.size
        }
        if (newStats.isEmpty) stats
        else stats ::: newStats.toList
      }
      val result = stats mapConserve (typedStat)
      if (phase.erasedTypes) result
      else checkNoDoubleDefsAndAddSynthetics(result)
    }

    def typedArg(arg: Tree, mode: Int, newmode: Int, pt: Type): Tree =
      checkDead(constrTyperIf((mode & SCCmode) != 0).typed(arg, mode & stickyModes | newmode, pt))

    def typedArgs(args: List[Tree], mode: Int) =
      args mapConserve (arg => typedArg(arg, mode, 0, WildcardType))

    def typedArgs(args: List[Tree], mode: Int, originalFormals: List[Type], adaptedFormals: List[Type]) = {
      if (isVarArgs(originalFormals)) {
        val nonVarCount = originalFormals.length - 1
        val prefix =
          List.map2(args take nonVarCount, adaptedFormals take nonVarCount) ((arg, formal) =>
            typedArg(arg, mode, 0, formal))
        val suffix =
          List.map2(args drop nonVarCount, adaptedFormals drop nonVarCount) ((arg, formal) =>
            typedArg(arg, mode, REGPATmode, formal))
        prefix ::: suffix
      } else {
        List.map2(args, adaptedFormals)((arg, formal) => typedArg(arg, mode, 0, formal))
      }
    }

    /** Does function need to be instantiated, because a missing parameter
     *  in an argument closure overlaps with an uninstantiated formal?
     */
    def needsInstantiation(tparams: List[Symbol], formals: List[Type], args: List[Tree]) = {
      def isLowerBounded(tparam: Symbol) = {
        val losym = tparam.info.bounds.lo.typeSymbol
        losym != NothingClass && losym != NullClass
      }
      List.exists2(formals, args) { 
        case (formal, Function(vparams, _)) =>
          (vparams exists (_.tpt.isEmpty)) &&
          vparams.length <= MaxFunctionArity &&
          (formal baseType FunctionClass(vparams.length) match {
            case TypeRef(_, _, formalargs) =>
              List.exists2(formalargs, vparams) ((formalarg, vparam) =>
                vparam.tpt.isEmpty && (tparams exists (formalarg contains))) &&
              (tparams forall isLowerBounded)
            case _ =>
              false
          })
        case _ => 
          false
      }
    }

    /** Is `tree' a block created by a named application?
     */
    def isNamedApplyBlock(tree: Tree) =
      context.namedApplyBlockInfo match {
        case Some((block, _)) => block == tree
        case None => false
      }


    /**
     *  @param tree ...
     *  @param fun0 ...
     *  @param args ...
     *  @param mode ...
     *  @param pt   ...
     *  @return     ...
     */
    def doTypedApply(tree: Tree, fun0: Tree, args: List[Tree], mode: Int, pt: Type): Tree = {
      var fun = fun0
      if (fun.hasSymbol && (fun.symbol hasFlag OVERLOADED)) {
        // remove alternatives with wrong number of parameters without looking at types.
        // less expensive than including them in inferMethodAlternatvie (see below).
        def shapeType(arg: Tree): Type = arg match {
          case Function(vparams, body) =>
            functionType(vparams map (vparam => AnyClass.tpe), shapeType(body))
          case AssignOrNamedArg(Ident(name), rhs) =>
            NamedType(name, shapeType(rhs))
          case _ =>
            NothingClass.tpe
        }
        val argtypes = args map shapeType
        val pre = fun.symbol.tpe.prefix

        var sym = fun.symbol filter { alt =>
          isApplicableSafe(context.undetparams, followApply(pre.memberType(alt)), argtypes, pt)
        }
        if (sym hasFlag OVERLOADED) {
          val sym1 = sym filter (alt => {
            // eliminate functions that would result from tupling transforms
            // keeps alternatives with repeated params
            hasExactlyNumParams(followApply(alt.tpe), argtypes.length) ||
            // also keep alts which define at least one default
            alt.tpe.paramss.exists(_.exists(_.hasFlag(DEFAULTPARAM)))
          })
          if (sym1 != NoSymbol) sym = sym1
        }
        if (sym != NoSymbol)
          fun = adapt(fun setSymbol sym setType pre.memberType(sym), funMode(mode), WildcardType)
      }
      fun.tpe match {
        case OverloadedType(pre, alts) =>
          val undetparams = context.extractUndetparams()

          val argtpes = new ListBuffer[Type]
          val amode = argMode(fun, mode)
          val args1 = args map {
            case arg @ AssignOrNamedArg(Ident(name), rhs) =>
              // named args: only type the righthand sides ("unknown identifier" errors otherwise)
              val rhs1 = typedArg(rhs, amode, 0, WildcardType)
              argtpes += NamedType(name, rhs1.tpe.deconst)
              // the assign is untyped; that's ok because we call doTypedApply
              atPos(arg.pos) { new AssignOrNamedArg(arg.lhs , rhs1) }
            case arg =>
              val arg1 = typedArg(arg, amode, 0, WildcardType)
              argtpes += arg1.tpe.deconst
              arg1
          }
          context.undetparams = undetparams
          inferMethodAlternative(fun, undetparams, argtpes.toList, pt)
          doTypedApply(tree, adapt(fun, funMode(mode), WildcardType), args1, mode, pt)

        case mt @ MethodType(params, _) =>
          // repeat vararg as often as needed, remove by-name
          val formals = formalTypes(mt.paramTypes, args.length)

          /** Try packing all arguments into a Tuple and apply `fun'
           *  to that. This is the last thing which is tried (after
           *  default arguments)
           */
          def tryTupleApply: Option[Tree] = {
            // if 1 formal, 1 arg (a tuple), otherwise unmodified args
            val tupleArgs = actualArgs(tree.pos.makeTransparent, args, formals.length)

            if (tupleArgs.length != args.length) {
              // expected one argument, but got 0 or >1 ==>  try applying to tuple
              // the inner "doTypedApply" does "extractUndetparams" => restore when it fails
              val savedUndetparams = context.undetparams
              silent(_.doTypedApply(tree, fun, tupleArgs, mode, pt)) match {
                case t: Tree => Some(t)
                case ex =>
                  context.undetparams = savedUndetparams
                  None
              }
            } else None
          }

          /** Treats an application which uses named or default arguments.
           *  Also works if names + a vararg used: when names are used, the vararg
           *  parameter has to be specified exactly once. Note that combining varargs
           *  and defaults is ruled out by typedDefDef.
           */
          def tryNamesDefaults: Tree = {
            if (mt.isErroneous) setError(tree)
            else if ((mode & PATTERNmode) != 0)
              // #2064
              errorTree(tree, "wrong number of arguments for "+ treeSymTypeMsg(fun))
            else if (args.length > formals.length) {
              tryTupleApply.getOrElse {
                errorTree(tree, "too many arguments for "+treeSymTypeMsg(fun))
              }
            } else if (args.length == formals.length) {
              // we don't need defaults. names were used, so this application is transformed
              // into a block (@see transformNamedApplication in NamesDefaults)
              val (namelessArgs, argPos) = removeNames(Typer.this)(args, params)
              if (namelessArgs exists (_.isErroneous)) {
                setError(tree)
              } else if (!isIdentity(argPos) && (formals.length != params.length))
                // !isIdentity indicates that named arguments are used to re-order arguments
                errorTree(tree, "when using named arguments, the vararg parameter "+
                                "has to be specified exactly once")
              else if (isIdentity(argPos) && !isNamedApplyBlock(fun)) {
                // if there's no re-ordering, and fun is not transformed, no need to transform
                // more than an optimization, e.g. important in "synchronized { x = update-x }"
                doTypedApply(tree, fun, namelessArgs, mode, pt)
              } else {
                transformNamedApplication(Typer.this, mode, pt)(
                                          treeCopy.Apply(tree, fun, namelessArgs), argPos)
              }
            } else {
              // defaults are needed. they are added to the argument list in named style as
              // calls to the default getters. Example:
              //  foo[Int](a)()  ==>  foo[Int](a)(b = foo$qual.foo$default$2[Int](a))
              val fun1 = transformNamedApplication(Typer.this, mode, pt)(fun, x => x)
              if (fun1.isErroneous) setError(tree)
              else {
                assert(isNamedApplyBlock(fun1), fun1)
                val NamedApplyInfo(qual, targs, previousArgss, _) = context.namedApplyBlockInfo.get._2
                val blockIsEmpty = fun1 match {
                  case Block(Nil, _) =>
                    // if the block does not have any ValDef we can remove it. Note that the call to
                    // "transformNamedApplication" is always needed in order to obtain targs/previousArgss
                    context.namedApplyBlockInfo = None
                    true
                  case _ => false
                }
                val (allArgs, missing) = addDefaults(args, qual, targs, previousArgss, params, fun.pos.focus)
                if (allArgs.length == formals.length) {
                  // useful when a default doesn't match parameter type, e.g. def f[T](x:T="a"); f[Int]()
                  context.diagnostic = "Error occured in an application involving default arguments." :: context.diagnostic
                  doTypedApply(tree, if (blockIsEmpty) fun else fun1, allArgs, mode, pt)
                } else {
                  tryTupleApply.getOrElse {
                    val suffix =
                      if (missing.isEmpty) ""
                      else {
                        val missingStr = missing.take(3).map(_.name).mkString(", ") + (if (missing.length > 3) ", ..." else ".")
                        val sOpt = if (missing.length > 1) "s" else ""
                        ".\nUnspecified value parameter"+ sOpt +" "+ missingStr
                      }
                    errorTree(tree, "not enough arguments for "+treeSymTypeMsg(fun) + suffix)
                  }
                }
              }
            }
          }

          if (formals.length != args.length || // wrong nb of arguments
              args.exists(isNamed(_)) ||       // uses a named argument
              isNamedApplyBlock(fun)) {        // fun was transformed to a named apply block =>
                                               // integrate this application into the block
            tryNamesDefaults
          } else {
            val tparams = context.extractUndetparams()
            if (tparams.isEmpty) { // all type params are defined
              val args1 = typedArgs(args, argMode(fun, mode), mt.paramTypes, formals)
              val restpe = mt.resultType(args1 map (_.tpe)) // instantiate dependent method types
              def ifPatternSkipFormals(tp: Type) = tp match {
                case MethodType(_, rtp) if ((mode & PATTERNmode) != 0) => rtp
                case _ => tp
              }

              // Replace the Delegate-Chainer methods += and -= with corresponding
              // + and - calls, which are translated in the code generator into
              // Combine and Remove
              if (forMSIL) {
                fun match {
                  case Select(qual, name) =>
                   if (isSubType(qual.tpe, DelegateClass.tpe)
                      && (name == encode("+=") || name == encode("-=")))
                     {
                       val n = if (name == encode("+=")) nme.PLUS else nme.MINUS
                       val f = Select(qual, n)
                       // the compiler thinks, the PLUS method takes only one argument,
                       // but he thinks it's an instance method -> still two ref's on the stack
                       //  -> translated by backend
                       val rhs = treeCopy.Apply(tree, f, args)
                       return typed(Assign(qual, rhs))
                     }
                  case _ => ()
                }
              }

              if (fun.symbol == List_apply && args.isEmpty) {
                atPos(tree.pos) { gen.mkNil setType restpe }
              } else {
                constfold(treeCopy.Apply(tree, fun, args1).setType(ifPatternSkipFormals(restpe)))
              }
              /* Would like to do the following instead, but curiously this fails; todo: investigate
              if (fun.symbol.name == nme.apply && fun.symbol.owner == ListClass && args.isEmpty) {
                atPos(tree.pos) { gen.mkNil setType restpe }
              } else {
                constfold(treeCopy.Apply(tree, fun, args1).setType(ifPatternSkipFormals(restpe)))
              }
              */

            } else if (needsInstantiation(tparams, formals, args)) {
              //println("needs inst "+fun+" "+tparams+"/"+(tparams map (_.info)))
              inferExprInstance(fun, tparams, WildcardType, true)
              doTypedApply(tree, fun, args, mode, pt)
            } else {
              assert((mode & PATTERNmode) == 0); // this case cannot arise for patterns
              val lenientTargs = protoTypeArgs(tparams, formals, mt.resultApprox, pt)
              val strictTargs = List.map2(lenientTargs, tparams)((targ, tparam) =>
                if (targ == WildcardType) tparam.tpe else targ)
              def typedArgToPoly(arg: Tree, formal: Type): Tree = {
                val lenientPt = formal.instantiateTypeParams(tparams, lenientTargs)
                val arg1 = typedArg(arg, argMode(fun, mode), POLYmode, lenientPt)
                val argtparams = context.extractUndetparams()
                if (!argtparams.isEmpty) {
                  val strictPt = formal.instantiateTypeParams(tparams, strictTargs)
                  inferArgumentInstance(arg1, argtparams, strictPt, lenientPt)
                }
                arg1
              }
              val args1 = List.map2(args, formals)(typedArgToPoly)
              if (args1 exists (_.tpe.isError)) setError(tree)
              else {
                if (settings.debug.value) log("infer method inst "+fun+", tparams = "+tparams+", args = "+args1.map(_.tpe)+", pt = "+pt+", lobounds = "+tparams.map(_.tpe.bounds.lo)+", parambounds = "+tparams.map(_.info));//debug
                // define the undetparams which have been fixed by this param list, replace the corresponding symbols in "fun"
                // returns those undetparams which have not been instantiated.
                val undetparams = inferMethodInstance(fun, tparams, args1, pt)
                val result = doTypedApply(tree, fun, args1, mode, pt)
                context.undetparams = undetparams
                result
              }
            }
          }

        case SingleType(_, _) =>
          doTypedApply(tree, fun setType fun.tpe.widen, args, mode, pt)
        
        case ErrorType =>
          setError(treeCopy.Apply(tree, fun, args))
        /* --- begin unapply  --- */

        case otpe if (mode & PATTERNmode) != 0 && unapplyMember(otpe).exists =>
          val unapp = unapplyMember(otpe)
          assert(unapp.exists, tree)
          val unappType = otpe.memberType(unapp)
          val argDummyType = pt // was unappArg
         // @S: do we need to memoize this?
          val argDummy =  context.owner.newValue(fun.pos, nme.SELECTOR_DUMMY)
            .setFlag(SYNTHETIC)
            .setInfo(argDummyType)
          if (args.length > MaxTupleArity)
            error(fun.pos, "too many arguments for unapply pattern, maximum = "+MaxTupleArity)
          val arg = Ident(argDummy) setType argDummyType
          val oldArgType = arg.tpe
          if (!isApplicableSafe(List(), unappType, List(arg.tpe), WildcardType)) {
            //Console.println("UNAPP: need to typetest, arg.tpe = "+arg.tpe+", unappType = "+unappType)
            def freshArgType(tp: Type): (Type, List[Symbol]) = tp match {
              case MethodType(params, _) => 
                (params(0).tpe, List())
              case PolyType(tparams, restype) => 
                val tparams1 = cloneSymbols(tparams)
                (freshArgType(restype)._1.substSym(tparams, tparams1), tparams1)
              case OverloadedType(_, _) =>
                error(fun.pos, "cannot resolve overloaded unapply")
                (ErrorType, List())
            }
            val (unappFormal, freeVars) = freshArgType(unappType)
            val context1 = context.makeNewScope(context.tree, context.owner)(FreshArgScopeKind)
            freeVars foreach(sym => context1.scope.enter(sym))
            val typer1 = newTyper(context1)
            arg.tpe = typer1.infer.inferTypedPattern(tree.pos, unappFormal, arg.tpe)
            //todo: replace arg with arg.asInstanceOf[inferTypedPattern(unappFormal, arg.tpe)] instead.
            argDummy.setInfo(arg.tpe) // bq: this line fixed #1281. w.r.t. comment ^^^, maybe good enough?
          }
/*
          val funPrefix = fun.tpe.prefix match {
            case tt @ ThisType(sym) => 
              //Console.println(" sym="+sym+" "+" .isPackageClass="+sym.isPackageClass+" .isModuleClass="+sym.isModuleClass);
              //Console.println(" funsymown="+fun.symbol.owner+" .isClass+"+fun.symbol.owner.isClass);
              //Console.println(" contains?"+sym.tpe.decls.lookup(fun.symbol.name));
              if(sym != fun.symbol.owner && (sym.isPackageClass||sym.isModuleClass) /*(1)*/ ) { // (1) see 'files/pos/unapplyVal.scala'
                if(fun.symbol.owner.isClass) {
                  mkThisType(fun.symbol.owner)
                } else {
                //Console.println("2 ThisType("+fun.symbol.owner+")")
                  NoPrefix                                                 // see 'files/run/unapplyComplex.scala'
                }
              } else tt
            case st @ SingleType(pre, sym) => st
              st
            case xx                        => xx // cannot happen?
          }
          val fun1untyped = fun
            Apply(
              Select(
                gen.mkAttributedRef(funPrefix, fun.symbol) setType null, 
                // setType null is necessary so that ref will be stabilized; see bug 881
                unapp), 
              List(arg))
          }
*/
          val fun1untyped = atPos(fun.pos) { 
            Apply(
              Select(
                fun setType null, // setType null is necessary so that ref will be stabilized; see bug 881
                unapp),
              List(arg))
          }

          val fun1 = typed(fun1untyped)
          if (fun1.tpe.isErroneous) setError(tree)
          else {
            val formals0 = unapplyTypeList(fun1.symbol, fun1.tpe)
            val formals1 = formalTypes(formals0, args.length)
            if (formals1.length == args.length) {
              val args1 = typedArgs(args, mode, formals0, formals1)
              if (!isFullyDefined(pt)) assert(false, tree+" ==> "+UnApply(fun1, args1)+", pt = "+pt)
              // <pending-change>
              //   this would be a better choice (from #1196), but fails due to (broken?) refinements
              val itype =  glb(List(pt, arg.tpe))
              // </pending-change>
              // restore old type (arg is a dummy tree, just needs to pass typechecking)
              arg.tpe = oldArgType
              UnApply(fun1, args1) setPos tree.pos setType itype //pt
              //
              // if you use the better itype, then the following happens.
              // the required type looks wrong...
              // 
              ///files/pos/bug0646.scala                                [FAILED]
              //
              //failed with type mismatch;
              // found   : scala.xml.NodeSeq{ ... }
              // required: scala.xml.NodeSeq{ ... } with scala.xml.NodeSeq{ ... } with scala.xml.Node on: temp3._data().==("Blabla").&&({
              //  exit(temp0);
              //  true
              //})
            } else {
              errorTree(tree, "wrong number of arguments for "+treeSymTypeMsg(fun))
            }
          }
          
/* --- end unapply  --- */
        case _ =>
          errorTree(tree, fun+" of type "+fun.tpe+" does not take parameters")
      }
    }

    /**
     * Convert an annotation constructor call into an AnnotationInfo.
     *
     * @param annClass the expected annotation class
     */
    def typedAnnotation(ann: Tree, mode: Int = EXPRmode, selfsym: Symbol = NoSymbol, annClass: Symbol = AnnotationClass, requireJava: Boolean = false): AnnotationInfo = {
      lazy val annotationError = AnnotationInfo(ErrorType, Nil, Nil)
      var hasError: Boolean = false
      def error(pos: Position, msg: String) = {
        context.error(pos, msg)
        hasError = true
        annotationError
      }
      def needConst(tr: Tree): None.type = {
        error(tr.pos, "annotation argument needs to be a constant; found: "+tr)
        None
      }

      /** Converts an untyped tree to a ClassfileAnnotArg. If the conversion fails,
       *  an error message is reporded and None is returned.
       */
      def tree2ConstArg(tree: Tree, pt: Type): Option[ClassfileAnnotArg] = tree match {
        case ann @ Apply(Select(New(tpt), nme.CONSTRUCTOR), args) =>
          val annInfo = typedAnnotation(ann, mode, NoSymbol, pt.typeSymbol, true)
          if (annInfo.atp.isErroneous) {
            // recursive typedAnnotation call already printed an error, so don't call "error"
            hasError = true
            None
          } else Some(NestedAnnotArg(annInfo))

        // use of: object Array.apply[A <: AnyRef](args: A*): Array[A] = ...
        // and object Array.apply(args: Int*): Array[Int] = ... (and similar)
        case Apply(fun, members) =>
          val typedFun = typed(fun, funMode(mode), WildcardType)
          if (typedFun.symbol.owner == ArrayModule.moduleClass &&
              typedFun.symbol.name == nme.apply &&
              pt.typeSymbol == ArrayClass &&
              !pt.typeArgs.isEmpty)
            trees2ConstArg(members, pt.typeArgs.head)
          else
            needConst(tree)

        case Typed(t, _) => tree2ConstArg(t, pt)

        case tree => typed(tree, EXPRmode, pt) match {
          // null cannot be used as constant value for classfile annotations
          case l @ Literal(c) if !(l.isErroneous || c.value == null) =>
            Some(LiteralAnnotArg(c))
          case _ =>
            needConst(tree)
        }
      }
      def trees2ConstArg(trees: List[Tree], pt: Type): Option[ArrayAnnotArg] = {
        val args = trees.map(tree2ConstArg(_, pt))
        if (args.exists(_.isEmpty)) None
        else Some(ArrayAnnotArg(args.map(_.get).toArray))
      }

      // begin typedAnnotation
      val (fun, argss) = {
        def extract(fun: Tree, outerArgss: List[List[Tree]]):
          (Tree, List[List[Tree]]) = fun match {
            case Apply(f, args) =>
              extract(f, args :: outerArgss)
            case Select(New(tpt), nme.CONSTRUCTOR) =>
              (fun, outerArgss)
            case _ =>
              error(fun.pos, "unexpected tree in annotationn: "+ fun)
              (setError(fun), outerArgss)
          }
        extract(ann, List())
      }

      if (fun.isErroneous) annotationError
      else {
        val typedFun @ Select(New(tpt), _) = typed(fun, funMode(mode), WildcardType)
        val annType = tpt.tpe

        if (typedFun.isErroneous) annotationError
        else if (annType.typeSymbol isNonBottomSubClass ClassfileAnnotationClass) {
          // annotation to be saved as java classfile annotation
          val isJava = typedFun.symbol.owner.hasFlag(JAVA)
          if (!annType.typeSymbol.isNonBottomSubClass(annClass)) {
            error(tpt.pos, "expected annotation of type "+ annClass.tpe +", found "+ annType)
          } else if (argss.length > 1) {
            error(ann.pos, "multiple argument lists on classfile annotation")
          } else {
            val args =
              if (argss.head.length == 1 && !isNamed(argss.head.head))
                List(new AssignOrNamedArg(Ident(nme.value), argss.head.head))
              else argss.head
            val annScope = annType.decls
                .filter(sym => sym.isMethod && !sym.isConstructor && sym.hasFlag(JAVA))
            val names = new collection.mutable.HashSet[Symbol]
            names ++= (if (isJava) annScope.iterator
                       else typedFun.tpe.params.iterator)
            val nvPairs = args map {
              case arg @ AssignOrNamedArg(Ident(name), rhs) =>
                val sym = if (isJava) annScope.lookupWithContext(name)(context.owner)
                          else typedFun.tpe.params.find(p => p.name == name).getOrElse(NoSymbol)
                if (sym == NoSymbol) {
                  error(arg.pos, "unknown annotation argument name: " + name)
                  (nme.ERROR, None)
                } else if (!names.contains(sym)) {
                  error(arg.pos, "duplicate value for anontation argument " + name)
                  (nme.ERROR, None)
                } else {
                  names -= sym
                  val annArg = tree2ConstArg(rhs, sym.tpe.resultType)
                  (sym.name, annArg)
                }
              case arg =>
                error(arg.pos, "classfile annotation arguments have to be supplied as named arguments")
                (nme.ERROR, None)
            }

            for (name <- names) {
              if (!name.annotations.contains(AnnotationInfo(AnnotationDefaultAttr.tpe, List(), List())) &&
                  !name.hasFlag(DEFAULTPARAM))
                error(ann.pos, "annotation " + annType.typeSymbol.fullNameString + " is missing argument " + name.name)
            }

            if (hasError) annotationError
            else AnnotationInfo(annType, List(), nvPairs map {p => (p._1, p._2.get)})
          }
        } else if (requireJava) {
          error(ann.pos, "nested classfile annotations must be defined in java; found: "+ annType)
        } else {
          val typedAnn = if (selfsym == NoSymbol) {
            typed(ann, mode, annClass.tpe)
          } else {
            // Since a selfsym is supplied, the annotation should have
            // an extra "self" identifier in scope for type checking.
            // This is implemented by wrapping the rhs
            // in a function like "self => rhs" during type checking,
            // and then stripping the "self =>" and substituting
            // in the supplied selfsym.
            val funcparm = ValDef(NoMods, nme.self, TypeTree(selfsym.info), EmptyTree)
            val func = Function(List(funcparm), ann.duplicate)
                                         // The .duplicate of annot.constr
                                         // deals with problems that
                                         // accur if this annotation is
                                         // later typed again, which
                                         // the compiler sometimes does.
                                         // The problem is that "self"
                                         // ident's within annot.constr
                                         // will retain the old symbol
                                         // from the previous typing.
            val fun1clazz = FunctionClass(1)
            val funcType = typeRef(fun1clazz.tpe.prefix, 
                                   fun1clazz, 
                                   List(selfsym.info, annClass.tpe))

            typed(func, mode, funcType) match {
              case t @ Function(List(arg), rhs) => 
                val subs =
                  new TreeSymSubstituter(List(arg.symbol),List(selfsym))
                subs(rhs)
            }
          }

          def annInfo(t: Tree): AnnotationInfo = t match {
            case Apply(Select(New(tpt), nme.CONSTRUCTOR), args) =>
              AnnotationInfo(annType, args, List())

            case Block(stats, expr) =>
              context.warning(t.pos, "Usage of named or default arguments transformed this annotation\n"+
                                "constructor call into a block. The corresponding AnnotationInfo\n"+
                                "will contain references to local values and default getters instead\n"+
                                "of the actual argument trees")
              annInfo(expr)

            case Apply(fun, args) =>
              context.warning(t.pos, "Implementation limitation: multiple argument lists on annotations are\n"+
                                     "currently not supported; ignoring arguments "+ args)
              annInfo(fun)

            case _ =>
              error(t.pos, "unexpected tree after typing annotation: "+ typedAnn)
          }

          if (annType.typeSymbol == DeprecatedAttr &&
              (argss.length == 0 || argss.head.length == 0))
            unit.deprecationWarning(ann.pos,
              "the `deprecated' annotation now takes a (message: String) as parameter\n"+
              "indicating the reason for deprecation. That message is printed to the console and included in scaladoc.")

          if ((typedAnn.tpe == null) || typedAnn.tpe.isErroneous) annotationError
          else annInfo(typedAnn)
        }
      }
    }

    def isRawParameter(sym: Symbol) = // is it a type parameter leaked by a raw type?
      sym.isTypeParameter && sym.owner.hasFlag(JAVA)

    /** Given a set `rawSyms' of term- and type-symbols, and a type `tp'.
     *  produce a set of fresh type parameters and a type so that it can be 
     *  abstracted to an existential type.
     *  Every type symbol `T' in `rawSyms' is mapped to a clone.
     *  Every term symbol `x' of type `T' in `rawSyms' is given an
     *  associated type symbol of the following form:
     *
     *    type x.type <: T with <singleton>
     *
     *  The name of the type parameter is `x.type', to produce nice diagnostics.
     *  The <singleton> parent ensures that the type parameter is still seen as a stable type.
     *  Type symbols in rawSyms are fully replaced by the new symbols.
     *  Term symbols are also replaced, except when they are the term
     *  symbol of an Ident tree, in which case only the type of the
     *  Ident is changed.
     */
    protected def existentialTransform(rawSyms: List[Symbol], tp: Type) = {
      val typeParams: List[Symbol] = rawSyms map { sym =>
        val name = if (sym.isType) sym.name else newTypeName(sym.name+".type")
        val bound = sym.existentialBound
        val sowner = if (isRawParameter(sym)) context.owner else sym.owner
        val quantified: Symbol = recycle(sowner.newAbstractType(sym.pos, name)) 
        trackSetInfo(quantified setFlag EXISTENTIAL)(bound.cloneInfo(quantified))
      }
      val typeParamTypes = typeParams map (_.tpe) // don't trackSetInfo here, since type already set!
      //println("ex trans "+rawSyms+" . "+tp+" "+typeParamTypes+" "+(typeParams map (_.info)))//DEBUG
      for (tparam <- typeParams) tparam.setInfo(tparam.info.subst(rawSyms, typeParamTypes))
      (typeParams, tp.subst(rawSyms, typeParamTypes))
    }

    /** Compute an existential type from raw hidden symbols `syms' and type `tp'
     */
    def packSymbols(hidden: List[Symbol], tp: Type): Type = 
      if (hidden.isEmpty) tp
      else {
//          Console.println("original type: "+tp)
//          Console.println("hidden symbols: "+hidden)
        val (tparams, tp1) = existentialTransform(hidden, tp)
//          Console.println("tparams: "+tparams+", result: "+tp1)
        val res = existentialAbstraction(tparams, tp1)
//          Console.println("final result: "+res)
        res
      }

    class SymInstance(val sym: Symbol, val tp: Type) {
      override def equals(other: Any): Boolean = other match {
        case that: SymInstance =>
          this.sym == that.sym && this.tp =:= that.tp
        case _ =>
          false
      }
      override def hashCode: Int = sym.hashCode * 41 + tp.hashCode
    }

    /** convert skolems to existentials */
    def packedType(tree: Tree, owner: Symbol): Type = {
      def defines(tree: Tree, sym: Symbol) = 
        sym.isExistentialSkolem && sym.unpackLocation == tree ||
        tree.isDef && tree.symbol == sym
      def isVisibleParameter(sym: Symbol) = 
        (sym hasFlag PARAM) && (sym.owner == owner) && (sym.isType || !owner.isAnonymousFunction)
      def containsDef(owner: Symbol, sym: Symbol): Boolean = 
        (!(sym hasFlag PACKAGE)) && {
          var o = sym.owner
          while (o != owner && o != NoSymbol && !(o hasFlag PACKAGE)) o = o.owner
          o == owner && !isVisibleParameter(sym)
        }
      var localSyms = collection.immutable.Set[Symbol]()
      var boundSyms = collection.immutable.Set[Symbol]()
      def isLocal(sym: Symbol): Boolean =
        if (sym == NoSymbol || sym.isRefinementClass || sym.isLocalDummy) false
        else if (owner == NoSymbol) tree exists (defines(_, sym))
        else containsDef(owner, sym) || isRawParameter(sym)
      def containsLocal(tp: Type): Boolean = 
        tp exists (t => isLocal(t.typeSymbol) || isLocal(t.termSymbol))
      val normalizeLocals = new TypeMap {
        def apply(tp: Type): Type = tp match {
          case TypeRef(pre, sym, args) =>
            if (sym.isAliasType && containsLocal(tp)) apply(tp.normalize)
            else {
              if (pre.isVolatile) 
                context.error(tree.pos, "Inferred type "+tree.tpe+" contains type selection from volatile type "+pre)
              mapOver(tp) 
            }
          case _ =>
            mapOver(tp)
        }
      }
      // add all local symbols of `tp' to `localSyms'
      // expanding higher-kinded types into individual copies for each instance.
      def addLocals(tp: Type) {
        val remainingSyms = new ListBuffer[Symbol]
        def addIfLocal(sym: Symbol, tp: Type) {
          if (isLocal(sym) && !localSyms.contains(sym) && !boundSyms.contains(sym)) {
            if (sym.typeParams.isEmpty) {
              localSyms += sym
              remainingSyms += sym
            } else {
              unit.error(tree.pos, 
                "can't existentially abstract over parameterized type " + tp)
            } 
          }
        }

        for (t <- tp) {
          t match {
            case ExistentialType(tparams, _) => 
              boundSyms ++= tparams
            case AnnotatedType(annots, _, _) =>
              for (annot <- annots; arg <- annot.args) {
                arg match {
                  case Ident(_) =>
                    // Check the symbol of an Ident, unless the
                    // Ident's type is already over an existential.
                    // (If the type is already over an existential,
                    // then remap the type, not the core symbol.)
                    if (!arg.tpe.typeSymbol.hasFlag(EXISTENTIAL))
                      addIfLocal(arg.symbol, arg.tpe)
                  case _ => ()
                }
              }
            case _ =>
          }
          addIfLocal(t.termSymbol, t)
          addIfLocal(t.typeSymbol, t)
        }
        for (sym <- remainingSyms) addLocals(sym.existentialBound)
      }

      val normalizedTpe = normalizeLocals(tree.tpe)
      addLocals(normalizedTpe)
      packSymbols(localSyms.toList, normalizedTpe)
    }

    protected def typedExistentialTypeTree(tree: ExistentialTypeTree, mode: Int): Tree = {
      for (wc <- tree.whereClauses)
        if (wc.symbol == NoSymbol) { namer.enterSym(wc); wc.symbol setFlag EXISTENTIAL }
        else context.scope enter wc.symbol
      val whereClauses1 = typedStats(tree.whereClauses, context.owner)
      for (vd @ ValDef(_, _, _, _) <- tree.whereClauses)
        if (vd.symbol.tpe.isVolatile)
          error(vd.pos, "illegal abstraction from value with volatile type "+vd.symbol.tpe)
      val tpt1 = typedType(tree.tpt, mode)
      val (typeParams, tpe) = existentialTransform(tree.whereClauses map (_.symbol), tpt1.tpe)
      //println(tpe + ": " + tpe.getClass )
      TypeTree(ExistentialType(typeParams, tpe)) setOriginal tree
    }

    /**
     *  @param tree ...
     *  @param mode ...
     *  @param pt   ...
     *  @return     ...
     */
    protected def typed1(tree: Tree, mode: Int, pt: Type): Tree = {
      //Console.println("typed1("+tree.getClass()+","+Integer.toHexString(mode)+","+pt+")")
      def ptOrLub(tps: List[Type]) = if (isFullyDefined(pt)) pt else lub(tps map (_.deconst))
      
      //@M! get the type of the qualifier in a Select tree, otherwise: NoType
      def prefixType(fun: Tree): Type = fun match { 
        case Select(qualifier, _) => qualifier.tpe
//        case Ident(name) => ??
        case _ => NoType
      }
      
      def typedAnnotated(ann: Tree, arg1: Tree): Tree = {
        /** mode for typing the annotation itself */
        val annotMode = mode & ~TYPEmode | EXPRmode

        if (arg1.isType) {
          // make sure the annotation is only typechecked once
          if (ann.tpe == null) {
            // an annotated type
            val selfsym =
              if (!settings.selfInAnnots.value)
                NoSymbol
              else
                arg1.tpe.selfsym match {
                  case NoSymbol =>
                    /* Implementation limitation: Currently this
                     * can cause cyclical reference errors even
                     * when the self symbol is not referenced at all.
                     * Surely at least some of these cases can be
                     * fixed by proper use of LazyType's.  Lex tinkered
                     * on this but did not succeed, so is leaving
                     * it alone for now. Example code with the problem:
                     *  class peer extends Annotation
                     *  class NPE[T <: NPE[T] @peer]
                     *
                     * (Note: -Yself-in-annots must be on to see the problem)
                     **/
                    val sym = 
                      newLocalDummy(context.owner, ann.pos)
                        .newValue(ann.pos, nme.self)
                    sym.setInfo(arg1.tpe.withoutAnnotations)
                    sym
                  case sym => sym
                }

            val ainfo = typedAnnotation(ann, annotMode, selfsym)
            val atype0 = arg1.tpe.withAnnotation(ainfo)
            val atype =
              if ((selfsym != NoSymbol) && (ainfo.refsSymbol(selfsym)))
                atype0.withSelfsym(selfsym)
              else
                atype0 // do not record selfsym if
                       // this annotation did not need it

            if (ainfo.isErroneous)
              arg1  // simply drop erroneous annotations
            else {
              ann.tpe = atype
              TypeTree(atype) setOriginal tree
            }
          } else {
            // the annotation was typechecked before
            TypeTree(ann.tpe) setOriginal tree
          }
        } else {
          // An annotated term, created with annotation ascription
          //   term : @annot()
          def annotTypeTree(ainfo: AnnotationInfo): Tree = 
            TypeTree(arg1.tpe.withAnnotation(ainfo)) setOriginal tree

          if (ann.tpe == null) {
            val annotInfo = typedAnnotation(ann, annotMode)
            ann.tpe = arg1.tpe.withAnnotation(annotInfo)
          }
          val atype = ann.tpe
          Typed(arg1, TypeTree(atype) setOriginal tree setPos tree.pos.focus) setPos tree.pos setType atype
        }
      }

      def typedBind(name: Name, body: Tree) = {
        var vble = tree.symbol
        if (name.isTypeName) {
          assert(body == EmptyTree)
          if (vble == NoSymbol) 
            vble = 
              if (isFullyDefined(pt))
                context.owner.newAliasType(tree.pos, name) setInfo pt
              else 
                context.owner.newAbstractType(tree.pos, name) setInfo
                  mkTypeBounds(NothingClass.tpe, AnyClass.tpe)
          val rawInfo = vble.rawInfo
          vble = if (vble.name == nme.WILDCARD.toTypeName) context.scope.enter(vble)
                 else namer.enterInScope(vble) 
          trackSetInfo(vble)(rawInfo) // vble could have been recycled, detect changes in type       
          tree setSymbol vble setType vble.tpe
        } else {
          if (vble == NoSymbol) 
            vble = context.owner.newValue(tree.pos, name)
          if (vble.name.toTermName != nme.WILDCARD) {
/*
          if (namesSomeIdent(vble.name))
            context.warning(tree.pos,
              "pattern variable"+vble.name+" shadows a value visible in the environment;\n"+
              "use backquotes `"+vble.name+"` if you mean to match against that value;\n" +
              "or rename the variable or use an explicit bind "+vble.name+"@_ to avoid this warning.")
*/
            if ((mode & ALTmode) != 0)
              error(tree.pos, "illegal variable in pattern alternative")
            vble = namer.enterInScope(vble)
          }
          val body1 = typed(body, mode, pt)
          trackSetInfo(vble)(
            if (treeInfo.isSequenceValued(body)) seqType(body1.tpe)
            else body1.tpe)
          treeCopy.Bind(tree, name, body1) setSymbol vble setType body1.tpe   // buraq, was: pt
        }
      }

      def typedArrayValue(elemtpt: Tree, elems: List[Tree]) = {
        val elemtpt1 = typedType(elemtpt, mode)
        val elems1 = elems mapConserve (elem => typed(elem, mode, elemtpt1.tpe))
        treeCopy.ArrayValue(tree, elemtpt1, elems1)
          .setType(
            (if (isFullyDefined(pt) && !phase.erasedTypes) pt
             else appliedType(ArrayClass.typeConstructor, List(elemtpt1.tpe))).notNull)
      }

      def typedAssign(lhs: Tree, rhs: Tree): Tree = {
        def mayBeVarGetter(sym: Symbol) = sym.info match {
          case PolyType(List(), _) => sym.owner.isClass && !sym.isStable
          case _: ImplicitMethodType => sym.owner.isClass && !sym.isStable
          case _ => false
        }
        val lhs1 = typed(lhs, EXPRmode | LHSmode, WildcardType)
        val varsym = lhs1.symbol
        if ((varsym ne null) && mayBeVarGetter(varsym))
          lhs1 match {
            case Select(qual, name) =>
              return typed(
                Apply(
                  Select(qual, nme.getterToSetter(name)) setPos lhs.pos,
                  List(rhs)) setPos tree.pos, 
                mode, pt)

            case _ =>

          }
        if ((varsym ne null) && (varsym.isVariable || varsym.isValue && phase.erasedTypes)) {
          val rhs1 = typed(rhs, lhs1.tpe)
          treeCopy.Assign(tree, lhs1, checkDead(rhs1)) setType UnitClass.tpe
        } else {
          if (!lhs1.tpe.isError) {
            //println(lhs1+" = "+rhs+" "+varsym+" "+mayBeVarGetter(varsym)+" "+varsym.ownerChain+" "+varsym.info)// DEBUG
            error(tree.pos, 
                  if ((varsym ne null) && varsym.isValue) "reassignment to val"
                  else "assignment to non variable")
          }
          setError(tree)
        }
      }

      def typedIf(cond: Tree, thenp: Tree, elsep: Tree) = {
        val cond1 = checkDead(typed(cond, BooleanClass.tpe))
        if (elsep.isEmpty) { // in the future, should be unecessary
          val thenp1 = typed(thenp, UnitClass.tpe)
          treeCopy.If(tree, cond1, thenp1, elsep) setType thenp1.tpe
        } else { 
          val thenp1 = typed(thenp, pt)
          val elsep1 = typed(elsep, pt)
          treeCopy.If(tree, cond1, thenp1, elsep1) setType ptOrLub(List(thenp1.tpe, elsep1.tpe))
        }
      }

      def typedReturn(expr: Tree) = {
        val enclMethod = context.enclMethod
        if (enclMethod == NoContext || 
            enclMethod.owner.isConstructor || 
            context.enclClass.enclMethod == enclMethod // i.e., we are in a constructor of a local class
            ) {
          errorTree(tree, "return outside method definition")
        } else {
          val DefDef(_, _, _, _, restpt, _) = enclMethod.tree
          var restpt0 = restpt
          if (restpt0.tpe eq null) {
            errorTree(tree, "" + enclMethod.owner +
                      " has return statement; needs result type")
          } else {
            context.enclMethod.returnsSeen = true
            val expr1: Tree = typed(expr, restpt0.tpe)
            treeCopy.Return(tree, checkDead(expr1)) setSymbol enclMethod.owner setType NothingClass.tpe
          }
        }
      }

      def typedNew(tpt: Tree) = {
        var tpt1 = typedTypeConstructor(tpt)
        checkClassType(tpt1, false)
        if (tpt1.hasSymbol && !tpt1.symbol.typeParams.isEmpty) {
          context.undetparams = cloneSymbols(tpt1.symbol.typeParams)
          tpt1 = TypeTree()
            .setOriginal(tpt1)
            .setType(appliedType(tpt1.tpe, context.undetparams map (_.tpe)))
        }
        /** If current tree <tree> appears in <val x(: T)? = <tree>>
         *  return `tp with x.type' else return `tp'.
         */
        def narrowRhs(tp: Type) = {
          var sym = context.tree.symbol
          if (sym != null && sym != NoSymbol && sym.owner.isClass && sym.getter(sym.owner) != NoSymbol) 
            sym = sym.getter(sym.owner)
          context.tree match {
            case ValDef(mods, _, _, Apply(Select(`tree`, _), _)) if !(mods hasFlag MUTABLE) =>
              val pre = if (sym.owner.isClass) sym.owner.thisType else NoPrefix
              intersectionType(List(tp, singleType(pre, sym)))
            case _ =>
              tp
          }
        }
        if (tpt1.tpe.typeSymbol.isAbstractType || (tpt1.tpe.typeSymbol hasFlag ABSTRACT))
          error(tree.pos, tpt1.tpe.typeSymbol + " is abstract; cannot be instantiated")
        else if (tpt1.tpe.typeSymbol.initialize.thisSym != tpt1.tpe.typeSymbol &&
                 !(narrowRhs(tpt1.tpe) <:< tpt1.tpe.typeOfThis) && 
                 !phase.erasedTypes) {
          error(tree.pos, tpt1.tpe.typeSymbol + 
                " cannot be instantiated because it does not conform to its self-type "+
                tpt1.tpe.typeOfThis)
        }
        treeCopy.New(tree, tpt1).setType(tpt1.tpe)
      }

      def typedEta(expr1: Tree): Tree = expr1.tpe match {
        case TypeRef(_, sym, _) if (sym == ByNameParamClass) =>
          val expr2 = Function(List(), expr1) setPos expr1.pos
          new ChangeOwnerTraverser(context.owner, expr2.symbol).traverse(expr2)
          typed1(expr2, mode, pt)
        case PolyType(List(), restpe) =>
          val expr2 = Function(List(), expr1) setPos expr1.pos
          new ChangeOwnerTraverser(context.owner, expr2.symbol).traverse(expr2)
          typed1(expr2, mode, pt)
        case PolyType(_, MethodType(formals, _)) =>
          if (isFunctionType(pt)) expr1
          else adapt(expr1, mode, functionType(formals map (t => WildcardType), WildcardType))
        case MethodType(formals, _) =>
          if (isFunctionType(pt)) expr1
          else expr1 match {
            case Select(qual, name) if (forMSIL && 
                                        pt != WildcardType && 
                                        pt != ErrorType && 
                                        isSubType(pt, DelegateClass.tpe)) =>
              val scalaCaller = newScalaCaller(pt);
              addScalaCallerInfo(scalaCaller, expr1.symbol)
              val n: Name = scalaCaller.name
              val del = Ident(DelegateClass) setType DelegateClass.tpe
              val f = Select(del, n)
              //val f1 = TypeApply(f, List(Ident(pt.symbol) setType pt))
              val args: List[Tree] = if(expr1.symbol.isStatic) List(Literal(Constant(null)))
                                     else List(qual) // where the scala-method is located
              val rhs = Apply(f, args);
              typed(rhs)
            case _ => 
              adapt(expr1, mode, functionType(formals map (t => WildcardType), WildcardType))
          }
        case ErrorType =>
          expr1
        case _ =>
          errorTree(expr1, "_ must follow method; cannot follow " + expr1.tpe)
      }

      def typedTypeApply(fun: Tree, args: List[Tree]): Tree = fun.tpe match {
        case OverloadedType(pre, alts) =>
          inferPolyAlternatives(fun, args map (_.tpe))
          val tparams = fun.symbol.typeParams //@M TODO: fun.symbol.info.typeParams ? (as in typedAppliedTypeTree)
          val args1 = if(args.length == tparams.length) {
            //@M: in case TypeApply we can't check the kind-arities of the type arguments,
            // as we don't know which alternative to choose... here we do
            map2Conserve(args, tparams) { 
              //@M! the polytype denotes the expected kind
              (arg, tparam) => typedHigherKindedType(arg, mode, polyType(tparam.typeParams, AnyClass.tpe)) 
            }          
          } else // @M: there's probably something wrong when args.length != tparams.length... (triggered by bug #320)
           // Martin, I'm using fake trees, because, if you use args or arg.map(typedType), 
           // inferPolyAlternatives loops...  -- I have no idea why :-(
           // ...actually this was looping anyway, see bug #278.
            return errorTree(fun, "wrong number of type parameters for "+treeSymTypeMsg(fun))
          
          typedTypeApply(fun, args1)
        case SingleType(_, _) =>
          typedTypeApply(fun setType fun.tpe.widen, args)
        case PolyType(tparams, restpe) if (tparams.length != 0) =>
          if (tparams.length == args.length) {
            val targs = args map (_.tpe)
            checkBounds(tree.pos, NoPrefix, NoSymbol, tparams, targs, "")
            if (fun.symbol == Predef_classOf) {
              checkClassType(args.head, true) 
              atPos(tree.pos) { gen.mkClassOf(targs.head) }
            } else {
              if (phase.id <= currentRun.typerPhase.id &&
                  fun.symbol == Any_isInstanceOf && !targs.isEmpty)
                checkCheckable(tree.pos, targs.head, "")
              val resultpe = restpe.instantiateTypeParams(tparams, targs)
              //@M substitution in instantiateParams needs to be careful!
              //@M example: class Foo[a] { def foo[m[x]]: m[a] = error("") } (new Foo[Int]).foo[List] : List[Int]
              //@M    --> first, m[a] gets changed to m[Int], then m gets substituted for List, 
              //          this must preserve m's type argument, so that we end up with List[Int], and not List[a]
              //@M related bug: #1438 
              //println("instantiating type params "+restpe+" "+tparams+" "+targs+" = "+resultpe)
              treeCopy.TypeApply(tree, fun, args) setType resultpe
            }
          } else {
            errorTree(tree, "wrong number of type parameters for "+treeSymTypeMsg(fun))
          }
        case ErrorType =>
          setError(tree)
        case _ =>
          errorTree(tree, treeSymTypeMsg(fun)+" does not take type parameters.")
      }

      /**
       *  @param args ...
       *  @return     ...
       */
      def tryTypedArgs(args: List[Tree], mode: Int, other: TypeError): List[Tree] = {
        val c = context.makeSilent(false)
        c.retyping = true
        try {
          newTyper(c).typedArgs(args, mode)
        } catch {
          case ex: TypeError =>
            null
        }
      }

      /** Try to apply function to arguments; if it does not work try to
       *  insert an implicit conversion.
       *
       *  @param fun  ...
       *  @param args ...
       *  @return     ...
       */
      def tryTypedApply(fun: Tree, args: List[Tree]): Tree = {
        val start = System.nanoTime()
        silent(_.doTypedApply(tree, fun, args, mode, pt)) match {
          case t: Tree =>
            t
          case ex: TypeError =>
            failedApplies += System.nanoTime() - start 
            def errorInResult(tree: Tree): Boolean = tree.pos == ex.pos || {
              tree match {
                case Block(_, r) => errorInResult(r)
                case Match(_, cases) => cases exists errorInResult
                case CaseDef(_, _, r) => errorInResult(r)
                case Annotated(_, r) => errorInResult(r)
                case If(_, t, e) => errorInResult(t) || errorInResult(e)
                case Try(b, catches, _) => errorInResult(b) || (catches exists errorInResult)
                case Typed(r, Function(List(), EmptyTree)) => errorInResult(r)
                case _ => false
              }
            }
            if (errorInResult(fun) || (args exists errorInResult)) {
              val Select(qual, name) = fun
              val args1 = tryTypedArgs(args, argMode(fun, mode), ex)
              val qual1 =
                if ((args1 ne null) && !pt.isError) {
                  def templateArgType(arg: Tree) =
                    new BoundedWildcardType(mkTypeBounds(arg.tpe, AnyClass.tpe))
                  val dummyMethod = new TermSymbol(NoSymbol, NoPosition, "typer$dummy")
                  adaptToMember(qual, name, MethodType(dummyMethod.newSyntheticValueParams(args1 map templateArgType), pt))
                } else qual
              if (qual1 ne qual) {
                val tree1 = Apply(Select(qual1, name) setPos fun.pos, args1) setPos tree.pos
                return typed1(tree1, mode | SNDTRYmode, pt)
              }
            } 
            reportTypeError(tree.pos, ex)
            setError(tree)
        }
      }
    
      def typedApply(fun: Tree, args: List[Tree]) = {
        val stableApplication = (fun.symbol ne null) && fun.symbol.isMethod && fun.symbol.isStable
        if (stableApplication && (mode & PATTERNmode) != 0) {
          // treat stable function applications f() as expressions.
          typed1(tree, mode & ~PATTERNmode | EXPRmode, pt)
        } else {
          val funpt = if ((mode & PATTERNmode) != 0) pt else WildcardType
          val start = System.nanoTime()
          silent(_.typed(fun, funMode(mode), funpt)) match {
            case fun1: Tree =>
              val fun2 = if (stableApplication) stabilizeFun(fun1, mode, pt) else fun1
              if (util.Statistics.enabled) appcnt += 1
              val res = 
                if (phase.id <= currentRun.typerPhase.id &&
                    fun2.isInstanceOf[Select] && 
                    !fun2.tpe.isInstanceOf[ImplicitMethodType] &&
                    ((fun2.symbol eq null) || !fun2.symbol.isConstructor) &&
                    (mode & (EXPRmode | SNDTRYmode)) == EXPRmode) {
                      tryTypedApply(fun2, args)
                    } else {
                      doTypedApply(tree, fun2, args, mode, pt)
                    }
            /*
              if (fun2.hasSymbol && fun2.symbol.isConstructor && (mode & EXPRmode) != 0) {
                res.tpe = res.tpe.notNull
              }
              */
              if (fun2.symbol == Array_apply) {
                val checked = gen.mkCheckInit(res)
                // this check is needed to avoid infinite recursion in Duplicators
                // (calling typed1 more than once for the same tree)
                if (checked ne res) typed { atPos(tree.pos)(checked) }
       	        else res
              } else res
              /* Would like to do the following instead, but curiously this fails; todo: investigate
              if (fun2.symbol.name == nme.apply && fun2.symbol.owner == ArrayClass) 
                typed { atPos(tree.pos) { gen.mkCheckInit(res) } }
              else res
              */  
            case ex: TypeError =>
              failedOpEqs += System.nanoTime() - start
              fun match {
                case Select(qual, name) 
                if (mode & PATTERNmode) == 0 && nme.isOpAssignmentName(name.decode) =>
                  val qual1 = typedQualifier(qual)
                  if (treeInfo.isVariableOrGetter(qual1)) {
                    convertToAssignment(fun, qual1, name, args, ex) 
                  } else {
                    if ((qual1.symbol ne null) && qual1.symbol.isValue) 
                      error(tree.pos, "reassignment to val")
                    else
                      reportTypeError(fun.pos, ex)
                    setError(tree)                           
                  }
                case _ =>
                  reportTypeError(fun.pos, ex)
                  setError(tree)                           
              }
          }
        }
      }
    
      def convertToAssignment(fun: Tree, qual: Tree, name: Name, args: List[Tree], ex: TypeError): Tree = {
        val prefix = name.subName(0, name.length - nme.EQL.length)
        def mkAssign(vble: Tree): Tree = 
          Assign(
            vble,
            Apply(
              Select(vble.duplicate, prefix) setPos fun.pos.focus, args) setPos tree.pos.makeTransparent
          ) setPos tree.pos
        val tree1 = qual match {
          case Select(qualqual, vname) =>
            gen.evalOnce(qualqual, context.owner, context.unit) { qq =>
              val qq1 = qq()
              mkAssign(Select(qq1, vname) setPos qual.pos)
            }
          case Apply(Select(table, nme.apply), indices) =>
            gen.evalOnceAll(table :: indices, context.owner, context.unit) { ts =>
              val tab = ts.head
              val is = ts.tail
              Apply(
                 Select(tab(), nme.update) setPos table.pos,
                 ((is map (i => i())) ::: List(
                   Apply(
                     Select(
                       Apply(
                         Select(tab(), nme.apply) setPos table.pos,
                         is map (i => i())) setPos qual.pos,
                       prefix) setPos fun.pos, 
                     args) setPos tree.pos)
                 )
               ) setPos tree.pos
             }
           case Ident(_) =>
             mkAssign(qual)
        }                
        typed1(tree1, mode, pt)
/*
        if (settings.debug.value) log("retry assign: "+tree1)
        silent(_.typed1(tree1, mode, pt)) match {
          case t: Tree => 
            t
          case _ =>
            reportTypeError(tree.pos, ex)
            setError(tree)                           
        }
*/
      }

      def qualifyingClassSym(qual: Name): Symbol =
        if (tree.symbol != NoSymbol) tree.symbol else qualifyingClass(tree, qual, false)

      def typedSuper(qual: Name, mix: Name) = {
        val clazz = qualifyingClassSym(qual)
        if (clazz == NoSymbol) setError(tree)
        else {
          def findMixinSuper(site: Type): Type = {
            val ps = site.parents filter (p => compare(p.typeSymbol, mix))
            if (ps.isEmpty) {
              if (settings.debug.value)
                Console.println(site.parents map (_.typeSymbol.name))//debug
              if (phase.erasedTypes && context.enclClass.owner.isImplClass) {
                // the reference to super class got lost during erasure
                unit.error(tree.pos, "implementation restriction: traits may not select fields or methods from to super[C] where C is a class")
              } else {
                error(tree.pos, mix+" does not name a parent class of "+clazz)
              }
              ErrorType
            } else if (!ps.tail.isEmpty) {
              error(tree.pos, "ambiguous parent class qualifier")
              ErrorType
            } else {
              ps.head
            }
          }
          val owntype =
            if (mix.isEmpty) {
              if ((mode & SUPERCONSTRmode) != 0) 
                if (clazz.info.parents.isEmpty) AnyRefClass.tpe // can happen due to cyclic references ==> #1036
                else clazz.info.parents.head
              else intersectionType(clazz.info.parents)
            } else {
              findMixinSuper(clazz.info)
            }
          tree setSymbol clazz setType mkSuperType(clazz.thisType, owntype)
        }
      }

      def typedThis(qual: Name) = {
        val clazz = qualifyingClassSym(qual)
        if (clazz == NoSymbol) setError(tree)
        else {
          tree setSymbol clazz setType clazz.thisType.underlying
          if (isStableContext(tree, mode, pt)) tree setType clazz.thisType
          tree
        }
      }

      /** Attribute a selection where <code>tree</code> is <code>qual.name</code>.
       *  <code>qual</code> is already attributed.
       *
       *  @param qual ...
       *  @param name ...
       *  @return     ...
       */
      def typedSelect(qual: Tree, name: Name): Tree = {
        val sym =
          if (tree.symbol != NoSymbol) {
            if (phase.erasedTypes && qual.isInstanceOf[Super])
              qual.tpe = tree.symbol.owner.tpe
            if (false && settings.debug.value) { // todo: replace by settings.check.value?
              val alts = qual.tpe.member(tree.symbol.name).alternatives
              if (!(alts exists (alt =>
                alt == tree.symbol || alt.isTerm && (alt.tpe matches tree.symbol.tpe))))
                assert(false, "symbol "+tree.symbol+tree.symbol.locationString+" not in "+alts+" of "+qual.tpe+
                       "\n members = "+qual.tpe.members+
                       "\n type history = "+qual.tpe.termSymbol.infosString+
                       "\n phase = "+phase)
            }
            tree.symbol
          } else {
            member(qual, name)(context.owner)
          }
        if (sym == NoSymbol && name != nme.CONSTRUCTOR && (mode & EXPRmode) != 0) {
          val qual1 = adaptToName(qual, name)
          if (qual1 ne qual) return typed(treeCopy.Select(tree, qual1, name), mode, pt)
        }
        if (!sym.exists) {
          if (settings.debug.value) Console.err.println("qual = "+qual+":"+qual.tpe+"\nSymbol="+qual.tpe.termSymbol+"\nsymbol-info = "+qual.tpe.termSymbol.info+"\nscope-id = "+qual.tpe.termSymbol.info.decls.hashCode()+"\nmembers = "+qual.tpe.members+"\nname = "+name+"\nfound = "+sym+"\nowner = "+context.enclClass.owner)
          if (!qual.tpe.widen.isErroneous) {
            error(tree.pos,
              if (name == nme.CONSTRUCTOR) 
                qual.tpe.widen+" does not have a constructor"
              else 
                decode(name)+" is not a member of "+qual.tpe.widen +
                (if ((context.unit ne null) && // Martin: why is this condition needed?
                     qual.pos.isDefined && tree.pos.isDefined && qual.pos.line < tree.pos.line)
                  "\npossible cause: maybe a semicolon is missing before `"+decode(name)+"'?"
                 else ""))
          }
          
          // Temporary workaround to retain type information for qual so that askTypeCompletion has something to
          // work with. This appears to work in the context of the IDE, but is incorrect and needs to be
          // revisited.
          if (onlyPresentation) {
            // Nb. this appears to throw away the effects of setError, but some appear to be
            // retained across the copy.
            setError(tree)
            val tree1 = tree match {
              case Select(_, _) => treeCopy.Select(tree, qual, name)
              case SelectFromTypeTree(_, _) => treeCopy.SelectFromTypeTree(tree, qual, name)
            }
            tree1
          } else
            setError(tree)
        } else {
          val tree1 = tree match {
            case Select(_, _) => treeCopy.Select(tree, qual, name)
            case SelectFromTypeTree(_, _) => treeCopy.SelectFromTypeTree(tree, qual, name)
          }
          //if (name.toString == "Elem") println("typedSelect "+qual+":"+qual.tpe+" "+sym+"/"+tree1+":"+tree1.tpe)
          val (tree2, pre2) = makeAccessible(tree1, sym, qual.tpe, qual)
          val result = stabilize(tree2, pre2, mode, pt)
          def isPotentialNullDeference() = {
            phase.id <= currentRun.typerPhase.id &&
            !sym.isConstructor &&
            !(qual.tpe <:< NotNullClass.tpe) && !qual.tpe.isNotNull &&
            (result.symbol != Any_isInstanceOf)  // null.isInstanceOf[T] is not a dereference; bug #1356
          }
          if (settings.Xchecknull.value && isPotentialNullDeference)
            unit.warning(tree.pos, "potential null pointer dereference: "+tree)

          result
        }
      }

      /** does given name name an identifier visible at this point?
       *
       *  @param name the given name
       *  @return     <code>true</code> if an identifier with the given name is visible.
       */
      def namesSomeIdent(name: Name): Boolean = {
        var cx = context
        while (cx != NoContext) {
          val pre = cx.enclClass.prefix
          val defEntry = cx.scope.lookupEntryWithContext(name)(context.owner)
          if ((defEntry ne null) && defEntry.sym.exists) return true
          cx = cx.enclClass
          if ((pre.member(name) filter (
            sym => sym.exists && context.isAccessible(sym, pre, false))) != NoSymbol) return true
          cx = cx.outer
        }
        var imports = context.imports      // impSym != NoSymbol => it is imported from imports.head
        while (!imports.isEmpty) {
          if (imports.head.importedSymbol(name) != NoSymbol) return true
          imports = imports.tail
        }
        false
      }

      /** Attribute an identifier consisting of a simple name or an outer reference.
       *
       *  @param tree      The tree representing the identifier. 
       *  @param name      The name of the identifier.
       *  Transformations: (1) Prefix class members with this.
       *                   (2) Change imported symbols to selections
       */
      def typedIdent(name: Name): Tree = {
        def ambiguousError(msg: String) =
          error(tree.pos, "reference to " + name + " is ambiguous;\n" + msg)

        var defSym: Symbol = tree.symbol // the directly found symbol
        var pre: Type = NoPrefix         // the prefix type of defSym, if a class member
        var qual: Tree = EmptyTree       // the qualififier tree if transformed tree is a select

        // if we are in a constructor of a pattern, ignore all definitions
        // which are methods (note: if we don't do that
        // case x :: xs in class List would return the :: method).
        def qualifies(sym: Symbol): Boolean = 
          sym.exists && 
          ((mode & PATTERNmode | FUNmode) != (PATTERNmode | FUNmode) || !sym.isSourceMethod)
           
        if (defSym == NoSymbol) {
          var defEntry: ScopeEntry = null // the scope entry of defSym, if defined in a local scope

          var cx = context
          if ((mode & (PATTERNmode | TYPEPATmode)) != 0) {
            // println("ignoring scope: "+name+" "+cx.scope+" "+cx.outer.scope)
            // ignore current variable scope in patterns to enforce linearity
            cx = cx.outer 
          }
          
          while (defSym == NoSymbol && cx != NoContext) {
            pre = cx.enclClass.prefix
            defEntry = cx.scope.lookupEntryWithContext(name)(context.owner)
            if ((defEntry ne null) && qualifies(defEntry.sym)) {
              defSym = defEntry.sym
            } 
            else {
              cx = cx.enclClass
              defSym = pre.member(name) filter (
                sym => qualifies(sym) && context.isAccessible(sym, pre, false))
              if (defSym == NoSymbol) cx = cx.outer
            }
          }

          val symDepth = if (defEntry eq null) cx.depth
                         else cx.depth - (cx.scope.nestingLevel - defEntry.owner.nestingLevel)
          var impSym: Symbol = NoSymbol;      // the imported symbol
          var imports = context.imports;      // impSym != NoSymbol => it is imported from imports.head
          while (!impSym.exists && !imports.isEmpty && imports.head.depth > symDepth) {
            impSym = imports.head.importedSymbol(name)
            if (!impSym.exists) imports = imports.tail
          }

          // detect ambiguous definition/import,
          // update `defSym' to be the final resolved symbol,
          // update `pre' to be `sym's prefix type in case it is an imported member,
          // and compute value of:

          if (defSym.exists && impSym.exists) {
            // imported symbols take precedence over package-owned symbols in different
            // compilation units. Defined symbols take precedence over errenous imports.
            if (defSym.definedInPackage && 
                (!currentRun.compiles(defSym) ||
                 (context.unit ne null) && defSym.sourceFile != context.unit.source.file))
              defSym = NoSymbol
            else if (impSym.isError)
              impSym = NoSymbol
          }
          if (defSym.exists) {
            if (impSym.exists)
              ambiguousError(
                "it is both defined in "+defSym.owner +
                " and imported subsequently by \n"+imports.head)
            else if (!defSym.owner.isClass || defSym.owner.isPackageClass || defSym.isTypeParameterOrSkolem)
              pre = NoPrefix
            else
              qual = atPos(tree.pos.focusStart)(gen.mkAttributedQualifier(pre))
          } else {
            if (impSym.exists) {
              var impSym1 = NoSymbol
              var imports1 = imports.tail
              def ambiguousImport() = {
                if (!(imports.head.qual.tpe =:= imports1.head.qual.tpe))
                  ambiguousError(
                    "it is imported twice in the same scope by\n"+imports.head +  "\nand "+imports1.head)
              }
              while (!imports1.isEmpty && 
                     (!imports.head.isExplicitImport(name) ||
                      imports1.head.depth == imports.head.depth)) {
                var impSym1 = imports1.head.importedSymbol(name)
                if (impSym1.exists) {
                  if (imports1.head.isExplicitImport(name)) {
                    if (imports.head.isExplicitImport(name) ||
                        imports1.head.depth != imports.head.depth) ambiguousImport()
                    impSym = impSym1
                    imports = imports1
                  } else if (!imports.head.isExplicitImport(name) &&
                             imports1.head.depth == imports.head.depth) ambiguousImport()
                }
                imports1 = imports1.tail
              }
              defSym = impSym
              qual = atPos(tree.pos.focusStart)(resetPos(imports.head.qual.duplicate))
              pre = qual.tpe
            } else {
              if (settings.debug.value) {
                log(context.imports)//debug
              }
              error(tree.pos, "not found: "+decode(name))
              defSym = context.owner.newErrorSymbol(name)
            }
          }
        }
        if (defSym.owner.isPackageClass) pre = defSym.owner.thisType
        if (defSym.isThisSym) {
          typed1(This(defSym.owner) setPos tree.pos, mode, pt)
        } else {
          val tree1 = if (qual == EmptyTree) tree
                      else atPos(tree.pos)(Select(qual, name))
                    // atPos necessary because qualifier might come from startContext
          val (tree2, pre2) = makeAccessible(tree1, defSym, pre, qual)
          stabilize(tree2, pre2, mode, pt)
        }
      }

      def typedCompoundTypeTree(templ: Template) = {
        val parents1 = templ.parents mapConserve (typedType(_, mode))
        if (parents1 exists (_.tpe.isError)) tree setType ErrorType
        else {
          val decls = scopeFor(tree, CompoundTreeScopeKind)
          //Console.println("Owner: " + context.enclClass.owner + " " + context.enclClass.owner.id)
          val self = refinedType(parents1 map (_.tpe), context.enclClass.owner, decls, templ.pos)
          newTyper(context.make(templ, self.typeSymbol, decls)).typedRefinement(templ.body)
          tree setType self
        }
      }

      def typedAppliedTypeTree(tpt: Tree, args: List[Tree]) = {
        val tpt1 = typed1(tpt, mode | FUNmode | TAPPmode, WildcardType)
        if (tpt1.tpe.isError) {
          setError(tree)
        } else if (!tpt1.hasSymbol) {
          errorTree(tree, tpt1.tpe+" does not take type parameters")
        } else {
          val tparams = tpt1.symbol.typeParams 
          if (tparams.length == args.length) {
          // @M: kind-arity checking is done here and in adapt, full kind-checking is in checkKindBounds (in Infer)
            val args1 = 
              if(!tpt1.symbol.rawInfo.isComplete)
                args mapConserve (typedHigherKindedType(_, mode))
                // if symbol hasn't been fully loaded, can't check kind-arity
              else map2Conserve(args, tparams) { 
                (arg, tparam) => 
                  typedHigherKindedType(arg, mode, polyType(tparam.typeParams, AnyClass.tpe)) 
                  //@M! the polytype denotes the expected kind
              }
            val argtypes = args1 map (_.tpe)
            val owntype = if (tpt1.symbol.isClass || tpt1.symbol.isTypeMember) 
                             // @M! added the latter condition
                             appliedType(tpt1.tpe, argtypes) 
                          else tpt1.tpe.instantiateTypeParams(tparams, argtypes)
            List.map2(args, tparams) { (arg, tparam) => arg match {
              // note: can't use args1 in selector, because Bind's got replaced 
              case Bind(_, _) => 
                if (arg.symbol.isAbstractType)
                  arg.symbol setInfo // XXX, feedback. don't trackSymInfo here! 
                    TypeBounds(
                      lub(List(arg.symbol.info.bounds.lo, tparam.info.bounds.lo.subst(tparams, argtypes))),
                      glb(List(arg.symbol.info.bounds.hi, tparam.info.bounds.hi.subst(tparams, argtypes))))
              case _ =>
            }}
            TypeTree(owntype) setOriginal(tree) // setPos tree.pos
          } else if (tparams.length == 0) {
            errorTree(tree, tpt1.tpe+" does not take type parameters")
          } else {
            //Console.println("\{tpt1}:\{tpt1.symbol}:\{tpt1.symbol.info}")
            if (settings.debug.value) Console.println(tpt1+":"+tpt1.symbol+":"+tpt1.symbol.info);//debug
            errorTree(tree, "wrong number of type arguments for "+tpt1.tpe+", should be "+tparams.length)
          }
        }
      }

      // begin typed1
      implicit val scopeKind = TypedScopeKind
      val sym: Symbol = tree.symbol
      if ((sym ne null) && (sym ne NoSymbol)) sym.initialize 
      //if (settings.debug.value && tree.isDef) log("typing definition of "+sym);//DEBUG
      tree match {
        case PackageDef(pid, stats) =>
          val pid1 = typedQualifier(pid).asInstanceOf[RefTree]
          assert(sym.moduleClass ne NoSymbol, sym)
          val stats1 = newTyper(context.make(tree, sym.moduleClass, sym.info.decls))
            .typedStats(stats, NoSymbol)
          treeCopy.PackageDef(tree, pid1, stats1) setType NoType

        case tree @ ClassDef(_, _, _, _) =>
          newTyper(context.makeNewScope(tree, sym)).typedClassDef(tree)

        case tree @ ModuleDef(_, _, _) =>
          newTyper(context.makeNewScope(tree, sym.moduleClass)).typedModuleDef(tree)

        case vdef @ ValDef(_, _, _, _) =>
          typedValDef(vdef)

        case ddef @ DefDef(_, _, _, _, _, _) => 
          newTyper(context.makeNewScope(tree, sym)).typedDefDef(ddef)

        case tdef @ TypeDef(_, _, _, _) =>
          newTyper(context.makeNewScope(tree, sym)).typedTypeDef(tdef)

        case ldef @ LabelDef(_, _, _) =>
          labelTyper(ldef).typedLabelDef(ldef)

        case DocDef(comment, defn) =>
          val ret = typed(defn, mode, pt)
          if ((comments ne null) && (defn.symbol ne null) && (defn.symbol ne NoSymbol)) comments(defn.symbol) = comment
          ret

        case Annotated(constr, arg) =>
          typedAnnotated(constr, typed(arg, mode, pt))

        case tree @ Block(_, _) =>
          newTyper(context.makeNewScope(tree, context.owner)(BlockScopeKind(context.depth)))
            .typedBlock(tree, mode, pt)

        case Sequence(elems) =>
          checkRegPatOK(tree.pos, mode)
          val elems1 = elems mapConserve (elem => typed(elem, mode, pt))
          treeCopy.Sequence(tree, elems1) setType pt

        case Alternative(alts) =>
          val alts1 = alts mapConserve (alt => typed(alt, mode | ALTmode, pt))
          treeCopy.Alternative(tree, alts1) setType pt

        case Star(elem) =>
          checkRegPatOK(tree.pos, mode)
          val elem1 = typed(elem, mode, pt)
          treeCopy.Star(tree, elem1) setType pt

        case Bind(name, body) =>
          typedBind(name, body)

        case UnApply(fun, args) =>
          val fun1 = typed(fun)
          val tpes = formalTypes(unapplyTypeList(fun.symbol, fun1.tpe), args.length)
          val args1 = List.map2(args, tpes)(typedPattern(_, _))
          treeCopy.UnApply(tree, fun1, args1) setType pt

        case ArrayValue(elemtpt, elems) =>
          typedArrayValue(elemtpt, elems)

        case tree @ Function(_, _) =>
          if (tree.symbol == NoSymbol)
            tree.symbol = recycle(context.owner.newValue(tree.pos, nme.ANON_FUN_NAME)
              .setFlag(SYNTHETIC).setInfo(NoType))
          newTyper(context.makeNewScope(tree, tree.symbol)).typedFunction(tree, mode, pt)

        case Assign(lhs, rhs) =>
          typedAssign(lhs, rhs)

        case AssignOrNamedArg(lhs, rhs) => // called by NamesDefaults in silent typecheck
          typedAssign(lhs, rhs)

        case If(cond, thenp, elsep) =>
          typedIf(cond, thenp, elsep)

        case tree @ Match(selector, cases) =>
          if (selector == EmptyTree) {
            val arity = if (isFunctionType(pt)) pt.normalize.typeArgs.length - 1 else 1
            val params = for (i <- List.range(0, arity)) yield 
              atPos(tree.pos.focusStart) {
                ValDef(Modifiers(PARAM | SYNTHETIC), 
                       unit.fresh.newName(tree.pos, "x" + i + "$"), TypeTree(), EmptyTree)
              }
            val ids = for (p <- params) yield Ident(p.name)
            val selector1 = atPos(tree.pos.focusStart) { if (arity == 1) ids.head else gen.mkTuple(ids) }
            val body = treeCopy.Match(tree, selector1, cases)
            typed1(atPos(tree.pos) { Function(params, body) }, mode, pt)
          } else {
            val selector1 = checkDead(typed(selector))
            val cases1 = typedCases(tree, cases, selector1.tpe.widen, pt)
            treeCopy.Match(tree, selector1, cases1) setType ptOrLub(cases1 map (_.tpe))
          }

        case Return(expr) =>
          typedReturn(expr)

        case Try(block, catches, finalizer) =>
          val block1 = typed(block, pt)
          val catches1 = typedCases(tree, catches, ThrowableClass.tpe, pt)
          val finalizer1 = if (finalizer.isEmpty) finalizer
                           else typed(finalizer, UnitClass.tpe)
          treeCopy.Try(tree, block1, catches1, finalizer1)
            .setType(ptOrLub(block1.tpe :: (catches1 map (_.tpe))))

        case Throw(expr) =>
          val expr1 = typed(expr, ThrowableClass.tpe)
          treeCopy.Throw(tree, expr1) setType NothingClass.tpe

        case New(tpt: Tree) =>
          typedNew(tpt)

        case Typed(expr, Function(List(), EmptyTree)) =>
          typedEta(checkDead(typed1(expr, mode, pt)))

        case Typed(expr, tpt) =>
          if (treeInfo.isWildcardStarArg(tree)) {
            val expr0 = typed(expr, mode & stickyModes, WildcardType)
            def subArrayType(pt: Type) =
              if (isValueClass(pt.typeSymbol) || !isFullyDefined(pt)) arrayType(pt)
              else {
                val tparam = makeFreshExistential("", context.owner, TypeBounds(NothingClass.tpe, pt))
                ExistentialType(List(tparam), arrayType(tparam.tpe))
              }
            val (expr1, baseClass) = 
              if (expr0.tpe.typeSymbol == ArrayClass)
                (adapt(expr0, mode & stickyModes, subArrayType(pt)), ArrayClass)
              else
                (adapt(expr0, mode & stickyModes, seqType(pt)), SeqClass)
            expr1.tpe.baseType(baseClass) match {
              case TypeRef(_, _, List(elemtp)) =>
                treeCopy.Typed(tree, expr1, tpt setType elemtp) setType elemtp
              case _ =>
                setError(tree)
            }
          } else {
            val tpt1 = typedType(tpt, mode)
            val expr1 = typed(expr, mode & stickyModes, tpt1.tpe.deconst)
            val owntype = 
              if ((mode & PATTERNmode) != 0) inferTypedPattern(tpt1.pos, tpt1.tpe, pt) 
              else tpt1.tpe
            //Console.println(typed pattern: "+tree+":"+", tp = "+tpt1.tpe+", pt = "+pt+" ==> "+owntype)//DEBUG
            treeCopy.Typed(tree, expr1, tpt1) setType owntype
          }

        case TypeApply(fun, args) =>
          // @M: kind-arity checking is done here and in adapt, full kind-checking is in checkKindBounds (in Infer)        
          //@M! we must type fun in order to type the args, as that requires the kinds of fun's type parameters.
          // However, args should apparently be done first, to save context.undetparams. Unfortunately, the args
          // *really* have to be typed *after* fun. We escape from this classic Catch-22 by simply saving&restoring undetparams.

          // @M TODO: the compiler still bootstraps&all tests pass when this is commented out..
          //val undets = context.undetparams 
          
          // @M: fun is typed in TAPPmode because it is being applied to its actual type parameters
          val fun1 = typed(fun, funMode(mode) | TAPPmode, WildcardType) 
          val tparams = fun1.symbol.typeParams

          //@M TODO: val undets_fun = context.undetparams  ?
          // "do args first" (by restoring the context.undetparams) in order to maintain context.undetparams on the function side.
          
          // @M TODO: the compiler still bootstraps when this is commented out.. TODO: run tests
          //context.undetparams = undets
          
          // @M maybe the well-kindedness check should be done when checking the type arguments conform to the type parameters' bounds?          
          val args1 = if(args.length == tparams.length) map2Conserve(args, tparams) { 
                        //@M! the polytype denotes the expected kind
                        (arg, tparam) => typedHigherKindedType(arg, mode, polyType(tparam.typeParams, AnyClass.tpe)) 
                      } else { 
                      //@M  this branch is correctly hit for an overloaded polymorphic type. It also has to handle erroneous cases.
                      // Until the right alternative for an overloaded method is known, be very liberal, 
                      // typedTypeApply will find the right alternative and then do the same check as 
                      // in the then-branch above. (see pos/tcpoly_overloaded.scala)
                      // this assert is too strict: be tolerant for errors like trait A { def foo[m[x], g]=error(""); def x[g] = foo[g/*ERR: missing argument type*/] }
                      //assert(fun1.symbol.info.isInstanceOf[OverloadedType] || fun1.symbol.isError) //, (fun1.symbol,fun1.symbol.info,fun1.symbol.info.getClass,args,tparams))
                        args mapConserve (typedHigherKindedType(_, mode)) 
                      }

          //@M TODO: context.undetparams = undets_fun ?
          typedTypeApply(fun1, args1)

        case Apply(Block(stats, expr), args) =>
          typed1(atPos(tree.pos)(Block(stats, Apply(expr, args))), mode, pt)

        case Apply(fun, args) =>
          typedApply(fun, args) match {
            case Apply(Select(New(tpt), name), args) 
            if (tpt.tpe != null &&
                tpt.tpe.typeSymbol == ArrayClass && 
                args.length == 1 && 
                erasure.GenericArray.unapply(tpt.tpe).isDefined) => // !!! todo simplify by using extractor
              // convert new Array[T](len) to evidence[ClassManifest[T]].newArray(len)
              // convert new Array^N[T](len) for N > 1 to evidence[ClassManifest[T]].newArrayN(len)
              val Some((level, manifType)) = erasure.GenericArray.unapply(tpt.tpe)
              if (level > MaxArrayDims) 
                error(tree.pos, "cannot create a generic multi-dimensional array of more than "+MaxArrayDims+" dimensions")
              val newArrayApp = atPos(tree.pos) {
                val manif = getManifestTree(tree.pos, manifType, false)
                Apply(Select(manif, if (level == 1) "newArray" else "newArray"+level), args)
              }
              typed(newArrayApp, mode, pt)
            case tree1 =>
              tree1
          }

        case ApplyDynamic(qual, args) =>
          val reflectiveCalls = !(settings.refinementMethodDispatch.value == "invoke-dynamic") 
          val qual1 = typed(qual, AnyRefClass.tpe)
          val args1 = args mapConserve (arg => if (reflectiveCalls) typed(arg, AnyRefClass.tpe) else typed(arg))
          treeCopy.ApplyDynamic(tree, qual1, args1) setType (if (reflectiveCalls) AnyRefClass.tpe else tree.symbol.info.resultType)

        case Super(qual, mix) =>
          typedSuper(qual, mix)

        case This(qual) =>
          typedThis(qual)

        case Select(qual @ Super(_, _), nme.CONSTRUCTOR) =>
          val qual1 = 
            typed(qual, EXPRmode | QUALmode | POLYmode | SUPERCONSTRmode, WildcardType)
          // the qualifier type of a supercall constructor is its first parent class
          typedSelect(qual1, nme.CONSTRUCTOR)

        case Select(qual, name) =>
          if (util.Statistics.enabled) selcnt += 1
          var qual1 = checkDead(typedQualifier(qual, mode))
          if (name.isTypeName) qual1 = checkStable(qual1)
          val tree1 = typedSelect(qual1, name)
          if (qual1.symbol == RootPackage) treeCopy.Ident(tree1, name)
          else tree1

        case Ident(name) =>
          if (util.Statistics.enabled) idcnt += 1
          if ((name == nme.WILDCARD && (mode & (PATTERNmode | FUNmode)) == PATTERNmode) ||
              (name == nme.WILDCARD.toTypeName && (mode & TYPEmode) != 0))
            tree setType makeFullyDefined(pt)
          else 
            typedIdent(name)
          
        case Literal(value) =>
          tree setType (
            if (value.tag == UnitTag) UnitClass.tpe
            else mkConstantType(value))

        case SingletonTypeTree(ref) =>
          val ref1 = checkStable(
            typed(ref, EXPRmode | QUALmode | (mode & TYPEPATmode), AnyRefClass.tpe))
          tree setType ref1.tpe.resultType

        case SelectFromTypeTree(qual, selector) =>
          val qual1 = typedType(qual, mode)
          if (qual1.tpe.isVolatile) error(tree.pos, "illegal type selection from volatile type "+qual.tpe) 
          typedSelect(typedType(qual, mode), selector)

        case CompoundTypeTree(templ) =>
          typedCompoundTypeTree(templ)

        case AppliedTypeTree(tpt, args) =>
          typedAppliedTypeTree(tpt, args)

        case TypeBoundsTree(lo, hi) =>
          val lo1 = typedType(lo, mode)
          val hi1 = typedType(hi, mode)
          treeCopy.TypeBoundsTree(tree, lo1, hi1) setType mkTypeBounds(lo1.tpe, hi1.tpe)

        case etpt @ ExistentialTypeTree(_, _) =>
          newTyper(context.makeNewScope(tree, context.owner)).typedExistentialTypeTree(etpt, mode)

        case tpt @ TypeTree() =>
          if (tpt.original != null)
            tree setType typedType(tpt.original, mode).tpe
          else
            // we should get here only when something before failed 
            // and we try again (@see tryTypedApply). In that case we can assign 
            // whatever type to tree; we just have to survive until a real error message is issued.
            tree setType AnyClass.tpe
        case _ =>
          throw new Error("unexpected tree: " + tree.getClass + "\n" + tree)//debug
      }
    }

    /**
     *  @param tree ...
     *  @param mode ...
     *  @param pt   ...
     *  @return     ...
     */
     def typed(tree: Tree, mode: Int, pt: Type): Tree = {
      
      def dropExistential(tp: Type): Type = tp match {
        case ExistentialType(tparams, tpe) => 
          if (settings.debug.value) println("drop ex "+tree+" "+tp)
          new SubstWildcardMap(tparams).apply(tp)
        case TypeRef(_, sym, _) if sym.isAliasType =>
          val tp0 = tp.normalize
          val tp1 = dropExistential(tp0)
          if (tp1 eq tp0) tp else tp1
        case _ => tp
      }

      try {
        if (context.retyping &&
            (tree.tpe ne null) && (tree.tpe.isErroneous || !(tree.tpe <:< pt))) {
          tree.tpe = null
          if (tree.hasSymbol) tree.symbol = NoSymbol
        }
        if (printTypings) println("typing "+tree+", "+context.undetparams+(mode & TYPEPATmode)); //DEBUG

        var tree1 = if (tree.tpe ne null) tree else typed1(tree, mode, dropExistential(pt))
        if (printTypings) println("typed "+tree1+":"+tree1.tpe+", "+context.undetparams+", pt = "+pt); //DEBUG
       
        tree1.tpe = addAnnotations(tree1, tree1.tpe)

        val result = if (tree1.isEmpty) tree1 else adapt(tree1, mode, pt)
        if (printTypings) println("adapted "+tree1+":"+tree1.tpe.widen+" to "+pt+", "+context.undetparams); //DEBUG
//      for (t <- tree1.tpe) assert(t != WildcardType)
//      if ((mode & TYPEmode) != 0) println("type: "+tree1+" has type "+tree1.tpe)
        if (phase.id <= currentRun.typerPhase.id) signalDone(context.asInstanceOf[analyzer.Context], tree, result)
        result
      } catch {
        case ex: ControlException => throw ex
        case ex: TypeError =>
          tree.tpe = null
          //Console.println("caught "+ex+" in typed");//DEBUG
          reportTypeError(tree.pos, ex)
          setError(tree)
        case ex: Exception =>
          if (settings.debug.value) // @M causes cyclic reference error
            Console.println("exception when typing "+tree+", pt = "+pt)
          if ((context ne null) && (context.unit ne null) &&
              (context.unit.source ne null) && (tree ne null))
            logError("AT: " + (tree.pos).dbgString, ex);
          throw(ex)
/*
        case ex: java.lang.Error =>
          Console.println("exception when typing "+tree+", pt = "+pt)
          throw ex
*/ //debug
      }
    }

    def atOwner(owner: Symbol): Typer =
      newTyper(context.make(context.tree, owner))

    def atOwner(tree: Tree, owner: Symbol): Typer =
      newTyper(context.make(tree, owner))

    /** Types expression or definition <code>tree</code>.
     *
     *  @param tree ...
     *  @return     ...
     */
    def typed(tree: Tree): Tree = {
      val ret = typed(tree, EXPRmode, WildcardType)
      ret
    }

    def typedPos(pos: Position)(tree: Tree) = typed(atPos(pos)(tree))

    /** Types expression <code>tree</code> with given prototype <code>pt</code>.
     *
     *  @param tree ...
     *  @param pt   ...
     *  @return     ...
     */
    def typed(tree: Tree, pt: Type): Tree =
      typed(tree, EXPRmode, pt)

    /** Types qualifier <code>tree</code> of a select node.
     *  E.g. is tree occurs in a context like <code>tree.m</code>.
     *
     *  @param tree ...
     *  @return     ...
     */
    def typedQualifier(tree: Tree, mode: Int): Tree =
      typed(tree, EXPRmode | QUALmode | POLYmode | mode & TYPEPATmode, WildcardType)

    def typedQualifier(tree: Tree): Tree = typedQualifier(tree, NOmode)

    /** Types function part of an application */
    def typedOperator(tree: Tree): Tree =
      typed(tree, EXPRmode | FUNmode | POLYmode | TAPPmode, WildcardType)

    /** Types a pattern with prototype <code>pt</code> */
    def typedPattern(tree: Tree, pt: Type): Tree =
      typed(tree, PATTERNmode, pt)

    /** Types a (fully parameterized) type tree */
    def typedType(tree: Tree, mode: Int): Tree =
      typed(tree, typeMode(mode), WildcardType)

    /** Types a (fully parameterized) type tree */
    def typedType(tree: Tree): Tree = typedType(tree, NOmode)

    /** Types a higher-kinded type tree -- pt denotes the expected kind*/
    def typedHigherKindedType(tree: Tree, mode: Int, pt: Type): Tree =
      if (pt.typeParams.isEmpty) typedType(tree, mode) // kind is known and it's *
      else typed(tree, HKmode, pt)
      
    def typedHigherKindedType(tree: Tree, mode: Int): Tree = 
      typed(tree, HKmode, WildcardType)
             
    def typedHigherKindedType(tree: Tree): Tree = typedHigherKindedType(tree, NOmode)

    /** Types a type constructor tree used in a new or supertype */
    def typedTypeConstructor(tree: Tree, mode: Int): Tree = {
      val result = typed(tree, typeMode(mode) | FUNmode, WildcardType)
      val restpe = result.tpe.normalize
      if (!phase.erasedTypes && restpe.isInstanceOf[TypeRef] && !restpe.prefix.isStable) {
        error(tree.pos, restpe.prefix+" is not a legal prefix for a constructor")
      }
      result setType restpe // @M: normalization is done during erasure
    }

    def typedTypeConstructor(tree: Tree): Tree = typedTypeConstructor(tree, NOmode)

    def computeType(tree: Tree, pt: Type): Type = {
      val tree1 = typed(tree, pt)
      transformed(tree) = tree1
      packedType(tree1, context.owner)
    }

    def transformedOrTyped(tree: Tree, pt: Type): Tree = transformed.get(tree) match {
      case Some(tree1) => transformed -= tree; tree1
      case None => typed(tree, pt)
    }

    def findManifest(tp: Type, full: Boolean) = atPhase(currentRun.typerPhase) {
      inferImplicit(
        EmptyTree, 
        appliedType((if (full) FullManifestClass else PartialManifestClass).typeConstructor, List(tp)),
        true, false, context)
    }

    def getManifestTree(pos: Position, tp: Type, full: Boolean): Tree = {
      val manifestOpt = findManifest(tp, false)
      if (manifestOpt.tree.isEmpty) {
        error(pos, "cannot find "+(if (full) "" else "class ")+"manifest for element type "+tp)
        Literal(Constant(null))
      } else {
        manifestOpt.tree
      }
    }
/*
    def convertToTypeTree(tree: Tree): Tree = tree match {
      case TypeTree() => tree
      case _ => TypeTree(tree.tpe)
    }
*/
  }
}
