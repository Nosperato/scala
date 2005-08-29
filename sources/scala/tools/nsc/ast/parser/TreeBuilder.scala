/* NSC -- new scala compiler
 * Copyright 2005 LAMP/EPFL
 * @author  Martin Odersky
 */
// $Id$
package scala.tools.nsc.ast.parser;

import symtab.Flags._;
import util.ListBuffer;

abstract class TreeBuilder {

  val global: Global;
  import global._;
  import posAssigner.atPos;

  def freshName(prefix: String): Name;

  def freshName(): Name = freshName("x$");

  private object patvarTransformer extends Transformer {
    override def transform(tree: Tree): Tree = tree match {
      case Ident(name) if (treeInfo.isVariableName(name) && name != nme.WILDCARD) => 
	atPos(tree.pos)(Bind(name, Ident(nme.WILDCARD)))
      case Typed(id @ Ident(name), tpt) if (treeInfo.isVariableName(name) && name != nme.WILDCARD) => 
	Bind(name, atPos(tree.pos)(Typed(Ident(nme.WILDCARD), tpt))) setPos id.pos
      case Apply(fn @ Apply(_, _), args) => 
	copy.Apply(tree, transform(fn), transformTrees(args))
      case Apply(fn, args) => 
	copy.Apply(tree, fn, transformTrees(args))
      case Typed(expr, tpt) =>
	copy.Typed(tree, transform(expr), tpt)
      case Bind(name, body) =>
	copy.Bind(tree, name, transform(body))
      case Sequence(_) | Alternative(_) | Star(_) =>
	super.transform(tree)
      case _ =>
	tree
    }
  } 
 
  /** Traverse pattern and collect all variable names in buffer */ 
  private object getvarTraverser extends Traverser {
    val buf = new ListBuffer[Name];
    def init: Traverser = { buf.clear; this }
    override def traverse(tree: Tree): unit = tree match {
      case Bind(name, tpe) => 
	if ((name != nme.WILDCARD) && (buf.elements forall (name !=))) buf += name; 
	traverse(tpe)
      case _ => super.traverse(tree)
    }
  }

  /** Returns list of all pattern variables without duplicates */
  private def getVariables(tree: Tree): List[Name] = {
    getvarTraverser.init.traverse(tree);
    getvarTraverser.buf.toList
  }

  private def mkTuple(trees: List[Tree]): Tree = trees match {
    case List() => Literal(())
    case List(tree) => tree
    case _ => Apply(Select(Ident(nme.scala), newTermName("Tuple" + trees.length)), trees)
  }

  /** If tree is a variable pattern, return Some("its name and type").
   *  Otherwise return none */
  private def matchVarPattern(tree: Tree): Option[Pair[Name, Tree]] = tree match {
    case Ident(name) => Some(Pair(name, TypeTree()))
    case Bind(name, Ident(nme.WILDCARD)) => Some(Pair(name, TypeTree()))
    case Typed(Ident(name), tpt) => Some(Pair(name, tpt))
    case Bind(name, Typed(Ident(nme.WILDCARD), tpt)) => Some(Pair(name, tpt))
    case _ => None
  }

  /** Create tree representing (unencoded) binary operation expression or pattern. */
  def makeBinop(isExpr: boolean, left: Tree, op: Name, right: Tree): Tree = {
    if (isExpr) {
      if (treeInfo.isLeftAssoc(op)) {
	Apply(Select(left, op.encode), List(right))
      } else {
	val x = freshName();
	Block(
	  List(ValDef(SYNTHETIC, x, TypeTree(), left)),
	  Apply(Select(right, op.encode), List(Ident(x))))
      }
    } else {
      Apply(Ident(op.encode.toTypeName), List(left, right))
    }
  }

  /** Create tree representing an object creation <new parents { stats }> */
  def makeNew(parents: List[Tree], stats: List[Tree], argss: List[List[Tree]]): Tree =
    if (parents.tail.isEmpty && stats.isEmpty)
      New(parents.head, argss)
    else {
      val x = nme.ANON_CLASS_NAME.toTypeName;
      Block(
        List(ClassDef(
          FINAL | SYNTHETIC, x, List(), TypeTree(), 
          Template(parents, List(List()), argss, stats))),
	New(Ident(x), List(List())))
    }

