/* NSC -- new Scala compiler
 * Copyright 2005-2007 LAMP/EPFL
 * @author Burak Emir
 */
// $Id$

package scala.tools.nsc.matching

import scala.tools.nsc.util.{Position, NoPosition}

/**
 *  @author Burak Emir
 */
trait PatternNodes { self: transform.ExplicitOuter =>

  import global._
                    
  object TagIndexPair {
    /** inserts tag and index, maintaining relative order of tags */
  def insert(current: TagIndexPair, tag: Int, index: Int): TagIndexPair = {
    if (current eq null)
      new TagIndexPair(tag, index, null)
    else if (tag > current.tag) 
      new TagIndexPair(current.tag, current.index, insert(current.next, tag, index))
    else
      new TagIndexPair(tag, index, current)
    }
  } 

  /** sorted, null-terminated list of (int,int) pairs */
  class TagIndexPair(val tag: Int, val index: Int, val next: TagIndexPair) {

    def find(tag: Int): Int =
      if (this.tag == tag) index
      else next.find(tag) // assumes argument can always be found

  }

  // --- misc methods

  private val dummy1 = EmptyTree :: Nil
  private val dummy2 = EmptyTree :: dummy1
  private val dummy3 = EmptyTree :: dummy2
  private val dummy4 = EmptyTree :: dummy3
  private val dummy5 = EmptyTree :: dummy4
  private val dummy6 = EmptyTree :: dummy5
  private val dummy7 = EmptyTree :: dummy6

  final def getDummies(i:Int): List[Tree] = i match {
    case 0 => Nil
    case 1 => dummy1
    case 2 => dummy2
    case 3 => dummy3
    case 4 => dummy4
    case 5 => dummy5
    case 6 => dummy6
    case 7 => dummy7
    case n => EmptyTree::getDummies(i-1)
  }

  def makeBind(vs:SymList, pat:Tree): Tree = 
    if(vs eq Nil) pat else Bind(vs.head, makeBind(vs.tail, pat)) setType pat.tpe

  def normalizedListPattern(pats:List[Tree], tptArg:Type): Tree = pats match {
    case Nil   => gen.mkAttributedRef(definitions.NilModule)
    case sp::xs if strip2(sp).isInstanceOf[Star] => 
      makeBind(definedVars(sp), Ident(nme.WILDCARD) setType sp.tpe)
    case x::xs => 
      var resType: Type = null;
      val consType: Type = definitions.ConsClass.primaryConstructor.tpe match {
        case mt @ MethodType(args, res @ TypeRef(pre,sym,origArgs)) =>
          val listType = TypeRef(pre, definitions.ListClass, List(tptArg))
               resType = TypeRef(pre, sym                  , List(tptArg))
        
          MethodType(List(tptArg, listType), resType)
      }
      Apply(TypeTree(consType),List(x,normalizedListPattern(xs,tptArg))).setType(resType)
  }

  object Apply_Value {
    def unapply(x:Apply) = if ((x.symbol ne null) && (x.args eq Nil)) Some(x.tpe.prefix, x.symbol) else None
  }
                   
  object Apply_CaseClass_NoArgs {
    def unapply(x:Apply) = if ((x.symbol eq null) && (x.args eq Nil)) Some(x.tpe) else None
  }
  object Apply_CaseClass_WithArgs {
    def unapply(x:Apply) = if (x.symbol eq null) true else false
  }

  object __UnApply {
    def unapply(x:Tree) = strip(x) match {
      case (vs, UnApply(Apply(fn, _), args)) => 
        val argtpe = fn.tpe.asInstanceOf[MethodType].paramTypes.head
        Some(Tuple3(vs,argtpe,args))
      case _                      => None
    }
  }
/*
  object ArrayValueFixed {
    def unapply(x:Tree):Option[List[Tree]] = x match {
      case ArrayValue(_,xs) => if(isDefaultPattern(xs.last)) Some(xs) else None
    }
  }
  object ArrayValueStar {
    def unapply(x:Tree): Option[(List[Tree],Tree)] = x match {
      case ArrayValue(_,xs) => 
        val ys = xs.drop(xs.length-1) 
        val p = xs.last
        if(!isDefaultPattern(p)) Some(ys,p) else None
    }
  }*/

