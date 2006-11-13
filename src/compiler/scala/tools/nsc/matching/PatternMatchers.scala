/* NSC -- new Scala compiler
 * Copyright 2005-2006 LAMP/EPFL
 * @author Burak Emir
 */
// $Id$

package scala.tools.nsc.matching

import compat.StringBuilder
import scala.tools.nsc.util.Position

/** This trait ...
 *
 *  @author Burak Emir
 *  @version 1.0
 */
trait PatternMatchers requires (transform.ExplicitOuter with PatternNodes) {
  import global._
  import typer.typed
  import symtab.Flags

  // -- begin new data structure for matcher
                        /*
  abstract class Node

  class Test(var tpe: Type, var casted: Symbol) extends Node {
    var thenp: Node  = _
    var vbles = List[Symbol]()
    def bindTo(v: Symbol): this.type = { vbles = v::vbles; this }
    override def toString() = tpe.toString + "?"
  }

  class Load(var expr: Tree) extends Node {
    def tpe = expr.tpe
    var thenp: PatNodeList = Snil
  }

  case class Return(b:Tree) extends Node

  abstract class PatNodeList
  case class Snoc(sx: PatNodeList, x:Test) extends PatNodeList
  case object Snil extends PatNodeList
*/
    // -- end 

  class PatternMatcher {

    protected var optimize = true

    /** the owner of the pattern matching expression
     */
    var owner:Symbol = _

    /** the selector expression
     */
    protected var selector: Tree = _

    /** the root of the pattern node structure
     */
    protected var root: PatternNode = _

    // statistics
    var nremoved = 0
    var nsubstituted = 0

    /** the symbol of the result variable
     */
    //protected var resultVar: Symbol = _

    def defs = definitions
    var handleOuter: Tree=>Tree = _
    /** init method, also needed in subclass AlgebraicMatcher
     */
    def initialize(selector: Tree, owner: Symbol, handleOuter:Tree=>Tree): Unit = {
      this.owner = owner
      this.selector = selector
      this.handleOuter = handleOuter
      this.root = pConstrPat(selector.pos, selector.tpe.widen);

      this.root.and = pHeader(selector.pos,
                              selector.tpe.widen,
                              Ident(root.symbol).setType(root.tpe));
      //Konsole.println("resultType =  "+resultType);
      //this.optimize = this.optimize && (settings.target.value == "jvm");
    }

// ---
  /**
   *  @param pos ...
   *  @param tpe ...
   *  @param len ...
   *  @return ...
   */
  def pSequencePat(pos: PositionType, tpe: Type, len: int) = {
    //assert (tpe != null)
    val sym = newVar(FirstPos, tpe)
    val node = new SequencePat(sym, len)
    node.pos = pos
    node.tpe = tpe
    node
  }

  /**
   *  @param pos         ...
   *  @param tpe         ...
   *  @param castedRest1 ...
   *  @param minlen      ...
   *  @return            ...
   */
  def pRightIgnoringSequencePat(pos: PositionType, tpe: Type,
                                castedRest: Symbol, minlen: int) = {
    //assert (tpe != null)
    val sym = newVar(FirstPos, tpe)
    val node = new RightIgnoringSequencePat(sym, castedRest, minlen)
    node.pos = pos
    node.tpe = tpe
    node
  }

  /**
   *  @param pos ...
   *  @param tpe ...
   *  @return    ...
   */
  def pDefaultPat(pos: PositionType, tpe: Type) = {
    //assert (tpe != null)
    val node = new DefaultPat()
    node.pos = pos
    node.setType(tpe)
    node
  }
  
  def pConstrPat(pos: PositionType, tpe: Type) = {
    //assert (tpe != null)
    val node = new ConstrPat(newVar(pos, tpe))
    node.pos = pos
    node.setType(tpe)
    node
  }
  
  def pUnapplyPat(pos: PositionType, fn: Tree) = {
    val tpe = definitions.productType(definitions.optionOfProductElems(fn.tpe)) // is subtype of producttype
    val node = new UnapplyPat(newVar(pos, tpe), fn)
    node.pos = pos
    node.setType(tpe)
    node
  }

  def pConstantPat(pos: PositionType, tpe: Type, value: Any) = {
    //assert (tpe != null)
    val node = new ConstantPat(value)
    node.pos = pos
    node.setType(tpe)
    node
  }
  
  def pVariablePat(pos: PositionType, tree: Tree) = {
    //assert (tree.tpe != null)
    val node = new VariablePat(tree)
    node.pos = pos
    node.setType(tree.tpe)
    node
  }

  /**
   *  @param pos    ...
   *  @param header ...
   *  @return       ...
   */
  def pAltPat(pos: PositionType, header: Header) = {
    val node = new AltPat(header)
    node.pos = pos
    node.setType(header.getTpe())
    node
  }

  // factories

  def pHeader(pos: PositionType, tpe: Type, selector: Tree) = {
    //assert (tpe != null)
    val node = new Header(selector, null)
    node.pos = pos
    node.setType(tpe)
    node
  }

  def pBody(pos: PositionType) = {
    val node = new Body(new Array[Array[ValDef]](0),
                        new Array[Tree](0),
                        new Array[Tree](0))
    node.pos = pos
    node
  }