  /** Create a tree represeting an assignment <lhs = rhs> */
  def makeAssign(lhs: Tree, rhs: Tree): Tree = lhs match {
    case Apply(fn, args) => Apply(Select(fn, nme.update), args ::: List(rhs))
    case _ => Assign(lhs, rhs)
  }

  /** A type tree corresponding to (possibly unary) intersection type */
  def makeIntersectionTypeTree(tps: List[Tree]): Tree = {
    if (tps.tail.isEmpty) tps.head else CompoundTypeTree(Template(tps, List()))
  }

  /** Create tree representing a while loop */
  def makeWhile(lname: Name, cond: Tree, body: Tree): Tree = {
    val continu = Apply(Ident(lname), List());
    val rhs = If(cond, Block(List(body), continu), Literal(()));
    LabelDef(lname, Nil, rhs)
  }
  
  /** Create tree representing a do-while loop */
  def makeDoWhile(lname: Name, body: Tree, cond: Tree): Tree = {
    val continu = Apply(Ident(lname), List());
    val rhs = Block(List(body), If(cond, continu, Literal(())));
    LabelDef(lname, Nil, rhs)
  }
  
  /** Create block of statements `stats'  */
  def makeBlock(stats: List[Tree]): Tree = {
    if (stats.isEmpty) Literal(())
    else if (!stats.last.isTerm) Block(stats, Literal(()));
    else if (stats.length == 1) stats(0)
    else Block(stats.init, stats.last)
  }
  
  /** Create tree for for-comprehension generator <val pat0 <- rhs0> */
  def makeGenerator(pat: Tree, rhs: Tree): Tree = {
    val pat1 = patvarTransformer.transform(pat);
    val rhs1 = matchVarPattern(pat1) match {
      case Some(_) => 
	rhs
      case None =>
	Apply(
	  Select(rhs, nme.filter),
	  List(makeVisitor(List(
	    CaseDef(pat1.duplicate, EmptyTree, Literal(true)),
	    CaseDef(Ident(nme.WILDCARD), EmptyTree, Literal(false))))))
    }
    CaseDef(pat1, EmptyTree, rhs1)
  }

  /** Create tree for for-comprehension <for (enums) do body> or
  *   <for (enums) yield body> where mapName and flatMapName are chosen
  *  corresponding to whether this is a for-do or a for-yield.
  */
  private def makeFor(mapName: Name, flatMapName: Name, enums: List[Tree], body: Tree): Tree = {

    def makeCont(pat: Tree, body: Tree): Tree = matchVarPattern(pat) match {
      case Some(Pair(name, tpt)) =>
        Function(List(ValDef(PARAM, name, tpt, EmptyTree)), body)
      case None =>
	makeVisitor(List(CaseDef(pat, EmptyTree, body)))
    }

    def makeBind(meth: Name, qual: Tree, pat: Tree, body: Tree): Tree = 
      Apply(Select(qual, meth), List(makeCont(pat, body)));

    atPos(enums.head.pos) {
      enums match {
	case CaseDef(pat, g, rhs) :: Nil =>
          makeBind(mapName, rhs, pat, body)
	case CaseDef(pat, g, rhs) :: (rest @ (CaseDef(_, _, _) :: _)) =>
          makeBind(flatMapName, rhs, pat, makeFor(mapName, flatMapName, rest, body))
	case CaseDef(pat, g, rhs) :: test :: rest =>
	  makeFor(mapName, flatMapName,
		  CaseDef(pat, g, makeBind(nme.filter, rhs, pat.duplicate, test)) :: rest, 
		  body)
      }
    }
  }

  /** Create tree for for-do comprehension <for (enums) body> */
  def makeFor(enums: List[Tree], body: Tree): Tree = 
    makeFor(nme.foreach, nme.foreach, enums, body);
    
  /** Create tree for for-yield comprehension <for (enums) yield body> */
  def makeForYield(enums: List[Tree], body: Tree): Tree =
    makeFor(nme.map, nme.flatMap, enums, body);
  