  /* equality checks for named constant patterns like "Foo()" are encoded as "_:<equals>[Foo().type]"
   * and later compiled to "if(Foo() == scrutinee) ...". This method extracts type information from
   * such an encoded type, which is used in optimization. If the argument is not an encoded equals
   *  test, it is returned as is.
   */
  def patternType_wrtEquals(pattpe:Type) = pattpe match {
    case TypeRef(_,sym,arg::Nil) if sym eq definitions.EqualsPatternClass => 
      arg
    case x => x
  }
  /** returns if pattern can be considered a no-op test ??for expected type?? */
  final def isDefaultPattern(pattern:Tree): Boolean = pattern match {
    case Bind(_, p)            => isDefaultPattern(p)
    case EmptyTree             => true // dummy
    case Ident(nme.WILDCARD)   => true
    case _                     => false
// -- what about the following? still have to test "ne null" :/
//  case Typed(nme.WILDCARD,_) => pattern.tpe <:< scrutinee.tpe
  }

  final def DBG(x:String) { if(settings_debug) Console.println(x) }

  /** returns all variables that are binding the given pattern 
   *  @param   x a pattern
   *  @return  vs variables bound, p pattern proper
   */
  final def strip(x: Tree): (Set[Symbol], Tree) = x match {
    case b @ Bind(_,pat) => val (vs, p) = strip(pat); (vs + b.symbol, p)
    case z               => (emptySymbolSet,z)
  }

  final def strip1(x: Tree): Set[Symbol] = x match { // same as strip(x)._1
    case b @ Bind(_,pat) => strip1(pat) + b.symbol
    case z               => emptySymbolSet
  }
  final def strip2(x: Tree): Tree = x match {        // same as strip(x)._2
    case     Bind(_,pat) => strip2(pat)
    case z               => z
  }

  final def isCaseClass(tpe: Type): Boolean = 
    tpe match {
      case TypeRef(_, sym, _) =>
        if(!sym.isAliasType) 
          sym.hasFlag(symtab.Flags.CASE)
        else
          tpe.normalize.typeSymbol.hasFlag(symtab.Flags.CASE)
      case _ => false
    } 

  final def isEqualsPattern(tpe: Type): Boolean = 
    tpe match {
      case TypeRef(_, sym, _) => sym eq definitions.EqualsPatternClass
      case _                  => false
    }


  //  this method obtains tag method in a defensive way
  final def getCaseTag(x:Type): Int = { x.typeSymbol.tag }

  final def definedVars(x:Tree): SymList = {
    var vs = new collection.mutable.ListBuffer[Symbol]
    def definedVars1(x:Tree): Unit = x match {
      case Alternative(bs) => ; // must not have any variables
      case Apply(_, args)  => definedVars2(args)
      case b @ Bind(_,p)   => vs += b.symbol; definedVars1(p) 
      case Ident(_)        => ;
      case Literal(_)      => ;
      case Select(_,_)     => ;
      case Typed(p,_)      => definedVars1(p) //otherwise x @ (_:T)
      case UnApply(_,args) => definedVars2(args)
      
      // regexp specific
      case ArrayValue(_,xs)=> definedVars2(xs)
      case Star(p)         => ; // must not have variables
    }
    def definedVars2(args:List[Tree]): Unit = {
      var xs = args; while(xs ne Nil) { definedVars1(xs.head); xs = xs.tail };
    }
    definedVars1(x);
    vs.toList
  }

  // insert in sorted list, larger items first
  final def insertSorted(tag: Int, xs:List[Int]):List[Int] = xs match { 
    case y::ys if y > tag => y::insertSorted(tag, ys)
    case ys               => tag :: ys
  } 

  // find taag in sorted list
  final def findSorted(Tag: Int, xs:List[Int]): Boolean = xs match { 
    case Tag::_             => true
    case   y::ys if y > Tag => findSorted(Tag,ys)
    case _                  => false
  } 