  /**
   *  @param pos   ...
   *  @param bound ...
   *  @param guard ...
   *  @param body  ...
   *  @return      ...
   */
  def pBody(pos: PositionType, bound: Array[ValDef], guard: Tree, body: Tree) = {
    val node =
      new Body(Array(bound), Array(guard), Array(body))
    node.pos = pos
    node
  }

  /**
   *  @param pos  ...
   *  @param name ...
   *  @param tpe  ...
   *  @return     ...
   */
  def newVar(pos: PositionType, name: Name, tpe: Type): Symbol = {
    val sym = owner.newVariable(pos, name) // careful: pos has special meaning
    sym.setInfo(tpe)
    sym
  }

  /**
   *  @param pos ...
   *  @param tpe ...
   *  @return    ...
   */
  def newVar(pos: PositionType, tpe: Type): Symbol =
    newVar(pos, cunit.fresh.newName("temp"), tpe).setFlag(Flags.SYNTHETIC)

// ---
  def squeezedBlock(vds:List[Tree], exp:Tree): Tree = {
    val tpe = exp.tpe
    class RefTraverser(sym:Symbol) extends Traverser {
      var nref = 0
      var nsafeRef = 0
      override def traverse(tree: Tree) = tree match {
	case t:Ident if t.symbol == sym => 
	  nref = nref + 1
	  if(sym.owner == currentOwner)  { // oldOwner should match currentOwner
	    nsafeRef = nsafeRef + 1
	  } /*else if(nref == 1) {
	    Console.println("sym owner: "+sym.owner+" but currentOwner = "+currentOwner)
	  }*/
	case t if nref > 1 => // abort, no story to tell
	  
	case t       => super . traverse (t)
      }
    }
    class Subst(sym:Symbol,rhs:Tree) extends Transformer {
      var stop = false
      override def transform(tree: Tree) = tree match {
	case t:Ident if t.symbol == sym => 
	  stop = true
	  rhs
	case t if stop =>
	  t
	case t       => 
	  super . transform (t)
      }
    }
    vds match {
      case Nil   => exp

      case (vd:ValDef) :: rest =>
        // recurse
        val exp1 = squeezedBlock(rest, exp)

        //Console.println("squeezedBlock for valdef "+vd)
	val sym  = vd.symbol
	val rt   = new RefTraverser(sym)
	rt.atOwner (PatternMatcher.this.owner) (rt.traverse(exp1))
	rt.nref match {
	  case 0 => 
	    nremoved = nremoved + 1
	    exp1 
	  case 1 if rt.nsafeRef == 1 => 
	    nsubstituted = nsubstituted + 1
	    new Subst(sym, vd.rhs).transform( exp1 )
	  case _ => 
            exp1 match {
	      case Block(vds2, exp2) => Block(vd::vds2, exp2)  
	      case exp2              => Block(vd::Nil,  exp2)  
            }
        }
      case x::xs => 
        squeezedBlock(xs, exp) match {
	  case Block(vds2, exp2) => Block(x::vds2, exp2) 
	  case exp2              => Block(x::Nil,  exp2) 
        }
    }
  }

// ---
    /** pretty printer
     */
    def print(): Unit =
      Console.println(root.and.print("", new StringBuilder()).toString())

    /** enters a sequence of cases into the pattern matcher
     */
    def construct(cases: List[Tree]): Unit =
      cases foreach enter

    /** enter a single case into the pattern matcher
     */
    protected def enter(caseDef: Tree): Unit = caseDef match {
      case CaseDef(pat, guard, body) =>
        val env = new CaseEnv
        val target = enter1(pat, -1, root, root.symbol, env)
        // if (target.and != null)
        //   unit.error(pat.pos, "duplicate case");
      if (null == target.and)
        target.and = pBody(caseDef.pos, env.getBoundVars(), guard, body)
      else if (target.and.isInstanceOf[Body])
        updateBody(target.and.asInstanceOf[Body], env.getBoundVars(), guard, body)
      else
        cunit.error(pat.pos, "duplicate case")
    }

    protected def updateBody(tree: Body, bound: Array[ValDef],
                             guard: Tree, body: Tree): Unit =
      if (tree.guard(tree.guard.length - 1) == EmptyTree) {
        cunit.error(body.pos, "unreachable code")
      } else {
        val bd = new Array[Array[ValDef]](tree.bound.length + 1)
        val ng = new Array[Tree](tree.guard.length + 1)
        val nb = new Array[Tree](tree.body.length + 1)
        Array.copy(tree.bound, 0, bd, 0, tree.bound.length)
        Array.copy(tree.guard, 0, ng, 0, tree.guard.length)
        Array.copy(tree.body, 0, nb, 0, tree.body.length)
        bd(bd.length - 1) = bound
        ng(ng.length - 1) = guard
        nb(nb.length - 1) = body
        tree.bound = bd
        tree.guard = ng
        tree.body = nb
      }

    /** returns the child patterns of a pattern 
     */
    protected def patternArgs(tree: Tree): List[Tree] = {
      //Console.println("patternArgs "+tree.toString())
      val res = tree match {
        case Bind(_, pat) =>
          patternArgs(pat)

        case a @ Apply(_, List(av @ ArrayValue(_, ts)))
        if isSeqApply(a) && isRightIgnoring(av) =>
          ts.reverse.drop(1).reverse

        case a @ Apply(_, List(av @ ArrayValue(_, ts))) if isSeqApply(a) =>
          ts

        case a @ Apply(_, args) =>
          args

        case a @ UnApply(_, args) =>
          args

        case av @ ArrayValue(_, ts) if isRightIgnoring(av) => 
          ts.reverse.drop(1).reverse

        case av @ ArrayValue(_, ts) =>
          ts

        case _ =>
          List()
      }
      //Console.println("patternArgs returns "+res.toString())
      res
    }

