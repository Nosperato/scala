/* NSC -- new scala compiler
 * Copyright 2005 LAMP/EPFL
 * @author  Martin Odersky
 */
// $Id$
package scala.tools.nsc.typechecker;

import symtab.RunId
import symtab.Flags._
import scala.tools.nsc.util.Position

trait Contexts requires Analyzer {
  import global._

  val NoContext = new Context {
    override def implicitss: List[List[ImplicitInfo]] = List()
  }
  NoContext.enclClass = NoContext
  NoContext.enclMethod = NoContext

  private val startContext = NoContext.make(
    Template(List(), List()) setSymbol NoSymbol setType NoType, 
    definitions.RootClass, 
    definitions.RootClass.info.decls)

  def rootContext(unit: CompilationUnit): Context = rootContext(unit, EmptyTree, false)

  def rootContext(unit: CompilationUnit, tree: Tree, erasedTypes: boolean): Context = {
    import definitions._
    var sc = startContext
    def addImport(pkg: Symbol): unit = {
      assert(pkg != null)
      val qual = gen.mkAttributedStableRef(pkg)
      sc = sc.makeNewImport(
        Import(qual, List(Pair(nme.WILDCARD, null)))
        .setSymbol(NoSymbol.newImport(Position.NOPOS).setInfo(ImportType(qual)))
        .setType(NoType))
      sc.depth = sc.depth + 1
    }
    if (!settings.noimports.value) {
      assert(isDefinitionsInitialized)
      addImport(JavaLangPackage)
      assert(ScalaPackage != null, "Scala package is null")
      addImport(ScalaPackage)
      if (!settings.nopredefs.value/* || unit.source.file.name != "Predef.scala"*/)
        addImport(PredefModule)
    }
    val c = sc.make(unit, tree, sc.owner, sc.scope, sc.imports)
    c.reportAmbiguousErrors = !erasedTypes
    c.reportGeneralErrors = !erasedTypes
    c.implicitsEnabled = !erasedTypes
    c
  }

  def resetContexts: unit = {
    var sc = startContext
    while (sc != NoContext) {
      sc.tree match {
	case Import(qual, _) => qual.tpe = singleType(qual.symbol.owner.thisType, qual.symbol)
	case _ =>
      }
      sc = sc.outer
    }
  }

  class Context {
    var unit: CompilationUnit = _
    var tree: Tree = _;                     // Tree associated with this context
    var owner: Symbol = NoSymbol;                  // The current owner
    var scope: Scope = _;                   // The current scope
    var outer: Context = _;                 // The next outer context
    var enclClass: Context = _;             // The next outer context whose tree is a 
                                            // template or package definition 
    var enclMethod: Context = _;            // The next outer context whose tree is a method
    var variance: int = _;                  // Variance relative to enclosing class.
    private var _undetparams: List[Symbol] = List(); // Undetermined type parameters
    var depth: int = 0
    var imports: List[ImportInfo] = List()

    var prefix: Type = NoPrefix
    var inConstructorSuffix = false;         // are we in a secondary constructor 
                                             // after the this constructor call?   
    var reportAmbiguousErrors = false
    var reportGeneralErrors = false
    var implicitsEnabled = false
    var checking = false

    var savedTypeBounds: List[Pair[Symbol, Type]] = List()

    def undetparams = _undetparams
    def undetparams_=(ps: List[Symbol]) = {
      //System.out.println("undetparams = " + ps);//debug
      _undetparams = ps
    }

    def make(unit: CompilationUnit, tree: Tree, owner: Symbol, scope: Scope, imports: List[ImportInfo]): Context = {
      val c = new Context
      c.unit = unit
      c.tree = tree
      c.owner = owner
      c.scope = scope
      tree match {
        case Template(_, _) | PackageDef(_, _) => 
	  c.enclClass = c; 
	  c.prefix = skolemizedThisType(this.tree, this.prefix, c.owner)
          c.inConstructorSuffix = false
	case _ =>
	  c.enclClass = this.enclClass
          c.prefix = if (c.owner != this.owner && c.owner.isTerm) NoPrefix else this.prefix
          c.inConstructorSuffix = this.inConstructorSuffix
      }
      tree match {
        case DefDef(_, _, _, _, _, _) =>
          c.enclMethod = c
        case _ =>
          c.enclMethod = this.enclMethod
      }
      c.variance = this.variance
      c.depth = if (scope == this.scope) this.depth else this.depth + 1
      c.imports = imports
      c.reportAmbiguousErrors = this.reportAmbiguousErrors
      c.reportGeneralErrors = this.reportGeneralErrors
      c.implicitsEnabled = this.implicitsEnabled
      c.checking = this.checking
      c.outer = this
      c
    }