  /** Create tree for a pattern alternative */
  def makeAlternative(ts: List[Tree]): Tree = {
    def alternatives(t: Tree): List[Tree] = t match {
      case Alternative(ts) => ts
      case _ => List(t)
    }
    Alternative(for (val t <- ts; val a <- alternatives(t)) yield a)
  }

  /** Create tree for a pattern sequence */
  def makeSequence(ts: List[Tree]): Tree = {
    def elements(t: Tree): List[Tree] = t match {
      case Sequence(ts) => ts
      case _ => List(t)
    }
    Sequence(for (val t <- ts; val e <- elements(t)) yield e)
  }

  /** Create tree for the p+ regex pattern, becomes p p*  */
  def makePlus(p: Tree): Tree = 
    makeSequence(List(p, Star(p.duplicate)));

  /** Create tree for the p? regex pattern, becomes (p| )         */
  def makeOpt(p: Tree): Tree =
    makeAlternative(List(p, Sequence(List())));

  /** Create visitor <x => x match cases> */
  def makeVisitor(cases: List[CaseDef]): Tree = {
    val x = freshName();
    Function(List(ValDef(PARAM | SYNTHETIC, x, TypeTree(), EmptyTree)), Match(Ident(x), cases))
  }

  /** Create tree for case definition <case pat if guard => rhs> */
  def makeCaseDef(pat: Tree, guard: Tree, rhs: Tree): CaseDef = {
    CaseDef(patvarTransformer.transform(pat), guard, rhs);
  }

  /** Create tree for pattern definition <mods val pat0 = rhs> */
  def makePatDef(mods: int, pat: Tree, rhs: Tree): List[Tree] = matchVarPattern(pat) match {
    case Some(Pair(name, tpt)) =>
      List(ValDef(mods, name, tpt, rhs))

    case None =>
      //  in case there are no variables in pattern
      //  val p = e  ==>  e.match (case p => ())
      //
      //  in case there is exactly one variable in pattern
      //  val x_1 = e.match (case p => (x_1))
      //
      //  in case there are more variables in pattern
      //  val p = e  ==>  private synthetic val t$ = e.match (case p => (x_1, ..., x_N))
      //                  val x_1 = t$._1
      //                  ...
      //                  val x_N = t$._N
      val pat1 = patvarTransformer.transform(pat);
      val vars = getVariables(pat1);
      val matchExpr = atPos(pat1.pos){
        Match(rhs, List(CaseDef(pat1, EmptyTree, mkTuple(vars map Ident))))
      }
      vars match {
	case List() => 
	  List(matchExpr)
	case List(vname) => 
	  List(ValDef(mods, vname, TypeTree(), matchExpr))
	case _ => 
	  val tmp = freshName();
	  val firstDef = ValDef(PRIVATE | LOCAL | SYNTHETIC, tmp, TypeTree(), matchExpr);
	  var cnt = 0;
	  val restDefs = for (val v <- vars) yield {
	    cnt = cnt + 1;
	    ValDef(mods, v, TypeTree(), Select(Ident(tmp), newTermName("_" + cnt)))
	  }
	  firstDef :: restDefs
      }
  }

  /** Create a tree representing a function type */
  def makeFunctionTypeTree(argtpes: List[Tree], restpe: Tree): Tree = 
    AppliedTypeTree(
      Select(Ident(nme.scala), newTypeName("Function" + argtpes.length)),
      argtpes ::: List(restpe));

  /** Append implicit view section if for `implicitViews' if nonempty */
  def addImplicitViews(owner: Name, vparamss: List[List[ValDef]], implicitViews: List[Tree]): List[List[ValDef]] = {
    val mods = if (owner.isTypeName) PARAMACCESSOR | LOCAL | PRIVATE else PARAM;
    def makeViewParam(tpt: Tree) = ValDef(mods | IMPLICIT, freshName("view$"), tpt, EmptyTree);
    if (implicitViews.isEmpty) vparamss
    else vparamss ::: List(implicitViews map makeViewParam)
  }

  /** Create a tree representing a packaging */
  def makePackaging(pkg: Tree, stats: List[Tree]): PackageDef = pkg match {
    case Ident(name) => 
      PackageDef(name, stats)
    case Select(qual, name) => 
      makePackaging(qual, List(PackageDef(name, stats)))
  }
}