  protected def patternNode(tree:Tree , header:Header , env: CaseEnv ): PatternNode  = {
    //Console.println("patternNode("+tree+","+header+")");
        //else scala.Predef.error("got null tree in patternNode");
    //Console.println("tree.tpe "+tree.tpe);
    //Console.println("tree.getClass() "+tree.getClass());
    val t = tree match {
      case Bind(name, Typed(Ident( nme.WILDCARD ), tpe)) => // x@_:Type
	val tpe2test = tree.symbol.tpe // safest! typer does not always use tpe.tpe!

      // @todo: this optimization probably not allowed, because of null -- but can rework it
      //if (isSubType(header.getTpe(), tpe2test)) {
        //  val node = pDefaultPat(tree.pos, tpe2test)
        //  env.newBoundVar(tree.symbol, tree.tpe, header.selector)
        //  node
        //} else {
          val node = pConstrPat(tree.pos, tpe2test)
          env.newBoundVar( tree.symbol, tpe2test, typed(Ident( node.casted )));
          node
        //}

      case Bind(name, Ident(nme.WILDCARD)) => // x @ _
        val node = pDefaultPat(tree.pos, header.getTpe())
        if ((env ne null) && (tree.symbol != defs.PatternWildcard))
          env.newBoundVar( tree.symbol, tree.tpe, header.selector);
        node

      case Bind(name, pat) =>                // x @ p
        val node = patternNode(pat, header, env)
        if ((env ne null) && (tree.symbol != defs.PatternWildcard)) {
          val theValue = node.symbol match {
            case NoSymbol => header.selector
            case x        => Ident(x) setType x.tpe
          }
          env.newBoundVar(tree.symbol, tree.tpe, theValue)
        }
       node

      case t @ UnApply(fn, args)  => pUnapplyPat(tree.pos, fn)

      case t @ Apply(fn, args) =>             // pattern with args
        //Console.println("Apply node: "+t);
        //Console.println("isSeqApply "+isSeqApply(t));
        if (isSeqApply(t)) {
          args(0) match {
            //  case Sequence(ts)=>
              case av @ ArrayValue(_, ts)=>
                if(isRightIgnoring(av)) {
                  //Console.println(av.toString()+" IS RIGHTIGNORING");
                  val castedRest = ts.last match {
                    case b:Bind => b.symbol;
                    case _      => null
                  }
                  pRightIgnoringSequencePat(tree.pos, tree.tpe, castedRest, ts.length-1);
                } else
                  //Console.println(av.toString()+" IS  N O T  RIGHTIGNORING");
                  pSequencePat(tree.pos, tree.tpe, ts.length);
          }
        } else if ((fn.symbol ne null) &&
                   fn.symbol.isStable &&
                   !(fn.symbol.isModule &&
                     ((fn.symbol.flags & Flags.CASE) != 0))) {
                       pVariablePat(tree.pos, tree);
                     }
          else {
             /*
            Console.println("apply but not seqApply");
            Console.println("tree.tpe="+tree.tpe);
            Console.println("tree.symbol="+tree.symbol);
             */
             pConstrPat(tree.pos, tree.tpe);
          }
      case Typed(Ident( nme.WILDCARD ), tpe) => // x@_:Type
        val doTest = isSubType(header.getTpe(), tpe.tpe) // this is already an optimization
        if (doTest) pDefaultPat(tree.pos, tpe.tpe)
        else pConstrPat(tree.pos, tpe.tpe)

      case t @ Typed(ident, tpe) =>       // variable pattern
        //Console.println("Z");
        val doTest = isSubType(header.getTpe(),tpe.tpe);
        val node =
          if(doTest) pDefaultPat(tree.pos, tpe.tpe)
          else pConstrPat(tree.pos, tpe.tpe)
        if ((null != env) /* && (ident.symbol != defs.PatternWildcard) */)
          node match {
            case ConstrPat(casted) =>
              env.newBoundVar(t.expr.symbol,
                              tpe.tpe,
                              Ident( casted ).setType(casted.tpe));
            case _ =>
              env.newBoundVar(t.expr.symbol,
                              tpe.tpe,
                              {if(doTest) 
                                header.selector 
                               else 
                                 typed(Ident(node
                                             .asInstanceOf[ConstrPat]
                                             .casted))});
          }
      node

      case Ident(nme.WILDCARD) => pDefaultPat(tree.pos, header.getTpe())

      case Ident(name) => // pattern without args or named constant
        if (tree.symbol.isPrimaryConstructor) 
          scala.Predef.error("error may not happen: ident is primary constructor"+tree.symbol); // Burak
        else 
          pVariablePat(tree.pos, tree); // named constant (capitalized variable Foo)

      case Select(_, name) => // named constant
        if (tree.symbol.isPrimaryConstructor)
          pConstrPat(tree.pos, tree.tpe)
        else
          pVariablePat(tree.pos, tree)

      case Literal(Constant(value)) =>
        pConstantPat(tree.pos, tree.tpe, value)

      case av @ ArrayValue(_, ts) =>
        if(isRightIgnoring(av)) {
          val castedRest = ts.last match {
            case b:Bind => b.symbol
            case _      => null
          }
          //Console.println("array value "+av+" is right ignoring!")
          pRightIgnoringSequencePat(tree.pos, tree.tpe, castedRest, ts.length-1);
        } else {
          //Console.println("array value "+av+" is not considered right ignoring")
          //lastSequencePat = 
          pSequencePat(tree.pos, tree.tpe, ts.length);
          //lastSequencePat

        }
      case Alternative(ts) =>
        if (ts.length < 2)
          Predef.error("ill-formed Alternative")
        val subroot = pConstrPat(header.pos, header.getTpe())
        subroot.and = pHeader(header.pos, header.getTpe(), header.selector.duplicate)
        val subenv = new CaseEnv
        var i = 0; while (i < ts.length) {
          val target = enter1(ts(i), -1, subroot, subroot.symbol, subenv)
          target.and = pBody(tree.pos)
          i = i + 1
        }
        pAltPat(tree.pos, subroot.and.asInstanceOf[Header])
/*
      case Star(Ident(nme.WILDCARD))  => 
        header.selector match {
          case Apply(Select(t, apply), arg @ List(litconst)) =>
            Console.println(t)
            Console.println(apply)
            Console.println(litconst)
            val tree = Apply(Select(Select(t, "toList"), "drop"), arg)
            throw new OptimizeSequencePattern(tree); // ? has to be caught and rethrown by each bind
        }
        // bind the rest /////////////////////////////////////////////////////
*/
      case _ =>
        Predef.error("unit = " + cunit + "; tree = " +
          (if (tree eq null) "null" else tree))
    }
    //Console.print(t);
    t
  }