    def make(unit: CompilationUnit): Context = {
      val c = make(unit, EmptyTree, owner, scope, imports)
      c.reportAmbiguousErrors = true
      c.reportGeneralErrors = true
      c.implicitsEnabled = true
      c
    }

    def makeNewImport(imp: Import): Context =
      make(unit, imp, owner, scope, new ImportInfo(imp, depth) :: imports)
      
    def make(tree: Tree, owner: Symbol, scope: Scope): Context = 
      make(unit, tree, owner, scope, imports)

    def makeNewScope(tree: Tree, owner: Symbol): Context = 
      make(tree, owner, new Scope(scope))

    def make(tree: Tree, owner: Symbol): Context = 
      make(tree, owner, scope)

    def make(tree: Tree): Context = 
      make(tree, owner)

    def makeImplicit(reportAmbiguousErrors: boolean) = {
      val c = make(tree)
      c.reportAmbiguousErrors = reportAmbiguousErrors
      c.reportGeneralErrors = false
      c.implicitsEnabled = false
      c
    }

    def makeConstructorContext = {
      var baseContext = enclClass.outer
      //todo: find out why we need next line
      while (baseContext.tree.isInstanceOf[Template]) baseContext = baseContext.outer
      val argContext = baseContext.makeNewScope(tree, owner)
      for (val sym <- scope.toList) argContext.scope enter sym
      argContext
    }

    def makeConstructorSuffixContext = {
      val c = make(tree)
      c.inConstructorSuffix = true
      c
    }

    def skolemizedThisType(encl: Tree, pre: Type, clazz: Symbol): Type = if (settings.Xgadt.value) {
      encl match {
        case ClassDef(_, _, tparamdefs, _, _) =>
          System.out.println("sktt " + clazz)
          if (!tparamdefs.isEmpty || pre.isInstanceOf[SingleType]) {
            val tparams = clazz.unsafeTypeParams
            val tskolems = tparamdefs map (.symbol)
            System.out.println("sktt2 " + tparams + " " + tskolems)
            val self = clazz.newThisSkolem setInfo clazz.typeOfThis.substSym(tparams, tskolems)
            singleType(pre, self)
          } else clazz.thisType
        case _ =>
          clazz.thisType
      }
    } else clazz.thisType

    def error(pos: int, msg: String): unit = 
      if (reportGeneralErrors) 
        unit.error(pos, if (checking) "**** ERROR DURING INTERNAL CHECKING ****\n" + msg else msg)
      else 
        throw new TypeError(msg)

    def ambiguousError(pos: int, pre: Type, sym1: Symbol, sym2: Symbol, rest: String): unit = {
      val msg = 
	("ambiguous reference to overloaded definition,\n" +
	 "both " + sym1 + sym1.locationString + " of type " + pre.memberType(sym1) +
	 "\nand  " + sym2 + sym2.locationString + " of type " + pre.memberType(sym2) + 
         "\nmatch " + rest)
      if (reportAmbiguousErrors) {
        if (!pre.isErroneous && !sym1.isErroneous && !sym2.isErroneous)
          unit.error(pos, msg)
      } else throw new TypeError(msg)
    }

    def outerContext(clazz: Symbol): Context = {
      var c = this
      while (c != NoContext && c.owner != clazz) c = c.outer.enclClass
      c
    }

    def isLocal(): boolean = tree match {
      case Block(_,_) => true
      case PackageDef(_, _) => false
      case EmptyTree => false
      case _ => outer.isLocal()
    }

    def nextEnclosing(p: Context => boolean): Context = 
      if (this == NoContext || p(this)) this else outer.nextEnclosing(p)

    override def toString(): String = {
      if (this == NoContext) "NoContext"
      else owner.toString() + " @ " + tree.getClass() + " " + tree.toString() + ", scope = " + scope.hashCode() + " " + scope.toList + "\n:: " + outer.toString()
    }

    /** Is `sym' accessible as a member of tree `site' with type `pre' in current context?
     */
    def isAccessible(sym: Symbol, pre: Type, superAccess: boolean): boolean = {

      /** Are we inside definition of `owner'? */
      def accessWithin(owner: Symbol): boolean = {
	var c = this
	while (c != NoContext && c.owner != owner) {
	  if (c.outer == null) assert(false, "accessWithin(" + owner + ") " + c);//debug
	  if (c.outer.enclClass == null) assert(false, "accessWithin(" + owner + ") " + c);//debug
	  c = c.outer.enclClass
	}
	c != NoContext
      }

      /** Is `clazz' a subclass of an enclosing class? */
      def isSubClassOfEnclosing(clazz: Symbol): boolean = {
	var c = this.enclClass
	while (c != NoContext && !clazz.isSubClass(c.owner)) c = c.outer.enclClass
	c != NoContext
      }

      ( pre == NoPrefix
        ||
        (!sym.hasFlag(PRIVATE | PROTECTED))
        ||
        accessWithin(sym.owner) && (!sym.hasFlag(LOCAL) || pre =:= sym.owner.thisType)
        ||
        (!sym.hasFlag(PRIVATE) &&
         (superAccess ||
	  (pre.widen.symbol.isSubClass(sym.owner) && isSubClassOfEnclosing(pre.widen.symbol))))
      ) && (
        sym.privateWithin == NoSymbol 
        ||
        phase.erasedTypes || accessWithin(sym.privateWithin)
      )
    }

