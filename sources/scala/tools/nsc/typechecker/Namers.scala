/* NSC -- new scala compiler
 * Copyright 2005 LAMP/EPFL
 * @author  Martin Odersky
 */
// $Id$
package scala.tools.nsc.typechecker;

import scala.tools.util.Position;
import symtab.Flags;
import symtab.Flags._;

/** Methods to create symbols and to enter them into scopes. */
trait Namers: Analyzer { 
  import global._;

  class NamerPhase(prev: Phase) extends StdPhase(prev) {
    def name = "namer";
    val global: Namers.this.global.type = Namers.this.global;
    def apply(unit: CompilationUnit): unit =
      new Namer(startContext.make(unit)).enterSym(unit.body);
  }
  
  class Namer(val context: Context) {

    private def isTemplateContext(context: Context): boolean = context.tree match {
      case Template(_, _) => true
      case Import(_, _) => isTemplateContext(context.outer)
      case _ => false
    }

    private var innerNamerCache: Namer = null;
    def innerNamer: Namer = {
      if (innerNamerCache == null) 
        innerNamerCache = if (!isTemplateContext(context)) this
                          else new Namer(context.make(context.tree, context.owner, new Scope()));
      innerNamerCache
    }

    private def doubleDefError(pos: int, sym: Symbol): unit = 
      context.error(pos,
        sym.name.toString() + " is already defined as " + 
        (if (sym.hasFlag(CASE)) "case class " + sym.name else sym.toString()));

    private def updatePosFlags(sym: Symbol, pos: int, mods: int): Symbol = {
      if (settings.debug.value) System.out.println("overwriting " + sym);
      sym.pos = pos;
      val oldflags = sym.flags & (INITIALIZED | LOCKED);
      val newflags = mods & ~(INITIALIZED | LOCKED);
      sym.flags = oldflags | newflags;
      if (sym.isModule)
        updatePosFlags(sym.moduleClass, pos, (mods & ModuleToClassFlags) | MODULE | FINAL);
      sym
    }

    def enterInScope(sym: Symbol): Symbol = { 
      if (!(sym.isMethod && sym.owner.isClass)) {
	val prev = context.scope.lookupEntry(sym.name);
	if (prev != null && prev.owner == context.scope && !prev.sym.isMethod)
	  doubleDefError(sym.pos, prev.sym);
      }
      context.scope enter sym; 
      sym 
    }

    private def enterPackageSymbol(pos: int, name: Name): Symbol = {
      val p: Symbol = context.scope.lookup(name);
      if (p.isPackage && context.scope == p.owner.info.decls) {
        p.pos = pos; p.moduleClass.pos = pos; p
      } else {
        val pkg = context.owner.newPackage(pos, name);
        pkg.moduleClass.setInfo(new PackageClassInfoType(new Scope(), pkg));
        pkg.setInfo(pkg.moduleClass.tpe);
        enterInScope(pkg)
      }
    }

    private def enterClassSymbol(pos: int, mods: int, name: Name): Symbol = {
      val c: Symbol = context.scope.lookup(name);
      if (c.isType && c.isExternal && context.scope == c.owner.info.decls) {
        updatePosFlags(c, pos, mods)
      } else {
	enterInScope(context.owner.newClass(pos, name).setFlag(mods))
      }
    }

    private def enterModuleSymbol(pos: int, mods: int, name: Name): Symbol = {
      val m: Symbol = context.scope.lookup(name);
      if (m.isModule && !m.isPackage && m.isExternal && (context.scope == m.owner.info.decls)) {
        updatePosFlags(m, pos, mods)
      } else {
        val newm = context.owner.newModule(pos, name);
        newm.setFlag(mods);
        newm.moduleClass.setFlag(mods);
	enterInScope(newm)
      }
    }

    private def enterCaseFactorySymbol(pos: int, mods: int, name: Name): Symbol = {
      val m: Symbol = context.scope.lookup(name);
      if (m.isModule && !m.isPackage && m.isExternal && context.scope == m.owner.info.decls) {
        m.resetFlag(MODULE);
        updatePosFlags(m, pos, mods)
      } else {
        enterInScope(context.owner.newMethod(pos, name).setFlag(mods))
      }
    }

    def enterSyms(trees: List[Tree]): Namer =
      (this /: trees) ((namer, tree) => namer.enterSym(tree));