  protected def enter(pat: Tree, index: Int, target: PatternNode,
                      casted: Symbol, env: CaseEnv): PatternNode =
    target match {
      case ConstrPat(newCasted) =>
        enter1(pat, index, target, newCasted, env)
      case SequencePat(newCasted, len) =>
        enter1(pat, index, target, newCasted, env)
      case _ =>
        enter1(pat, index, target, casted, env)
    }

  private def newHeader(pos: PositionType, casted: Symbol, index: Int): Header = {
    //Console.println("newHeader(pos,"+casted+","+index+")");
    //Console.println("  casted.tpe"+casted.tpe);
    //Console.println("  casted.pos "+casted.pos+"  equals firstpos?"+(casted.pos == Position.FIRSTPOS));
    val ident = typed(Ident(casted))
    if (casted.pos == Position.FIRSTPOS) { // load the result of casted(i)
      //Console.println("FIRSTPOS");

      //Console.println("DEBUG")
      //Console.println()

      val t = typed(
        Apply(Select(ident, ident.tpe.member(nme.apply)),
              List(Literal(Constant(index)))))
      val seqType = t.tpe
      pHeader( pos, seqType, t )
    } else if (defs.isProductType(casted.tpe)) {
      val acc = defs.productProj(casted.tpe.typeArgs.length, index+1)
      val accTree = typed(Apply(Select(ident, acc), List())) // nsc !
      pHeader(pos, accTree.tpe, accTree)
    } else {
      //Console.println("NOT FIRSTPOS");
      //Console.println("newHeader :: ");
      
      if(!casted.tpe.symbol.hasFlag(Flags.CASE)) {
/*	Console.println("  newHeader :: casted="+casted);
	Console.println("  newHeader :: casted.pos="+casted.pos);
	Console.println("  newHeader :: casted.pos==Position.FIRSTPOS"+(casted.pos == Position.FIRSTPOS));
	Console.println("  newHeader :: casted.tpe="+casted.tpe);
      	Console.println("  newHeader :: casted.tpe.symbol="+casted.tpe.symbol);
print()
*/	throw new Error("internal problem, trying casefield access for no case class") //DBG
      }
      
      val caseAccs = casted.tpe.symbol.caseFieldAccessors;
      if (caseAccs.length <= index) Console.println("selecting " + index + " in case fields of " + casted.tpe.symbol + "=" + casted.tpe.symbol.caseFieldAccessors);//debug
      val ts = caseAccs(index);
      val accTree = typed(Apply(Select(ident, ts), List()))
      val accType = accTree.tpe
      accType match {
        // scala case accessor
        case MethodType(_, _) =>
          //Console.println("Hello?!");
          pHeader(pos, accType.resultType, Apply(accTree, List()))
        // jaco case accessor
        case _ =>
          //Console.println("Hola?!");
          pHeader(pos, accType, accTree)
      }
    }
  }