  /** pvar: the symbol of the pattern variable
   *  temp: the temp variable that holds the actual value
   *  next: next binding
   */
  case class Binding(pvar:Symbol, temp:Symbol, next: Binding) {
    def add(vs:Iterator[Symbol], temp:Symbol): Binding = {
      var b = this; while(vs.hasNext){
        b = Binding(vs.next, temp, b)
      }
      return b
    }
    /** this is just to produce debug output, ListBuffer needs an equals method?! */
    override def equals(x:Any) = {
      x match {
        case NoBinding               => false
        case Binding(pv2,tmp2,next2) => (pvar eq pv2) && (temp eq tmp2) && (next==next2)
      }
    }
    def apply(v:Symbol): Ident = {
      //Console.println(this.toString()+" apply ("+v+"), eq?"+(v eq pvar))
      if(v eq pvar) {Ident(temp).setType(v.tpe)} else next(v)
    }
  }
  object NoBinding extends Binding(null,null,null) {
    override def apply(v:Symbol) = null // not found, means bound elsewhere (x @ unapply-call)
    override def toString = "."
    override def equals(x:Any) = x.isInstanceOf[Binding] && (x.asInstanceOf[Binding] eq this)
  }

  // misc methods END ---

  type SymSet  = collection.immutable.Set[Symbol]
  type SymList = List[Symbol]