    def pushTypeBounds(sym: Symbol): unit = { 
      savedTypeBounds = Pair(sym, sym.info) :: savedTypeBounds
    }

    def restoreTypeBounds: unit = { 
      for (val Pair(sym, info) <- savedTypeBounds) {
        if (settings.debug.value) log("resetting " + sym + " to " + info); 
        sym.setInfo(info)
      }
      savedTypeBounds = List()
    }

    private var implicitsCache: List[List[ImplicitInfo]] = null
    private var implicitsRunId: RunId = NoRunId

    private def collectImplicits(syms: List[Symbol], pre: Type): List[ImplicitInfo] = 
      for (val sym <- syms; sym.hasFlag(IMPLICIT) && isAccessible(sym, pre, false))
      yield new ImplicitInfo(sym.name, pre, sym)

    private def collectImplicitImports(imp: ImportInfo): List[ImplicitInfo] = {
      val pre = imp.qual.tpe
      def collect(sels: List[Pair[Name, Name]]): List[ImplicitInfo] = sels match {
	case List() => List()
	case List(Pair(nme.WILDCARD, _)) => collectImplicits(pre.implicitMembers, pre)
	case Pair(from, to) :: sels1 => 
	  var impls = collect(sels1) filter (info => info.name != from)
	  if (to != nme.WILDCARD) {
	    val sym = imp.importedSymbol(to)
	    if (sym.hasFlag(IMPLICIT)) impls = new ImplicitInfo(to, pre, sym) :: impls
	  }
	  impls
      }
      if (settings.debug.value) log("collect implicit imports " + imp + "=" + collect(imp.tree.selectors));//debug
      collect(imp.tree.selectors)
    }

    def implicitss: List[List[ImplicitInfo]] = {
      if (implicitsRunId != currentRunId) {
	implicitsRunId = currentRunId
	val newImplicits: List[ImplicitInfo] = 
	  if (owner != outer.owner && owner.isClass && !owner.isPackageClass) {
            if (!owner.isInitialized) return outer.implicitss
	    if (settings.debug.value) log("collect member implicits " + owner + ", implicit members = " + owner.thisType.implicitMembers);//debug
	    collectImplicits(owner.thisType.implicitMembers, owner.thisType)
	  } else if (scope != outer.scope && !owner.isPackageClass) {
	    if (settings.debug.value) log("collect local implicits " + scope.toList);//debug
	    collectImplicits(scope.toList, NoPrefix)
	  } else if (imports != outer.imports) {
	    assert(imports.tail == outer.imports)
	    collectImplicitImports(imports.head)
	  } else List()
	implicitsCache = if (newImplicits.isEmpty) outer.implicitss
			 else newImplicits :: outer.implicitss
      }
      implicitsCache
    }
  }
    
  class ImportInfo(val tree: Import, val depth: int) {

    /** The prefix expression */ 
    def qual: Tree = tree.symbol.info match {
      case ImportType(expr) => expr
      case _ => throw new FatalError("symbol " + tree.symbol + " has bad type: " + tree.symbol.info);//debug
    }
    
    /** Is name imported explicitly, not via wildcard? */
    def isExplicitImport(name: Name): boolean =
      tree.selectors exists (._2.==(name.toTermName))

    /** The symbol with name `name' imported from import clause `tree'.
     */
    def importedSymbol(name: Name): Symbol = {
      var result: Symbol = NoSymbol
      var renamed = false
      var selectors = tree.selectors
      while (selectors != Nil && result == NoSymbol) {
        if (selectors.head._2 == name.toTermName)
	  result = qual.tpe.member(
            if (name.isTypeName) selectors.head._1.toTypeName else selectors.head._1)
        else if (selectors.head._1 == name.toTermName)
          renamed = true
        else if (selectors.head._1 == nme.WILDCARD && !renamed)
          result = qual.tpe.member(name)
        selectors = selectors.tail
      }
      result
    }

    override def toString() = tree.toString()
  }

  class ImplicitInfo(val name: Name, pre: Type, val sym: Symbol) {
    private var tpeCache: Type = null
    def tpe: Type = {
      if (tpeCache == null) tpeCache = pre.memberType(sym)
      tpeCache
    }
  }

  val NoImplicitInfo = new ImplicitInfo(null, null, null)

  case class ImportType(expr: Tree) extends Type
}