  /** main enter function
   *
   *  invariant: ( curHeader == (Header)target.and ) holds
   */
  protected def enter1(pat: Tree, index: Int, target: PatternNode,
                       casted: Symbol, env: CaseEnv): PatternNode = {
    //Console.println("enter(" + pat + ", " + index + ", " + target + ", " + casted + ")");
    var bodycond: PatternNode => Body = null // in case we run into a body (combination of typed pattern and constructor pattern, see bug#644)
    val patArgs = patternArgs(pat);      // get pattern arguments
    //System.err.println("patArgs = "+patArgs);
    var curHeader: Header = target.and match {
      case null => null
      case h: Header => h    // advance one step in intermediate representation
      case b: Body if (b.or ne null) => b.or.asInstanceOf[Header]
      case b: Body =>
        if (b.guard(b.guard.length - 1) == EmptyTree) {
          cunit.error(pat.pos, "unreachable code")
          null
        }
        else {
          bodycond = {h => b.or = h; b} // depends on the side-effect to curHeader (*)
          null
        }
    }
    if (curHeader eq null) {                  // check if we have to add a new header
      //Console.println(" -- adding new header")
      //assert index >= 0 : casted;
      if (index < 0) { Predef.error("error entering:" + casted); return null }

      //access the index'th child of a case class
        
      curHeader  = newHeader(pat.pos, casted, index)
      target.and = curHeader; // (*)
      
      if (bodycond ne null) target.and = bodycond(target.and) // restores body with the guards
      
      curHeader.or = patternNode(pat, curHeader, env)
      enter(patArgs, curHeader.or, casted, env)
      
    } else {
      //Console.println("   enter: using old header for casted = "+casted) // DBG
      // find most recent header
      while (curHeader.next ne null)
        curHeader = curHeader.next
      // create node
      var patNode = patternNode(pat, curHeader, env)
      var next: PatternNode = curHeader
      // add branch to curHeader, but reuse tests if possible
      while (true) {
        if (next.isSameAs(patNode)) {           // test for patNode already present --> reuse
          //Console.println(" -- found the same!")
          // substitute... !!!
          patNode.symbol match {
            case NoSymbol => ;
            case ocasted =>
              env.substitute(ocasted, typed(Ident(next.symbol)));
          }
          return enter(patArgs, next, casted, env);
        } else if (next.isDefaultPat() ||           // default case reached, or 
                   ((next.or eq null) &&            //  no more alternatives and
                    (patNode.isDefaultPat() || next.subsumes(patNode)))) {
                      // new node is default or subsumed 
                      var header = pHeader(patNode.pos, 
                                           curHeader.getTpe(), 
                                           curHeader.selector);
                      {curHeader.next = header; header};
                      header.or = patNode;
                      return enter(patArgs,
                                   patNode,
                                   casted,
                                   env); 
                    }
          else if (next.or eq null) {
            //Console.println(" -- add new branch!")
            return enter(patArgs, {next.or = patNode; patNode}, casted, env); // add new branch
          } else
            next = next.or;
      }
      error("must not happen")
      null
    }
  }

    /** calls enter for an array of patterns, see enter
     *
     *  @param pats    ...
     *  @param target1 ...
     *  @param casted1 ...
     *  @param env     ...
     *  @return        ...
     */
    protected def enter(pats: List[Tree], target1: PatternNode,
                        casted1: Symbol, env: CaseEnv): PatternNode = {
      var target = target1
      var casted = casted1
      target match {
	case UnapplyPat(newCasted, fn) =>
	  casted = newCasted
        case ConstrPat(newCasted) =>
          casted = newCasted
        case SequencePat(newCasted, len) =>
          casted = newCasted
        case RightIgnoringSequencePat(newCasted, _, len) =>
          casted = newCasted
        case _ =>
      }
      var i = 0; while (i < pats.length) {
        target = enter1(pats(i), i, target, casted, env)
        i = i + 1
      }
      target
    }

    protected def nCaseComponents(tree: Tree): int = {
      tree match {
        case Apply(fn, _) =>
          val tpe = tree.tpe.symbol.primaryConstructor.tpe
          //Console.println("~~~ " + tree.type() + ", " + tree.type().symbol.primaryConstructor());
          tpe match {
            // I'm not sure if this is a good idea, but obviously, currently all case classes
            // without constructor arguments have type NoType
            case NoType =>
              error("this cannot happen")
              0
            case MethodType(args, _) =>
              args.length
            case PolyType(tvars, MethodType(args, _)) =>
              args.length
            case PolyType(tvars, _) =>
              0
            case _ =>
              error("not yet implemented;" +
                    "pattern matching for " + tree + ": " + tpe);
            }
        }
      0
    }


    //////////// generator methods

    def toTree(): Tree = {
     val t = if (optimize && isSimpleIntSwitch())
        intSwitchToTree()
      // else if (false && optimize && isSimpleSwitch())
      //  switchToTree()
      else {
        //print()
        generalSwitchToTree()
      }
      //if(settings.statistics.value)
	//Console.println("could remove "+(nremoved+nsubstituted)+" ValDefs, "+nremoved+" by deleting and "+nsubstituted+" by substitution")
      return t
    }
    case class Break(res:Boolean) extends java.lang.Throwable
    case class Break2() extends java.lang.Throwable

    // TODO disentangle this
    protected def isSimpleSwitch(): Boolean  = {
      print();
      var patNode = root.and;
      while (patNode ne null) {
        var node = patNode;
        while (({node = node.or; node}) ne null) {
          node match {
                case VariablePat(tree) =>
                  //Konsole.println(((tree.symbol.flags & Flags.CASE) != 0));
                case ConstrPat(_) =>
                  //Konsole.println(node.getTpe().toString() + " / " + ((node.getTpe().symbol.flags & Flags.CASE) != 0));
                    var inner = node.and;
                    def funct(inner: PatternNode): Boolean = {
                      //outer: while (true) {
                      inner match {
                        case _h:Header =>
                          if (_h.next ne null)
                            throw Break(false);
                          funct(inner.or)

                        case DefaultPat() =>
                          funct(inner.and)

                        case b:Body =>
                          if ((b.guard.length > 1) ||
                              (b.guard(0) != EmptyTree))
                            throw Break(false);

                          throw Break2() // break outer
                        case _ =>
                          throw Break(false)
                      }
                    }
                    var res = false
                    var set = false
                    try {
                      funct(inner)
                    } catch {
                      case ex: Break =>
                        res = ex.res
                        set = true
                      case ex: Break2 =>
                    }
                    if(set) return res;
            case _ =>
              return false;
          }
        }
        patNode = patNode.nextH()
      }
      true
    }