    def enterSym(tree: Tree): Namer = {

      def finishWith(tparams: List[AbsTypeDef]): unit = {
        if (settings.debug.value) log("entered " + tree.symbol);
	var ltype: LazyType = innerNamer.typeCompleter(tree);
        if (!tparams.isEmpty) {
	  new Namer(context.makeNewScope(tree, tree.symbol)).enterSyms(tparams);
	  ltype = new LazyPolyType(tparams map (.symbol), ltype);
	}
	tree.symbol.setInfo(ltype);
      }
      def finish = finishWith(List());

      if (tree.symbol == NoSymbol) {
	val owner = context.owner;
	tree match {
	  case PackageDef(name, stats) =>
	    tree.symbol = enterPackageSymbol(tree.pos, name);
	    val namer = new Namer(
	      context.make(tree, tree.symbol.moduleClass, tree.symbol.info.decls));
	    namer.enterSyms(stats);
	  case ClassDef(mods, name, tparams, _, _) =>
	    if ((mods & (CASE | ABSTRACT)) == CASE) { // enter case factory method.
	      tree.symbol = enterCaseFactorySymbol(
		tree.pos, mods & AccessFlags | CASE, name.toTermName)
		setInfo innerNamer.caseFactoryCompleter(tree)
	    }
	    tree.symbol = enterClassSymbol(tree.pos, mods, name);
	    if (settings.debug.value) System.out.println("entered: " + tree.symbol + flagsToString(tree.symbol.flags));//debug
	    finishWith(tparams)
	  case ModuleDef(mods, name, _) => 
	    tree.symbol = enterModuleSymbol(tree.pos, mods | MODULE | FINAL, name);
	    tree.symbol.moduleClass.setInfo(innerNamer.typeCompleter(tree));
	    finish
	  case ValDef(mods, name, tp, rhs) => 
            if (context.owner.isClass & (mods & PRIVATE) == 0) {
	      val accmods = ACCESSOR | (if ((mods & MUTABLE) != 0) mods & ~MUTABLE 
                                        else mods | STABLE);
	      val getter = owner.newMethod(tree.pos, name)
	        .setFlag(accmods).setInfo(innerNamer.getterTypeCompleter(tree));
	      enterInScope(getter);
	      if ((mods & MUTABLE) != 0) {
	        val setter = owner.newMethod(tree.pos, nme.SETTER_NAME(name))
		  .setFlag(accmods & ~STABLE).setInfo(innerNamer.setterTypeCompleter(tree));
	        enterInScope(setter)
	      }
	      tree.symbol = 
		if ((mods & DEFERRED) == 0)
		  enterInScope(
		    owner.newValue(tree.pos, nme.LOCAL_NAME(name))
 	              .setFlag(mods & FieldFlags | PRIVATE | LOCAL)
		      .setInfo(innerNamer.typeCompleter(tree)))
		else getter;
            } else {
              tree.symbol = 
                enterInScope(owner.newValue(tree.pos, name).setFlag(mods));
	      finish
            }
	  case DefDef(mods, nme.CONSTRUCTOR, tparams, vparams, tp, rhs) =>
	    tree.symbol = enterInScope(owner.newConstructor(tree.pos))
	      .setFlag(mods | owner.getFlag(ConstrFlags));
	    finishWith(tparams)
	  case DefDef(mods, name, tparams, _, _, _) =>
	    tree.symbol = enterInScope(owner.newMethod(tree.pos, name)).setFlag(mods);
	    finishWith(tparams)
	  case AbsTypeDef(mods, name, _, _) =>
	    tree.symbol = enterInScope(owner.newAbstractType(tree.pos, name)).setFlag(mods);
	    finish
	  case AliasTypeDef(mods, name, tparams, _) =>
	    tree.symbol = enterInScope(owner.newAliasType(tree.pos, name)).setFlag(mods);
	    finishWith(tparams)
	  case Attributed(_, defn) =>
	    enterSym(defn)
	  case DocDef(_, defn) =>
	    enterSym(defn) 
	  case imp @ Import(_, _) =>
	    tree.symbol = NoSymbol.newImport(tree.pos).setInfo(innerNamer.typeCompleter(tree));
	    return new Namer(context.makeNewImport(imp));
	  case _ =>
	}
      }
      this
    }

// --- Lazy Type Assignment --------------------------------------------------

    val typer = newTyper(context);

    def typeCompleter(tree: Tree) = new TypeCompleter(tree) {
      override def complete(sym: Symbol): unit = {
        if (settings.debug.value) log("defining " + sym);
        sym.setInfo(typeSig(tree));
        if (settings.debug.value) log("defined " + sym);
        validate(sym);
      }
    }

    def getterTypeCompleter(tree: Tree) = new TypeCompleter(tree) {
      override def complete(sym: Symbol): unit = {
        if (settings.debug.value) log("defining " + sym);
        sym.setInfo(PolyType(List(), typeSig(tree)));
        if (settings.debug.value) log("defined " + sym);
        validate(sym);
      }
    }