  /** returns the child patterns of a pattern 
   */
  protected def patternArgs(tree: Tree): List[Tree] = {
    //Console.println("patternArgs "+tree.toString())
    /*val res = */ tree match {
      case Bind(_, pat) =>
        patternArgs(pat)

      case a @ Apply(_, List(av @ ArrayValue(_, ts))) if isSeqApply(a) && isRightIgnoring(av) =>
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
    //Console.println("patternArgs returns "+res.toString()) ; res
  }

  /** Intermediate data structure for algebraic + pattern matcher
   */
  sealed class PatternNode {
    var pos : Position = NoPosition
    var tpe: Type  = _
    var or: PatternNode = _
    var and: PatternNode = _

    def casted: Symbol = NoSymbol

    def symbol2bind: Symbol = casted
    
    def forEachAlternative(f: PatternNode => Unit) { // only for header?
      var z = this;
      while (z ne null) {
        f(z)
        z = z.or
      }
    }

    def isUnguardedBody = this match {
      case b:Body => b.hasUnguarded
      case _      => false
    }

    def isSingleUnguardedBody = this match {
      case b:Body => b.isSingleUnguarded
      case _      => false
    }

    def bodyToTree(): Tree = this match {
      case _b:Body =>
        _b.body(0)
      case _ => 
        error("bodyToTree called for pattern node "+this)
        null
    }

    def set(p:(Position,Type)): this.type = {
      /*assert(tpe ne null); */
      this.pos = p._1; this.tpe = p._2; this
    }

    def dup(): PatternNode = {
      var res: PatternNode = this match {
        case h:Header =>
          new Header(h.selector, h.next)
        case b:Body=>
          new Body(b.bound, b.guard, b.body)
        case DefaultPat() =>
          DefaultPat()
        case ConstrPat(casted) =>
          ConstrPat(casted)
        case SequencePat(casted, len) =>
          SequencePat(casted, len)
        case ConstantPat(value) =>
          ConstantPat(value)
        case VariablePat(tree) =>
          VariablePat(tree)
        case AltPat(subheader) =>
          AltPat(subheader)
        case _ =>
          error("unexpected pattern"); null
      } 
      res set ((pos, tpe))
      res.or = or
      res.and = and
      res
    }

    /*
    def _symbol: Symbol = this match { // @todo
      case UnapplyPat(casted, fn) =>
	casted
      case ConstrPat(casted) =>
        casted
      case SequencePat(casted, _) =>
        casted
      case RightIgnoringSequencePat(casted, _, _) =>
        casted
      case _ =>
        NoSymbol //.NONE
    }
    */

    def nextH(): PatternNode = this match {
      case _h:Header => _h.next
      case _ => null
    }

    def isDefaultPat(): Boolean = this match {
      case DefaultPat() => true
      case _ => false
    }

    /** returns true if 
     *  p and q are equal (constructor | sequence) type tests, or 
     *  "q matches" => "p matches"
     */
    def isSameAs(q: PatternNode): Boolean = this match {
      case ConstrPat(_) =>
        q match {
          case ConstrPat(_) =>
            isSameType(q.tpe, this.tpe)
          case _ =>
            false
        }
      case SequencePat(_, plen) =>
        q match {
          case SequencePat(_, qlen) =>
            (plen == qlen) && isSameType(q.tpe, this.tpe)
          case _ =>
            false
        }
      case _ =>
        subsumes(q)
    }

    /** returns true if "q matches" => "p matches" 
     */
    def subsumes(q:PatternNode): Boolean = this match {
      case DefaultPat() =>
        q match {
          case DefaultPat() =>
            true
          case _ =>
            false
        }
      case ConstrPat(_) =>
        q match {
          case ConstrPat(_) =>
            isSubType(q.tpe, this.tpe)
          case _ =>
            false
        }
      case SequencePat(_, plen) =>
        q match {
          case SequencePat(_, qlen) =>
            (plen == qlen) && isSubType(q.tpe, this.tpe)
          case _ =>
            false
        }
      case ConstantPat(pval) =>
        q match {
          case ConstantPat(qval) =>
             pval == qval
          case _ =>
            false
        }
      case VariablePat(tree) =>
        q match {
          case VariablePat(other) =>
            (tree.symbol ne null) &&
            (tree.symbol != NoSymbol) &&
            (!tree.symbol.isError) &&
            (tree.symbol == other.symbol)
          case _ =>
            false
        }
      case _ =>
        false
    }

    override def toString(): String = this match {
      case _h:Header =>
        "Header(" + _h.selector + ")";
      case _b:Body =>
        "Body"
      case DefaultPat() =>
        "DefaultPat"
      case ConstrPat(casted) =>
        "ConstrPat(" + casted + ")"
      case SequencePat(casted, len) =>
        "SequencePat(" + casted + ", " + len + "...)"
      case RightIgnoringSequencePat(casted, castedRest, minlen) =>
        "RightIgnoringSequencePat(" + casted + ", " + castedRest + ", "+ minlen + "...)"
      case ConstantPat(value) =>
        "ConstantPat(" + value + ")"
      case VariablePat(tree) =>
        "VariablePat"
      case UnapplyPat(casted, fn) =>
	"UnapplyPat(" + casted + ")"
      case AltPat(alts) =>
        "Alternative("+alts+")"
    }

    def print(indent: String, sb: StringBuilder): StringBuilder = {
      val patNode = this
    
      def cont = if (patNode.or ne null) patNode.or.print(indent, sb) else sb

      def newIndent(s: String) = {
        val removeBar: Boolean = (null == patNode.or)
        val sb = new StringBuilder()
        sb.append(indent)
        if (removeBar) 
          sb.setCharAt(indent.length() - 1, ' ')
        var i = 0; while (i < s.length()) {
          sb.append(' ')
          i += 1
        }
        sb.toString()
      }

      if (patNode eq null)
        sb.append(indent).append("NULL")
      else
        patNode match {
        case UnapplyPat(_,fn) =>
          sb.append(indent + "UNAPPLY(" + fn + ")").append('\n')
        case _h: Header =>
          val selector = _h.selector
          val next = _h.next
          sb.append(indent + "HEADER(" + patNode.tpe +
                          ", " + selector + ")").append('\n')
          if(patNode.or ne null) patNode.or.print(indent + "|", sb)
          if (next ne null)
            next.print(indent, sb)
          else
            sb
        case ConstrPat(casted) =>
          val s = ("-- " + patNode.tpe.typeSymbol.name +
                   "(" + patNode.tpe + ", " + casted + ") -> ")
          val nindent = newIndent(s)
          sb.append(nindent + s).append('\n')
          patNode.and.print(nindent, sb)
          cont

        case SequencePat( casted, plen ) =>
          val s = ("-- " + patNode.tpe.typeSymbol.name + "(" + 
                   patNode.tpe +
                   ", " + casted + ", " + plen + ") -> ")
          val nindent = newIndent(s)
          sb.append(indent + s).append('\n')
          patNode.and.print(nindent, sb)
          cont

        case RightIgnoringSequencePat( casted, castedRest, plen ) =>
          val s = ("-- ri " + patNode.tpe.typeSymbol.name + "(" + 
                   patNode.tpe +
                   ", " + casted + ", " + plen + ") -> ")
          val nindent = newIndent(s)
          sb.append(indent + s).append('\n')
          patNode.and.print(nindent, sb)
          cont


        case DefaultPat() =>
          sb.append(indent + "-- _ -> ").append('\n')
          patNode.and.print(indent.substring(0, indent.length() - 1) +
                      "         ", sb)
          cont

        case ConstantPat(value) =>
          val s = "-- CONST(" + value + ") -> "
          val nindent = newIndent(s)
          sb.append(indent + s).append('\n')
          patNode.and.print( nindent, sb)
          cont

        case VariablePat(tree) =>
          val s = "-- STABLEID(" + tree + ": " + patNode.tpe + ") -> "
          val nindent = newIndent(s)
          sb.append(indent + s).append('\n')
          patNode.and.print(nindent, sb)
          cont

        case AltPat(header) =>
          sb.append(indent + "-- ALTERNATIVES:").append('\n')
          header.print(indent + "   * ", sb)
          patNode.and.print(indent + "   * -> ", sb)
          cont

        case _b:Body =>
          if ((_b.guard.length == 0) && (_b.body.length == 0))
            sb.append(indent + "true").append('\n') 
          else
            sb.append(indent + "BODY(" + _b.body.length + ")").append('\n')

      }
    } // def print

  } // class PatternNode

  class Header(sel1: Tree, next1: Header) extends PatternNode {
    var selector: Tree = sel1
    var next: Header = next1

    def findLast: PatternNode = {
      var g: PatternNode = findLastSection
      while(g.or != null)   { g = g.or }
      g
    }

    def findLastSection: Header = {
      var h: Header = this
      while(h.next != null) { h = h.next }
      h
    }
    var isSubHeader = false

    /* returns true if this header node has a catch all case
    
    def catchesAll: Boolean = {
      //Console.println(this.print("  catchesAll %%%%", new StringBuilder()).toString)
      val p = findLast
      (p.isDefaultPat && p.and.isUnguardedBody)
    }
    */

    // executes an action for every or branch
    def forEachBranch(f: PatternNode => Unit) { if(or ne null) or.forEachAlternative(f) }

    // executes an action for every header section
    def forEachSection(f: Header => Unit) { var h = this; while (h ne null) {f(h); h = h.next}}

    /** returns true if this tree is optimizable
     *  throws a warning if is not exhaustive
     */
    def optimize1(): (Boolean, SymSet, SymSet) = {
      import symtab.Flags

      val selType = this.tpe

      if (!isSubType(selType, definitions.ScalaObjectClass.tpe))
        return (false, null, emptySymbolSet)

      if(this.or eq null) 
        return (false, null, emptySymbolSet)  // only case _

      def checkExCoverage(tpesym:Symbol): SymSet = 
        if(!tpesym.hasFlag(Flags.SEALED)) emptySymbolSet else 
          tpesym.children.flatMap { x => 
            val z = checkExCoverage(x) 
            if(x.hasFlag(Flags.ABSTRACT)) z else z + x 
          }
      
      def andIsUnguardedBody(p1:PatternNode) = p1.and match {
        case p: Body => p.hasUnguarded
        case _       => false
      }
      
      //Console.println("optimize1("+selType+","+alternatives1+")")
      var res = true
      var coveredCases: SymSet  = emptySymbolSet 
      var remainingCases        = checkExCoverage(selType.typeSymbol) 
      var cases = 0

      def traverse(alts:PatternNode) {
        //Console.println("traverse, alts="+alts)
        alts match {
          case ConstrPat(_) =>
            //Console.print("ConstPat! of"+alts.tpe.typeSymbol)
            if (alts.tpe.typeSymbol.hasFlag(Flags.CASE)) {
              coveredCases   = coveredCases + alts.tpe.typeSymbol
              remainingCases = remainingCases - alts.tpe.typeSymbol
              cases = cases + 1
            } else {
              val covered = remainingCases.filter { x => 
                //Console.println("x.tpe is "+x.tpe)
                val y = alts.tpe.prefix.memberType(x)
                //Console.println(y + " is sub of "+alts.tpe+" ? "+isSubType(y, alts.tpe)); 
                isSubType(y, alts.tpe)
              }
              //Console.println(" covered : "+covered)

              coveredCases   = coveredCases ++ covered
              remainingCases = remainingCases -- covered
              res = false
            }

          // Nil is also a "constructor pattern" somehow
          case VariablePat(tree) if (tree.tpe.typeSymbol.hasFlag(Flags.MODULE)) => // Nil
            coveredCases   = coveredCases + tree.tpe.typeSymbol
            remainingCases = remainingCases - tree.tpe.typeSymbol
            cases = cases + 1
            res = res && tree.tpe.typeSymbol.hasFlag(Flags.CASE)
          case DefaultPat() =>
            if(andIsUnguardedBody(alts) || alts.and.isInstanceOf[Header]) {
              coveredCases   = emptySymbolSet
              remainingCases = emptySymbolSet
            }
          case UnapplyPat(_,_) | SequencePat(_, _) | RightIgnoringSequencePat(_, _, _) =>
            res = false
            remainingCases = emptySymbolSet

          case ConstantPat(_) =>
            res = false

          case AltPat(branchesHeader) =>
            res = false
          //Console.println("----------bfore: "+coveredCases)
            branchesHeader.forEachBranch(traverse) // branchesHeader is header
          //Console.println("----------after: "+coveredCases)

          case VariablePat(_) =>
            res = false

          case _:Header | _:Body =>
            Predef.error("cannot happen")
        }
      }

      this.forEachBranch(traverse)
      return (res && (cases > 2), coveredCases, remainingCases)
    } // def optimize

  }

  /** contains at least one body, so arrays are always nonempty 
   */
  class Body(bound1: Array[Array[ValDef]], guard1:Array[Tree], body1:Array[Tree]) extends PatternNode {
    var bound = bound1
    var guard = guard1
    var body = body1

    def hasUnguarded = guard.exists { x => x == EmptyTree }

    def isSingleUnguarded = (guard.length == 1) && (guard(0) == EmptyTree) && (bound(0).length == 0)
  }

  case class DefaultPat()extends PatternNode
  case class ConstrPat(override val casted:Symbol) extends PatternNode
  case class UnapplyPat(override val casted:Symbol, fn:Tree) extends PatternNode {

  override def symbol2bind = NoSymbol

    def returnsOne =  {
      /*val res =*/ definitions.getProductArgs(casted.tpe) match {
        case Some(Nil) => true     // n = 0
        case Some(x::Nil) => true  // n = 1
        case Some(_) => false
        case _ => true
      }
      //Console.println("returns one? "+casted.tpe)
      //Console.println(" I say: "+res)
      //res
    }
  }
  case class ConstantPat(value: Any /*AConstant*/) extends PatternNode
  case class VariablePat(tree: Tree) extends PatternNode
  case class AltPat(subheader: Header) extends PatternNode
  case class SequencePat(override val casted: Symbol, len: Int) extends PatternNode // only used in PatternMatcher

  case class RightIgnoringSequencePat(override val casted: Symbol, castedRest: Symbol, minlen: int) extends PatternNode //PM

  /** the environment for a body of a case
   * @param owner the owner of the variables created here
   */
  class CaseEnv {
//    (val owner:Symbol, unit:CompilationUnit) 
    private var boundVars: Array[ValDef] = new Array[ValDef](4)
    private var numVars = 0

    def substitute(oldSym: Symbol, newInit: Tree) {
      var i = 0; while (i < numVars) {
        if (boundVars(i).rhs.symbol == oldSym) {
          boundVars(i) = ValDef(boundVars(i).symbol, newInit)
          return
        }
        i += 1
      }
    }

    def newBoundVar(sym: Symbol, tpe: Type, init: Tree) {
      //if(sym == Symbol.NoSymbol ) {
      //  scala.Predef.Error("can't add variable with NoSymbol");
      //}
      //sym.setOwner( owner ); // FIXME should be corrected earlier
      //@maybe is corrected now? bq
      if (numVars == boundVars.length) {
        val newVars = new Array[ValDef](numVars * 2)
        Array.copy(boundVars, 0, newVars, 0, numVars)
        this.boundVars = newVars
      }
      sym.setInfo(tpe)
      this.boundVars(numVars) = ValDef(sym, init.duplicate)
      numVars += 1
    }

    def getBoundVars(): Array[ValDef] = {
      val newVars = new Array[ValDef](numVars)
      Array.copy(boundVars, 0, newVars, 0, numVars)
      newVars
    }

    override def equals(obj: Any): Boolean = {
      if (!(obj.isInstanceOf[CaseEnv]))
        return false
      val env = obj.asInstanceOf[CaseEnv]
      if (env.numVars != numVars)
        return false
      var i = 0; while (i < numVars) {
        if ((boundVars(i).name != env.boundVars(i).name) ||
	    !isSameType(boundVars(i).tpe, env.boundVars(i).tpe) ||
	    (boundVars(i).rhs != env.boundVars(i).rhs))
	  return false
        i += 1
      }
      true
    }

  } // class CaseEnv

}