  protected def isSimpleIntSwitch(): Boolean =
    if (isSameType(selector.tpe.widen, defs.IntClass.tpe)) {
      var patNode = root.and
      while (patNode ne null) {
        var node = patNode;
        while (({node = node.or; node}) ne null) {
          node match {
            case ConstantPat(_) => ;
            case DefaultPat() => // experimental: enable simple int switch with default
            case _ =>
              return false;
          }
          node.and match {
            case _b:Body =>
              if ((_b.guard.length > 1) ||
                  (_b.guard(0) != EmptyTree) ||
                  (_b.bound(0).length > 0))
                return false;
            case _ =>
              return false;
          }
        }
        patNode = patNode.nextH();
      }
      true
    } else
      false

  class TagBodyPair(tag1: Int, body1: Tree, next1: TagBodyPair ) {
    var tag: int = tag1
    var body: Tree = body1
    var next: TagBodyPair = next1

    def length(): Int =
      if (null == next) 1 else (next.length() + 1)
  }

  protected def numCases(patNode1: PatternNode): Int = {
    var patNode = patNode1
    var n = 0
    while (({patNode = patNode.or; patNode}) ne null)
    patNode match {
      case DefaultPat() => ;
      case _ => n = n + 1
    }
    n
  }

  protected def defaultBody(patNode1: PatternNode, otherwise: Tree ): Tree = {
    var patNode = patNode1;
    while (patNode ne null) {
      var node = patNode
      while (({node = node.or; node}) ne null)
      node match {
        case DefaultPat() =>
          return node.and.bodyToTree();
        case _ =>
      }
      patNode = patNode.nextH()
    }
    otherwise
  }

  /** This method translates pattern matching expressions that match
   *  on integers on the top level.
   */
  def intSwitchToTree(): Tree = {
    def insert1(tag: Int, body: Tree, current: TagBodyPair): TagBodyPair = {
      if (current eq null)
        new TagBodyPair(tag, body, null)
      else if (tag > current.tag)
        new TagBodyPair(current.tag, current.body, insert1(tag, body, current.next))
      else
        new TagBodyPair(tag, body, current)
    }

    //print();
    val ncases = numCases(root.and)
    val matchError = ThrowMatchError(selector.pos, Ident(root.symbol))
    // without a case, we return a match error if there is no default case
    if (ncases == 0)
      return defaultBody(root.and, matchError);
    // for one case we use a normal if-then-else instruction
    else if (ncases == 1) {
      root.and.or match {
        case ConstantPat(value) =>
          return squeezedBlock(
            List(ValDef(root.symbol, selector)),
            If(Equals(Ident(root.symbol), Literal(value)),
               (root.and.or.and).bodyToTree(),
               defaultBody(root.and, matchError))
          )
        case _ =>
          return generalSwitchToTree();
      }
    }
    //
    // if we have more than 2 cases than use a switch statement
    val _h:Header = root.and.asInstanceOf[Header]

    val next = _h.next
    var mappings: TagBodyPair = null
    var defaultBody1: Tree = matchError
    var patNode = root.and
    while (patNode ne null) {
      var node = patNode.or
      while (node ne null) {
        node match {
          case DefaultPat() =>
            //experimental: allow default body
            //if (defaultBody1 != null)
            //  scala.Predef.error("not your day today");
            defaultBody1 = node.and.bodyToTree();
            node = node.or;

          case ConstantPat( value: Int )=>
            mappings = insert1(
              value,
              node.and.bodyToTree(),
              mappings);
          node = node.or;
        }
      }
      patNode = patNode.nextH()
    }

    var n = mappings.length()
    var nCases: List[CaseDef] = Nil
    while (mappings ne null) {
      nCases = CaseDef(Literal(mappings.tag),
                       mappings.body) :: nCases;
      mappings = mappings.next;
    }
    /*
    val tags = new Array[Int](n);
    val bodies = new Array[Tree](n);
    n = 0;
    while (mappings ne null) {
      tags(n) = mappings.tag;
      bodies(n) = mappings.body;
      n = n + 1;
      mappings = mappings.next;
    }
    return Switch(selector, tags, bodies, defaultBody1, resultType);
    */
    nCases = CaseDef(Ident(nme.WILDCARD), squeezedBlock(List(ValDef(root.symbol, selector)),defaultBody1)) :: nCases;
    Match(selector, nCases)
  }


    var exit: Symbol = null
    /** simple optimization: if the last pattern is `case _' (no guards), we won't generate the ThrowMatchError
     */
  def generalSwitchToTree(): Tree = {
    this.exit = owner.newLabel(root.pos, "exit").setInfo(new MethodType(List(resultType), resultType));
    val result = exit.newValueParameter(root.pos, "result").setInfo( resultType );
    return squeezedBlock(
      List(
        ValDef(root.symbol, selector),
        toTree(root.and),
        ThrowMatchError(selector.pos,  Ident(root.symbol))),
      LabelDef(exit, List(result), Ident(result)))
  }
  