    def setterTypeCompleter(tree: Tree) = new TypeCompleter(tree) {
      override def complete(sym: Symbol): unit = {
        if (settings.debug.value) log("defining " + sym);
        sym.setInfo(MethodType(List(typeSig(tree)), definitions.UnitClass.tpe));
        if (settings.debug.value) log("defined " + sym);
        validate(sym);
      }
    }

    def selfTypeCompleter(tree: Tree) = new TypeCompleter(tree) {
      override def complete(sym: Symbol): unit = {
        sym.setInfo(typer.transformType(tree).tpe);
      }
    }

    def caseFactoryCompleter(tree: Tree) = new TypeCompleter(tree) {
      override def complete(sym: Symbol): unit = {
	val clazz = tree.symbol;
	var tpe = clazz.primaryConstructor.tpe;
	val tparams = clazz.unsafeTypeParams;
	if (!tparams.isEmpty) tpe = PolyType(tparams, tpe).cloneInfo(sym);
	sym.setInfo(tpe);
      }
    }
    
    private def deconstIfNotFinal(sym: Symbol, tpe: Type): Type =
      if (sym.isVariable || !sym.isFinal) tpe.deconst else tpe;
    
    def enterValueParams(owner: Symbol, vparamss: List[List[ValDef]]): List[List[Symbol]] = {
      def enterValueParam(param: ValDef): Symbol = {
	param.symbol = owner.newValueParameter(param.pos, param.name)
	  .setInfo(typeCompleter(param)).setFlag(param.mods & IMPLICIT);
        context.scope enter param.symbol;
        param.symbol
      }
      vparamss.map(.map(enterValueParam))
    }

    /** A creator for polytypes. If tparams is empty, simply returns result type */
    private def makePolyType(tparams: List[Symbol], tpe: Type): Type = 
      if (tparams.isEmpty) tpe 
      else 
	PolyType(tparams, tpe match {
	  case PolyType(List(), tpe1) => tpe1
	  case _ => tpe
	});

    private def templateSig(templ: Template): Type = {
      val clazz = context.owner;
      val parents = typer.parentTypes(templ) map (.tpe);
      val decls = new Scope();
      new Namer(context.make(templ, clazz, decls)).enterSyms(templ.body);
      ClassInfoType(parents, decls, clazz)    
    }
    
    private def classSig(tparams: List[AbsTypeDef], tpt: Tree, impl: Template): Type = {
      val tparamSyms = typer.reenterTypeParams(tparams);
      if (!tpt.isEmpty)
        context.owner.typeOfThis = selfTypeCompleter(tpt);
      else tpt.tpe = NoType;
      makePolyType(tparamSyms, templateSig(impl))
    }

    private def methodSig(tparams: List[AbsTypeDef], vparamss: List[List[ValDef]], tpt: Tree, rhs: Tree): Type = {
      def checkContractive: unit = {}; //todo: complete
      val meth = context.owner;
      val tparamSyms = typer.reenterTypeParams(tparams);
      val vparamSymss = enterValueParams(meth, vparamss);
      val restype = deconstIfNotFinal(meth,
	if (tpt.isEmpty) { 
	  tpt.tpe = if (meth.name == nme.CONSTRUCTOR) context.enclClass.owner.tpe
		    else typer.transformExpr(rhs).tpe; 
	  tpt.tpe
	} else typer.transformType(tpt).tpe);
      def mkMethodType(vparams: List[Symbol], restpe: Type) = {
	val formals = vparams map (.tpe);
	if (!vparams.isEmpty && vparams.head.hasFlag(IMPLICIT))	{
          if (settings.debug.value) System.out.println("create implicit");//debug
	  checkContractive;
	  new ImplicitMethodType(formals, restpe)
	} else MethodType(formals, restpe);
      }
      makePolyType(
	tparamSyms, 
	if (vparamSymss.isEmpty) PolyType(List(), restype)
	else (vparamSymss :\ restype)(mkMethodType))
    }

    private def aliasTypeSig(tpsym: Symbol, tparams: List[AbsTypeDef], rhs: Tree): Type =
      makePolyType(typer.reenterTypeParams(tparams), typer.transformType(rhs).tpe);