  /*protected*/ def toTree(node1: PatternNode): Tree = {
    def optimize1(selType:Type, alternatives1: PatternNode ): Boolean = {
      var alts = alternatives1;
      if (!optimize || !isSubType(selType, defs.ScalaObjectClass.tpe))
        return false;
      var cases = 0;
      while (alts ne null) {
        alts match {
          case ConstrPat(_) =>
            if (alts.getTpe().symbol.hasFlag(Flags.CASE))
              cases = cases +1;
            else
              return false;
          
          case DefaultPat() =>
            ;
          case _ =>
            return false;
        }
        alts = alts.or;
      }
      return cases > 2;
    } // def optimize
    
    var node = node1;

    var res: Tree = typed(Literal(Constant(false))); //.setInfo(defs.BooleanClass);
    while (node ne null)
    node match {
      
      case _h:Header =>
        val selector = _h.selector;
        val next = _h.next;
        //res = And(mkNegate(res), toTree(node.or, selector));
        if (optimize1(node.getTpe(), node.or))
          res = Or(res, toOptTree(node.or, selector));
        else
          res = Or(res, toTree(node.or, selector));
        node = next;

      case _b:Body =>
        var bound = _b.bound
        val guard = _b.guard
        val body  = _b.body
        if ((bound.length == 0) &&
            (guard.length == 0) &&
            (body.length == 0)) {
              return Literal(Constant(true));
            } 
        var i = guard.length - 1; while(i >= 0) {
        val ts:Seq[Tree] = bound(i).asInstanceOf[Array[Tree]];
        val temp = owner.newValue(body(i).pos, cunit.fresh.newName("r$"))
          .setFlag(Flags.SYNTHETIC).setInfo(resultType); 
          var res0: Tree = 
            //Block(
            //  List(Assign(Ident(resultVar), body(i))),
            //  Literal(Constant(true)));
	    squeezedBlock(
              List(
                ValDef(temp, body(i)),
                Apply(Ident(exit), List(Ident(temp)))),
              Literal(Constant(true))
            ); // forward jump
          if (guard(i) != EmptyTree)
            res0 = And(guard(i), res0);
          res = Or(squeezedBlock(ts.toList, res0), res);
          i = i - 1
        }
      if (_b.or ne null)
        res = Or(res, toTree(_b.or))
      return res;
        case _ =>
          scala.Predef.error("error in toTree");
      }
      return res;
    }

    class TagNodePair(tag1: int, node1: PatternNode, next1: TagNodePair) { 
      var tag: int = tag1
      var node: PatternNode = node1
      var next: TagNodePair = next1

      def length(): Int =
        if (null == next) 1 else (next.length() + 1)
    }

    protected def toOptTree(node1: PatternNode, selector: Tree): Tree = {
      def insert2(tag: Int, node: PatternNode, current: TagNodePair): TagNodePair = {
        if (current eq null)
          return new TagNodePair(tag, node, null);
        else if (tag > current.tag)
          return new TagNodePair(current.tag, current.node, insert2(tag, node, current.next));
        else if (tag == current.tag) {
          val old = current.node;
          ({current.node = node; node}).or = old;
          return current;
        } else
          return new TagNodePair(tag, node, current);
      }

      def insertNode(tag: int, node: PatternNode, current:TagNodePair): TagNodePair = {
        val newnode = node.dup()
        newnode.or = null
        insert2(tag, newnode, current)
      }
      var node = node1;
      //System.err.println("pm.toOptTree called"+node);
      var cases: TagNodePair  = null
      var defaultCase: PatternNode  = null
      while (node ne null)
      node match {
        case ConstrPat(casted) =>
          cases = insertNode(node.getTpe().symbol.tag, node, cases)
          node = node.or

        case DefaultPat() =>
          defaultCase = node
          node = node.or

        case _ =>
          scala.Predef.error("errare humanum est")
      }
      var n = cases.length()
      /*
      val tags = new Array[int](n);
      val bodies = new Array[Tree](n);
      n = 0;
      while (null != cases) {
        tags(n) = cases.tag;
        bodies(n) = toTree(cases.node, selector);
        n = n + 1;
        cases = cases.next;
      }
      */


      /*
      return 
      Switch(
        Apply(
          Select(selector.duplicate, defs.ScalaObjectClass_tag),
          List()),
        tags,
        bodies,
        { if (defaultCase eq null) Literal(false) else toTree(defaultCase.and) },
                        defs.boolean_TYPE());
                        */
      var nCases: List[CaseDef] = Nil
      while (cases ne null) {
        nCases = CaseDef(Literal(Constant(cases.tag)),
                         toTree(cases.node, selector)) :: nCases;
        cases = cases.next
      }

      val defBody =
        if (defaultCase eq null) Literal(Constant(false))
        else toTree(defaultCase.and)

      nCases = CaseDef(Ident(nme.WILDCARD), defBody) :: nCases;
      return Match(Apply(Select(selector.duplicate, defs.ScalaObjectClass_tag),
                         List()),
                   nCases);
    }

    /** why not use plain `if's? the reason is that a failing *guard* must still remain
     *  on the testing path (a kind of backtracking) in order to test subsequent patterns
     *  consider for instance bug#440
     *
     *  @param cond  ...
     *  @param thenp ...
     *  @param elsep ...
     *  @return      ...
     */
    def myIf(cond: Tree, thenp: Tree, elsep: Tree) =
      Or(And(cond, thenp), elsep)

    protected def toTree(node: PatternNode, selector:Tree): Tree = {
      //Konsole.println("pm.toTree("+node+","+selector+") selector.tpe = "+selector.tpe+")")
      if (selector.tpe eq null)
        scala.Predef.error("cannot go on")
      if (node eq null)
        return Literal(Constant(false));
      else 
        node match {
          case DefaultPat() =>
            return toTree(node.and);

	  case UnapplyPat(casted, fn @ Apply(fn1, _)) =>
	    val v = newVar(fn.pos, fn.tpe)
	    Or(squeezedBlock(
	      List(ValDef(v,Apply(fn1,List(selector)))),
	      And(Not(Select(Ident(v), nme.isEmpty)),
		  squeezedBlock(List(ValDef(casted, typed(Select(Ident(v),"get")))),toTree(node.and)))), 
	       toTree(node.or, selector.duplicate))
          case ConstrPat(casted) => 
            def outerAlwaysEqual(left: Type, right: Type) = Pair(left,right) match {
              case Pair(TypeRef(lprefix, _,_), TypeRef(rprefix,_,_)) if lprefix =:= rprefix =>
                true
              case _ =>
                false
            }

            val ntpe = node.getTpe
            var cond = gen.mkIsInstanceOf(selector.duplicate, ntpe)
            // compare outer instance for patterns like foo1.Bar foo2.Bar if not statically known to match

            if(!outerAlwaysEqual(casted.tpe, selector.tpe)) {
              casted.tpe match {
                case TypeRef(prefix,_,_) if (prefix.symbol.isTerm && !prefix.symbol.isPackage) =>
                  var theRef = gen.mkAttributedRef(prefix.prefix, prefix.symbol) 

                  // needs explicitouter treatment
                  theRef = handleOuter(theRef)

                  val outerAcc = outerAccessor(casted.tpe.symbol)
                
                  if(outerAcc != NoSymbol) { // some guys don't have outers 
                    cond = And(cond,
                               Eq(Apply(Select(
                                 typed(gen.mkAsInstanceOf(selector.duplicate, ntpe, true)), outerAcc),List()), theRef))
                  }
                case _ =>
                  //ignore ;
              }
            }
          val succ = squeezedBlock(List(ValDef(casted,
                                               typed(gen.mkAsInstanceOf(selector.duplicate, ntpe, true)))),
                                   toTree(node.and))
            val fail = toTree(node.or, selector.duplicate)

            return myIf(cond, succ, fail)
            
          case SequencePat(casted, len) =>
            val ntpe = node.getTpe

            val tpetest = 
              if(!isSubType(selector.tpe, ntpe))
                gen.mkIsInstanceOf(selector.duplicate, ntpe);
              else
                Literal(Constant(true))

            val treeAsSeq = 
              if(!isSubType(selector.tpe, ntpe))
                typed(gen.mkAsInstanceOf(selector.duplicate, ntpe, true))
              else
                selector.duplicate

            val succ: Tree = squeezedBlock(
              List(ValDef(casted, treeAsSeq)),
              toTree(node.and))

            val fail = toTree(node.or, selector.duplicate) 

            return Or( And( And( tpetest, seqHasLength(treeAsSeq.duplicate, ntpe, len)), succ ),
                      fail);

          case RightIgnoringSequencePat(casted, castedRest, minlen) =>

            val ntpe = node.getTpe

            val tpetest = 
              if(!isSubType(selector.tpe,ntpe))
                gen.mkIsInstanceOf(selector.duplicate, ntpe);
              else
                Literal(Constant(true))

            val treeAsSeq = 
              if(!isSubType(selector.tpe,ntpe))
                typed(gen.mkAsInstanceOf(selector.duplicate, ntpe, true))
              else
                selector.duplicate
          
            val cond = 
              if(minlen == 0) tpetest 
              else And(tpetest, seqLongerThan(treeAsSeq, ntpe, minlen))

            var bindings =
              if(castedRest ne null)
                List(ValDef(castedRest, seqDrop(treeAsSeq.duplicate, minlen)))
              else 
                List()
                
            bindings = ValDef(casted, treeAsSeq.duplicate) :: bindings

            val succ = Block(bindings, toTree(node.and)) 
            val fail = toTree(node.or, selector.duplicate)

            return Or(And(cond, succ), fail);
            
          case ConstantPat(value) =>
            val succ = toTree(node.and) 
            val fail = toTree(node.or, selector.duplicate)
            return myIf(Equals(selector.duplicate,
                             typed(Literal(Constant(value))).setType(node.tpe)),
                        succ,
                        fail);

          case VariablePat(tree) =>
            val cmp = if(tree.tpe.symbol.isModuleClass && // objects are compared by eq, not == (avoids unnecessary null-magic)
                         selector.tpe <:< definitions.AnyRefClass.tpe) {
                        Eq(selector.duplicate, tree)
                      } else  {
                        Equals(selector.duplicate, tree)
                      }
            return myIf( cmp,
                      toTree(node.and),
                      toTree(node.or, selector.duplicate));

          case AltPat(header) =>
            return myIf(toTree(header),
                      toTree(node.and),
                      toTree(node.or, selector.duplicate));
          case _ =>
            scala.Predef.error("cannot handle pattern:"+node);
        }
    }
  }
}
 