    private def typeSig(tree: Tree): Type =
      try {
	val sym: Symbol = tree.symbol;
	tree match {
	  case ClassDef(_, _, tparams, tpt, impl) =>
	    new Namer(context.makeNewScope(tree, sym)).classSig(tparams, tpt, impl)

	  case ModuleDef(_, _, impl) =>
	    val clazz = sym.moduleClass;
            clazz.setInfo(new Namer(context.make(tree, clazz)).templateSig(impl));
            clazz.tpe;

	  case DefDef(_, _, tparams, vparamss, tpt, rhs) =>
	    new Namer(context.makeNewScope(tree, sym)).methodSig(tparams, vparamss, tpt, rhs)

	  case ValDef(_, _, tpt, rhs) =>
            deconstIfNotFinal(sym,
              if (tpt.isEmpty) 
                if (rhs.isEmpty) {
		  context.error(tpt.pos, "missing parameter type");
                  ErrorType
                } else { 
                  tpt.tpe = newTyper(context.make(tree, sym))
                    .transformExpr(rhs).tpe; 
                  tpt.tpe 
                }
              else typer.transformType(tpt).tpe)

	  case AliasTypeDef(_, _, tparams, rhs) =>
            new Namer(context.makeNewScope(tree, sym)).aliasTypeSig(sym, tparams, rhs)

	  case AbsTypeDef(_, _, lo, hi) =>
            TypeBounds(typer.transformType(lo).tpe, typer.transformType(hi).tpe);
          
          case Import(expr, selectors) =>
            val expr1 = typer.transformQualExpr(expr);
	    val base = expr1.tpe;
            typer.checkStable(expr1);
            def checkSelectors(selectors: List[Pair[Name, Name]]): unit = selectors match {
              case Pair(from, to) :: rest =>
                if (from != nme.WILDCARD && base != ErrorType &&
		    base.member(from) == NoSymbol && base.member(from.toTypeName) == NoSymbol)
	          context.error(tree.pos, from.decode + " is not a member of " + expr);
		if (from != nme.WILDCARD && (rest.exists (sel => sel._1 == from)))
		  context.error(tree.pos, from.decode + " is renamed twice");
	        if (to != null && to != nme.WILDCARD && (rest exists (sel => sel._2 == to)))
		  context.error(tree.pos, to.decode + " appears twice as a target of a renaming");
                checkSelectors(rest)
              case Nil => 
	    }
            ImportType(expr1)
        }
      } catch {
        case ex: TypeError =>
	  typer.reportTypeError(tree.pos, ex);
	  ErrorType
      }

    /** Check that symbol's definition is well-formed. This means:
     *   - no conflicting modifiers
     *   - `abstract' modifier only for classes
     *   - `override' modifier never for classes
     *   - `def' modifier never for parameters of case classes
     *   - declarations only in traits or abstract classes 
     */
    def validate(sym: Symbol): unit = {
      def checkNoConflict(flag1: int, flag2: int): unit =
	if (sym.hasFlag(flag1) && sym.hasFlag(flag2))
	  context.error(sym.pos,
	    if (flag1 == DEFERRED) 
	      "abstract member may not have " + Flags.flagsToString(flag2) + " modifier";
	    else 
	      "illegal combination of modifiers: " + 
	      Flags.flagsToString(flag1) + " and " + Flags.flagsToString(flag2));
      if (sym.hasFlag(IMPLICIT) && !sym.isTerm)
	context.error(sym.pos, "`implicit' modifier can be used only for values, variables and methods");
      if (sym.hasFlag(ABSTRACT) && !sym.isClass)
	context.error(sym.pos, "`abstract' modifier can be used only for classes; " + 
	  "\nit should be omitted for abstract members");
      if (sym.hasFlag(OVERRIDE | ABSOVERRIDE) && sym.isClass)
	context.error(sym.pos, "`override' modifier not allowed for classes");
      if (sym.info.symbol == definitions.FunctionClass(0) &&
	  sym.isValueParameter && sym.owner.isClass && sym.owner.hasFlag(CASE))
	context.error(sym.pos, "pass-by-name arguments not allowed for case class parameters"); 
      if ((sym.flags & DEFERRED) != 0) {
	if (!sym.isValueParameter && !sym.isTypeParameter &&
	    (!sym.owner.isClass || sym.owner.isModuleClass || sym.owner.isAnonymousClass)) {
	  context.error(sym.pos, 
	    "only classes can have declared but undefined members" + 
	    (if (!sym.isVariable) ""
	     else "\n(Note that variables need to be initialized to be defined)"));
	  sym.resetFlag(DEFERRED);
	} 
      }
      checkNoConflict(DEFERRED, PRIVATE);
      checkNoConflict(FINAL, SEALED);
      if (!sym.hasFlag(MODULE)) checkNoConflict(FINAL, PRIVATE);
      checkNoConflict(PRIVATE, PROTECTED);
      checkNoConflict(PRIVATE, OVERRIDE);
      checkNoConflict(DEFERRED, FINAL);
    }
  } 

  abstract class TypeCompleter(val tree: Tree) extends LazyType;
}

