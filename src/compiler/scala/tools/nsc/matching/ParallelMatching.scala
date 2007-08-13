/* NSC -- new Scala compiler
 * Copyright 2005-2007 LAMP/EPFL
 * @author Burak Emir
 */
// $Id$

package scala.tools.nsc.matching

import scala.tools.nsc.util.Position
import collection.mutable.ListBuffer

/** Translation of Match Expressions
 *
 *  `bx': body index
 *
 *  @author Burak Emir
 */
trait ParallelMatching  {
  self: transform.ExplicitOuter with PatternMatchers with PatternNodes with CodeFactory =>

  import global._
  import typer.typed
  import symtab.Flags

  def isCaseClass(tpe: Type) = 
    tpe match {
      case TypeRef(_, sym, _) =>
        if(!sym.isAliasType) 
          sym.hasFlag(symtab.Flags.CASE)
        else
          tpe.normalize.typeSymbol.hasFlag(symtab.Flags.CASE)
      case _ => false
    } 

  // ----------------------------------   data 

  sealed trait RuleApplication {
    def scrutinee:Symbol
  }
  case class ErrorRule extends RuleApplication {
    def scrutinee:Symbol = throw new RuntimeException("this never happens")
  }
  case class VariableRule(subst:Binding, guard: Tree, guardedRest:Rep, bx: Int) extends RuleApplication {
    def scrutinee:Symbol = throw new RuntimeException("this never happens")
  }

  /** here, we distinguish which rewrite rule to apply
   */
  def MixtureRule(scrutinee:Symbol, column:List[Tree], rest:Rep): RuleApplication = {
    def isSimpleIntSwitch: Boolean = {
      (isSameType(scrutinee.tpe.widen, definitions.IntClass.tpe)/*||
       isSameType(scrutinee.tpe.widen, definitions.CharClass.tpe)*/) && {
        var xs = column
        while(!xs.isEmpty) { // forall
          val h = xs.head
          if(h.isInstanceOf[Literal] || isDefaultPattern(h)) { xs = xs.tail } else return false
        }
        return true
      }}
    // an unapply for which we don't need a type test
    def isUnapplyHead: Boolean = {
      def isUnapply(x:Tree): Boolean = x match {
        case Bind(_,p) => isUnapply(p)
        case UnApply(Apply(fn,_),arg) => fn.tpe match {
          case MethodType(List(argtpe,_*),_) =>     
            //Console.println("scrutinee.tpe "+scrutinee.tpe)
            //Console.println("argtpe "+argtpe)
            val r = scrutinee.tpe <:< argtpe
            //Console.println("<: ???"+r)
          r
          case _                          =>  
            //Console.println("wrong tpe")
            false
        }
        case x => 
          //Console.println("is something else"+x+" "+x.getClass)
          false
      }
      isUnapply(column.head)
    }

    // true if pattern type is direct subtype of scrutinee (can't use just <:< cause have to take variance into account)
    def directSubtype(ptpe:Type) =
      (ptpe.parents.exists { x => ((x.typeSymbol eq scrutinee.tpe.typeSymbol) && (x <:< scrutinee.tpe))});

    // true if each pattern type is case and direct subtype of scrutinee
    def isFlatCases(col:List[Tree]): Boolean = (col eq Nil) || {
      strip2(col.head) match {
        case a @ Apply(fn,_) => 
          isCaseClass(a.tpe) && directSubtype( a.tpe ) && isFlatCases(col.tail)
        case t @ Typed(_,tpt) =>
          isCaseClass(tpt.tpe) && directSubtype( t.tpe ) && isFlatCases(col.tail)
        case Ident(nme.WILDCARD) =>
           isFlatCases(col.tail) // treat col.tail specially?
        case i @ Ident(n) => // n ne nme.WILDCARD 
          assert(false)
          (  (i.symbol.flags & Flags.CASE) != 0) && directSubtype( i.tpe ) && isFlatCases(col.tail)
        case s @ Select(_,_) => // i.e. scala.Nil
          assert(false)
          (  (s.symbol.flags & Flags.CASE) != 0) && directSubtype( s.tpe ) && isFlatCases(col.tail)
        case p =>
          //Console.println(p.getClass)
          false
      }
    }
    //def containsUnapply = column exists { _.isInstanceOf[UnApply] }
    /*
    if(settings_debug) {
      Console.println("/// MixtureRule("+scrutinee.name+":"+scrutinee.tpe+","+column+", rep = ")
      Console.println(rest)
      Console.println(")///")
    }
    */
    if(isSimpleIntSwitch) {
      if(settings_debug) { Console.println("\n%%% MixLiterals") }
      return new MixLiterals(scrutinee, column, rest)
    } 
     if(settings_casetags && (column.length > 1) && isFlatCases(column)) {
       if(settings_debug) {
         Console.println("flat cases!"+column)
         Console.println(scrutinee.tpe.typeSymbol.children)
         Console.println(scrutinee.tpe.member(nme.tag))
         //Console.println(column.map { x => x.tpe./*?type?*/symbol.tag })
       }
      if(settings_debug) { Console.println("\n%%% MixCases") }
       return new MixCases(scrutinee, column, rest)
       // if(scrutinee.tpe./*?type?*/symbol.hasFlag(symtab.Flags.SEALED)) new MixCasesSealed(scrutinee, column, rest) 
       // else new MixCases(scrutinee, column, rest) 
    }
    //Console.println("isUnapplyHead")
    if(isUnapplyHead) {
      if(settings_debug) { Console.println("\n%%% MixUnapply") }
      return new MixUnapply(scrutinee, column, rest)
    }
    
    if(settings_debug) { Console.println("\n%%% MixTypes") }
    return new MixTypes(scrutinee, column, rest) // todo: handle type tests in unapply
  }


  abstract class CaseRuleApplication extends RuleApplication {

    def column: List[Tree]
    def rest:Rep

    // e.g. (1,1) (1,3) (42,2) for column {case ..1.. => ;; case ..42..=> ;; case ..1.. => }
    protected var defaults: List[Int]    = Nil
    var defaultV: collection.immutable.Set[Symbol] = emptySymbolSet
    
    var theDefaultRows: List[Row] = null
    def getDefaultRows: List[Row] = {
      if(theDefaultRows ne null)
        return theDefaultRows
      var res:List[Row] = Nil
      var ds = defaults; while(ds ne Nil) {
        res = grabRow(ds.head) :: res
        ds = ds.tail
      }
      theDefaultRows = res
      res
    }

    // sorted e.g. case _ => 7,5,1
    protected def insertDefault(tag: Int,vs:Set[Symbol]) { 
      defaultV = defaultV ++ vs
      defaults = insertSorted(tag, defaults)
    }
    protected def haveDefault: Boolean = !defaults.isEmpty    

    var tagIndexPairs: TagIndexPair = null
    
    protected def grabTemps: List[Symbol] = rest.temp
    protected def grabRow(index:Int): Row = rest.row(index) match {
      case r @ Row(pats, s, g, bx) => if(defaultV.isEmpty) r else {
        val vs = strip1(column(index))  // get vars
        val nbindings = vs.toList.map { v => (v,scrutinee) } ::: s
        Row(pats, nbindings, g, bx)
      }
    }

    /** inserts row indices using in to list of tagindexpairs*/
    protected def tagIndicesToReps = {
      val defaultRows = getDefaultRows
      var trs:List[(Int,Rep)] = Nil
      var old = tagIndexPairs
      while(tagIndexPairs ne null) { // collect all with same tag
        val tag = tagIndexPairs.tag
        var tagRows = this.grabRow(tagIndexPairs.index)::Nil
        tagIndexPairs = tagIndexPairs.next
        while((tagIndexPairs ne null) && tagIndexPairs.tag == tag) {
          tagRows = this.grabRow(tagIndexPairs.index)::tagRows
          tagIndexPairs = tagIndexPairs.next
        }
        trs = (tag, Rep(this.grabTemps, tagRows ::: defaultRows)) :: trs
      }
      tagIndexPairs = old
      trs
    }

    protected def defaultsToRep = {
      Rep(rest.temp, getDefaultRows)
    }

    protected def insertTagIndexPair(tag: Int, index: Int) {
      tagIndexPairs = TagIndexPair.insert(tagIndexPairs, tag, index)
    }

    /** returns 
     *  @return list of continuations, 
     *  @return variables bound to default continuation,  
     *  @return optionally, a default continuation,  
     **/
    def getTransition(implicit theOwner: Symbol): (List[(Int,Rep)],Set[Symbol],Option[Rep]) = 
      (tagIndicesToReps, defaultV, {if(haveDefault) Some(defaultsToRep) else None})
  }

  /**
   *  mixture rule for flat case class (using tags)
   *
   *  this rule gets translated to a switch of _.$tag()
  **/
  class MixCases(val scrutinee:Symbol, val column:List[Tree], val rest:Rep) extends CaseRuleApplication {

    /** insert row indices using in to list of tagindexpairs */
    {
      var xs = column
      var i  = 0
      while(xs ne Nil) { // forall
        val p = strip2(xs.head)
        if(isDefaultPattern(p)) 
          insertDefault(i,strip1(xs.head))
        else 
          insertTagIndexPair( getCaseTag(p.tpe), i)
        i += 1
        xs = xs.tail
      }
    }

    override def grabTemps = scrutinee::rest.temp
    override def grabRow(index:Int) = rest.row(tagIndexPairs.index) match {
      case Row(pats, s, g, bx) => 
        val nbindings = (strip1(column(index)).toList.map { v => (v,scrutinee) }) ::: s
        Row(column(tagIndexPairs.index)::pats, nbindings, g, bx)
    }

  }

  /**
   *  mixture rule for flat case class (using tags)
   *
   *  this rule gets translated to a switch of _.$tag()
  class MixCasesSealed(scrut:Symbol, col:List[Tree], res:Rep) extends MixCases(scrut, col, res) {
    override def grabTemps = rest.temp
    override def grabRow(index:Int) = rest.row(tagIndexPairs.index) 
  }
  **/
  
  /**
   *  mixture rule for literals
   *
   *  this rule gets translated to a switch. all defaults/same literals are collected
  **/
  class MixLiterals(val scrutinee:Symbol, val column:List[Tree], val rest:Rep) extends CaseRuleApplication {

    private var defaultIndexSet: Set64 = if(column.length < 64) new Set64 else null
    
    override def insertDefault(tag: Int, vs: Set[Symbol]): Unit = 
      if(defaultIndexSet eq null) 
        super.insertDefault(tag,vs)
      else {
        defaultIndexSet |= tag
        defaultV = defaultV ++ vs
      }

    protected override def haveDefault: Boolean = 
      if(defaultIndexSet eq null) super.haveDefault else (defaultIndexSet.underlying != 0)

    override def getDefaultRows: List[Row] = {
      if(theDefaultRows ne null)
        return theDefaultRows
      
      if(defaultIndexSet eq null) 
        super.getDefaultRows
      else {
        var ix = 63
        var res:List[Row] = Nil
        while((ix >= 0) && !defaultIndexSet.contains(ix)) { ix = ix - 1 } 
        while(ix >= 0) {
          res = grabRow(ix) :: res
          ix = ix - 1
          while((ix >= 0) && !defaultIndexSet.contains(ix)) { ix = ix - 1 } 
        }
        theDefaultRows = res
        res
      }
    }

    private def sanity(pos:Position,pvars:Set[Symbol]) {
      if(!pvars.isEmpty) cunit.error(pos, "nonsensical variable binding")
    } 

    { 
      var xs = column
      var i  = 0;
      while(xs ne Nil) { // forall
        strip(xs.head) match {
          case (pvars, p @ Literal(Constant(c:Int)))  => sanity(p.pos, pvars); insertTagIndexPair(c,i)
          case (pvars, p @ Literal(Constant(c:Char))) => sanity(p.pos, pvars); insertTagIndexPair(c.toInt,i)
          case (pvars, p )     if isDefaultPattern(p) => insertDefault(i,pvars)
        }
        i += 1
        xs = xs.tail
      }
    }

  }
  
  /**
   * mixture rule for unapply pattern
   */
  class MixUnapply(val scrutinee:Symbol, val column:List[Tree], val rest:Rep) extends RuleApplication {

    def newVarCapture(pos:Position,tpe:Type)(implicit theOwner:Symbol) = {
      val v = newVar(pos,tpe)
      if(scrutinee.hasFlag(symtab.Flags.CAPTURED))
        v.setFlag(symtab.Flags.CAPTURED) // propagate "unchecked"
      v
    }

    private def bindToScrutinee(x:Symbol) = typedValDef(x,Ident(scrutinee))

    val unapp = strip2(column.head)
    /** returns the (un)apply and two continuations */
    var rootvdefs:List[Tree] = Nil // later, via bindToScrutinee

    final def getTransition(implicit theOwner: Symbol): (Tree, List[Tree], Rep, Option[Rep]) = {
      unapp match {
        case ua @ UnApply(app @ Apply(fn, appargs), args) => 
          val ures = newVarCapture(ua.pos, app.tpe)
          val n    = args.length
          val uacall = ValDef(ures, Apply(fn, Ident(scrutinee) :: appargs.tail)) 
          //Console.println("uacall:"+uacall)
        
          val nrowsOther = column.tail.zip(rest.row.tail) flatMap { case (pat, Row(ps, subst, g, bx)) => strip2(pat) match { 
            case UnApply(app @ Apply(fn1,_),args) if fn.symbol==fn1.symbol => Nil
            case p                                                         => List(Row(pat::ps, subst, g, bx))
          }}
          val nrepFail = if(nrowsOther.isEmpty) None else Some(Rep(scrutinee::rest.temp, nrowsOther))
          //Console.println("active = "+column.head+" / nrepFail = "+nrepFail)
          n match {
            case 0  => //special case for unapply(), app.tpe is boolean
              val ntemps = scrutinee :: rest.temp
              val nrows  = column.zip(rest.row) map { case (pat, Row(ps, subst, g, bx)) => strip2(pat) match { 
                case UnApply(Apply(fn1,_),args) if (fn.symbol == fn1.symbol) => 
                  rootvdefs = (strip1(pat).toList map bindToScrutinee) ::: rootvdefs
                  Row(EmptyTree::ps, subst, g, bx)
                case _                                                     => 
                  Row(     pat ::ps, subst, g, bx)
              }}
              (uacall, rootvdefs, Rep(ntemps, nrows), nrepFail)
 
            case  1 => //special case for unapply(p), app.tpe is Option[T]
              val vtpe = app.tpe.typeArgs(0)
              val vsym = newVarCapture(ua.pos, vtpe)
              val ntemps = vsym :: scrutinee :: rest.temp
              val nrows = column.zip(rest.row) map { case (pat, Row(ps, subst, g, bx)) => strip2(pat) match { 
                case UnApply(Apply(fn1,_),args) if (fn.symbol == fn1.symbol) => 
                  rootvdefs = (strip1(pat).toList map bindToScrutinee) ::: rootvdefs
                  Row(args(0)   :: EmptyTree :: ps, subst, g, bx)
                case _                                                           => 
                  Row(EmptyTree ::  pat      :: ps, subst, g, bx)
              }}
              (uacall, rootvdefs:::List( typedValDef(vsym, Select(Ident(ures), nme.get))), Rep(ntemps, nrows), nrepFail)

            case _ => // app.tpe is Option[? <: ProductN[T1,...,Tn]]
              val uresGet = newVarCapture(ua.pos, app.tpe.typeArgs(0))
              var vdefs = typedValDef(uresGet, Select(Ident(ures), nme.get))::Nil
              var ts = definitions.getProductArgs(uresGet.tpe).get
              var i = 1;
              //Console.println("typeargs"+ts)
              var vsyms:List[Symbol] = Nil
              var dummies:List[Tree] = Nil
              while(ts ne Nil) {
                val vtpe = ts.head
                val vchild = newVarCapture(ua.pos, vtpe)
                val accSym = definitions.productProj(uresGet, i)
                val rhs = typed(Apply(Select(Ident(uresGet), accSym), List())) // nsc !
                vdefs = typedValDef(vchild, rhs)::vdefs 
                vsyms   =  vchild  :: vsyms
                dummies = EmptyTree::dummies
                ts = ts.tail
                i += 1
              } 
              val ntemps  =  vsyms.reverse ::: scrutinee :: rest.temp
              dummies     = dummies.reverse
              val nrows = column.zip(rest.row) map { case (pat,Row(ps, subst, g, bx)) => strip2(pat) match { 
                case UnApply(Apply(fn1,_),args) if (fn.symbol == fn1.symbol) =>
                  rootvdefs = (strip1(pat).toList map bindToScrutinee) ::: rootvdefs
                  Row(   args::: EmptyTree ::ps, subst, g, bx)
                case _                                                       =>
                  Row(dummies:::     pat   ::ps, subst, g, bx)
              }}
              (uacall, rootvdefs:::vdefs.reverse, Rep(ntemps, nrows), nrepFail)
          }}
    }
  }

  /**
   *   mixture rule for type tests
  **/
  class MixTypes(val scrutinee:Symbol, val column:List[Tree], val rest:Rep) extends RuleApplication {
    
    var casted: Symbol = null
    var moreSpecific:   List[Tree]        = Nil
    var subsumed:  List[(Int,List[Tree])] = Nil // row index and subpatterns
    var remaining: List[(Int,Tree)]       = Nil // row index and pattern

    val isExhaustive = !scrutinee.tpe.typeSymbol.hasFlag(symtab.Flags.SEALED) || {
      //DEBUG("check exha for column "+column)
      val tpes = column.map {x => /*Console.println("--x:"+x+":"+x.tpe); */ x.tpe.typeSymbol}
      scrutinee.tpe.typeSymbol.children.forall { sym => tpes.contains(sym) }
    }

    private val patternType     = column.head match {
      case p @ (_:Ident | _:Select) => 
        singleType(p.symbol.tpe.prefix, p.symbol)  //ConstantType(p.tpe.singleton)
      //case p@Apply(_,_) if !p.tpe./*?type?*/symbol.hasFlag(symtab.Flags.CASE) => ConstantType(new NamedConstant(p))
      case _ => column.head.tpe
    }
    private val isCaseHead = isCaseClass(patternType)
    private val dummies = if(!isCaseHead) Nil else patternType.typeSymbol.caseFieldAccessors.map { x => EmptyTree }
  
    //Console.println("isCaseHead = "+isCaseHead)
    //Console.println("dummies = "+dummies)

    private def subpatterns(pat:Tree): List[Tree] = {
      //Console.print("subpatterns("+pat+")=")
      //val x = 
      pat match {
        case Bind(_,p)                                                          => 
          subpatterns(p)
        case app @ Apply(fn, pats) if isCaseClass(app.tpe) && (fn.symbol eq null)=> 
          if(isCaseHead) pats else dummies
        case Apply(fn,xs) => assert((xs.isEmpty) && (fn.symbol ne null), "strange Apply"); dummies // named constant
        case _: UnApply                                                         => 
          dummies
        case pat                                                                => 
          //Console.println("[class="+pat.getClass()+"]")
          dummies
      }
      //Console.println(x)
      //x
    }

    /** returns true if pattern tests an object */
    final def objectPattern(pat:Tree): Boolean = try {
      (pat.symbol ne null) && 
      (pat.symbol != NoSymbol) && 
      pat.symbol.tpe.prefix.isStable &&
      patternType =:= singleType(pat.symbol.tpe.prefix, pat.symbol)
    } catch {
      case e =>
        Console.println("object pattern test throws "+e.getMessage())
        if(settings_debug)
          System.exit(-1);
        throw e
    }
    //Console.println("scrutinee == "+scrutinee+":"+scrutinee.tpe)
    //Console.println("column.head == "+column.head);
    {
      var sr = (moreSpecific,subsumed,remaining)
      var j = 0; var pats = column; while(pats ne Nil) {
        val (ms,ss,rs) = sr // more specific, more general(subsuming current), remaining patterns
        val pat = pats.head 

        //Console.println("pat = "+pat+" (class "+pat.getClass+")of type "+pat.tpe)
        //Console.println("current pat is wild? = "+isDefault(pat))
        //Console.println("current pat.symbol = "+pat.symbol+", pat.tpe "+pat.tpe)
        //Console.println("patternType = "+patternType)
        //Console.println("(current)pat.tpe <:< patternType = "+(pat.tpe <:< patternType))
        //Console.println("patternType <:< (current)pat.tpe = "+(patternType <:< pat.tpe))
        //Console.println("(current)pat.tpe =:= patternType = "+(pat.tpe <:< patternType))

        sr = strip2(pat) match {
          // case _: Bind       => // cannot happen, using strip2
          // case a:Alternative => // cannot happen, alternatives should be preprocessed away

          case Literal(Constant(null)) if !(patternType =:= pat.tpe) => //special case for constant null pattern
            //Console.println("[1")
            (ms,ss,(j,pat)::rs);
          case _ if objectPattern(pat) =>
            //Console.println("[2")
            (EmptyTree::ms, (j,dummies)::ss, rs);                                 // matching an object
          
          case Typed(p:UnApply,_) if (pat.tpe <:< patternType) => 
            //Console.println("unapply arg is same or *more* specific")          
            (p::ms, (j, dummies)::ss, rs); 

          case q @ Typed(pp,_) if (pat.tpe <:< patternType) => 
            //Console.println("current pattern is same or *more* specific")          
            ({if(pat.tpe =:= patternType) EmptyTree else q}::ms, (j, dummies)::ss, rs); 

          case qq if (pat.tpe <:< patternType) && !isDefaultPattern(pat) => 
            //Console.println("current pattern is same or *more* specific")          
            ({if(pat.tpe =:= patternType) EmptyTree else pat}::ms, (j,subpatterns(pat))::ss, rs); 
          
          case _ if (patternType <:< pat.tpe) || isDefaultPattern(pat) => 
            //Console.println("current pattern is *more general*")
            (EmptyTree::ms, (j, dummies)::ss, (j,pat)::rs);                        // subsuming (matched *and* remaining pattern)
          
          case _ =>
            //Console.println("current pattern tests something else")
            (ms,ss,(j,pat)::rs)
        }
        j += 1
        pats = pats.tail
      }
      this.moreSpecific = sr._1.reverse
      this.subsumed     = sr._2.reverse
      this.remaining    = sr._3.reverse
      sr = null
    }

    override def toString = {
      "MixTypes("+scrutinee+":"+scrutinee.tpe+") {\n  moreSpecific:"+moreSpecific+"\n  subsumed:"+subsumed+"\n  remaining"+remaining+"\n}"
    }

    /** returns casted symbol, success matrix and optionally fail matrix for type test on the top of this column */
    final def getTransition(implicit theOwner: Symbol): (Symbol, Rep, Option[Rep]) = {
      //Console.println("*** getTransition! of "+this.toString)
      // the following works for type tests... what fudge is necessary for value comparisons?
      // type test
      casted = if(scrutinee.tpe =:= patternType) scrutinee else newVar(scrutinee.pos, patternType)
      if(scrutinee.hasFlag(symtab.Flags.CAPTURED))
        casted.setFlag(symtab.Flags.CAPTURED)
      // succeeding => transition to translate(subsumed) (taking into account more specific)
      val nmatrix = {
        //Console.println("casted:"+casted)
        //Console.println("casted.case:"+casted.tpe./*?type?*/symbol.hasFlag(symtab.Flags.CASE))
        var ntemps  = if(isCaseClass(casted.tpe)) casted.caseFieldAccessors map { 
          meth => 
            val ctemp = newVar(scrutinee.pos, casted.tpe.memberType(meth).resultType) 
            if(scrutinee.hasFlag(symtab.Flags.CAPTURED))
              ctemp.setFlag(symtab.Flags.CAPTURED)
            ctemp
        } else Nil // (***)
        var subtests = subsumed

//var moreSpecificIndices:Option[List[Int]] = None

        //Console.println("subtests BEFORE "+subtests)
        if(moreSpecific.exists { x => x != EmptyTree }) {
          //moreSpecificIndices = Some(Nil)
          ntemps   = casted::ntemps                                                                            // (***)
          subtests = moreSpecific.zip(subsumed) map { 
            case (mspat, (j,pats)) => 
              //moreSpecificIndices = Some(j::moreSpecificIndices)
              (j,mspat::pats) 
          }
         //Console.println("more specific, subtests "+subtests)
        }
        ntemps = ntemps ::: rest.temp
        val ntriples = subtests map {
          case (j,pats) => 
            val (vs,thePat) = strip(column(j))
            val Row(opats, osubst, og, bx) = rest.row(j)
            val subst1 = //if(!moreSpecificIndices.isEmpty && moreSpecificIndices.contains(j)) Nil /*morespecific?*/ else 
              vs.toList map { v => (v,casted) }
          //Console.println("j = "+j)
          //Console.println("pats:"+pats)
          //Console.println("opats:"+pats)
          //debug
          // don't substitute eagerly here, problems with bodies that can 
          //   be reached with several routes... impossible to find one-fits-all ordering.

            Row(pats ::: opats, subst1 ::: osubst, og, bx) 

          // BTW duplicating body/guard, gets you "defined twice" error cause hashing breaks
        }
        if(settings_debug) {
          Console.println("ntemps   = "+ntemps.mkString("[["," , ","]]"))
          Console.println("ntriples = "+ntriples.mkString("[[\n","\n, ","\n]]"))
        }
        Rep(ntemps, ntriples) /*setParent this*/
      }
      // fails      => transition to translate(remaining)

      val nmatrixFail: Option[Rep] = {
        val ntemps   = scrutinee :: rest.temp 
        //Console.println("nmatrixfail ntemps:"+ntemps)
        val ntriples = remaining map {
          case (j, pat) => val r = rest.row(j);  Row(pat :: r.pat, r.subst, r.guard, r.bx)
        }
        //Console.println("nmatrixfail triples:"+ntriples)
        if(ntriples.isEmpty) None else Some(Rep(ntemps, ntriples) /*setParent this*/)
      }
      if(!nmatrixFail.isEmpty) {
        //DEBUG("nmatrix for failing type test "+patternType)
        //DEBUG(nmatrixFail.get.toString)
      } else {
        //DEBUG("pattern type "+patternType+" cannot fail for "+scrutinee)
      }
      (casted, nmatrix, nmatrixFail)
    } // end getTransitions
  }


  final def genBody(subst: Binding, guard:Tree, rest:Tree, bx:Int)(implicit theOwner: Symbol): Tree = {
    //Rep.markReached(bx) // for unreachable-code-detection

    val rbody = typed { Rep.requestBody( bx, subst ) }
    val vdefs = targetParams(subst)

    val typedThen = rbody // origbody//typed{origbody.duplicate}

    //Console.println("typedThen = "+typedThen);
    val typedElse = rest//typed{rest}
    //Console.println("typedElse = "+typedElse);
    val untypedIf = makeIf(guard, typedThen, typedElse)
      //Console.println("typedIf   = "+typedIf);
      //val typedBlock = typed{ squeezedBlock(vdefs, typedIf) }
      //Console.println("typedBlock= "+typedBlock);
      //typedBlock
    val r = atPhase(phase.prev) { typed { squeezedBlock(vdefs, untypedIf) }}
    //Console.println("genBody-guard:"+r)
    r
  }

  final def genBody(subst: Binding, bx:Int)(implicit theOwner: Symbol): Tree = {
    typed{ Rep.requestBody(bx, subst) }
  }

  /** converts given rep to a tree - performs recursive call to translation in the process to get sub reps
   */
  final def repToTree(rep:Rep, handleOuter: Tree => Tree)(implicit theOwner: Symbol, failTree: Tree): Tree = {
    rep.applyRule match {
      case ErrorRule() => failTree
      case VariableRule(subst, EmptyTree, _, bx) => 
        genBody(subst, bx)

      case VariableRule(subst, g, restRep, bx) => 
        genBody(subst, g, repToTree(restRep, handleOuter),bx)

      case mc: MixCases =>
        val (branches, defaultV, default) = mc.getTransition // tag body pairs
        if (settings_debug) {
          Console.println("[[mix cases transition: branches \n"+(branches.mkString("","\n","")))
          Console.println("defaults:"+defaultV)
          Console.println(default)
          Console.println("]]")
        }

        var ndefault = if(default.isEmpty) failTree else repToTree(default.get, handleOuter)

        var cases = branches map { 
          case (tag, rep) => 
            CaseDef(Literal(tag), 
                    EmptyTree, 
                    {
                      val pat  = mc.column(mc.tagIndexPairs.find(tag));
                      val ptpe = pat.tpe
                      if(mc.scrutinee.tpe.typeSymbol.hasFlag(symtab.Flags.SEALED) && strip2(pat).isInstanceOf[Apply]) {
                        //cast 
                        val vtmp = newVar(pat.pos, ptpe)
                        squeezedBlock(
                          List(typedValDef(vtmp, gen.mkAsInstanceOf(Ident(mc.scrutinee),ptpe))),
                          repToTree(Rep(vtmp::rep.temp.tail, rep.row),handleOuter)
                        )
                      } else repToTree(rep, handleOuter)
                    }
                  )}

        renamingBind(defaultV, mc.scrutinee, ndefault) // each v in defaultV gets bound to scrutinee 
      
        // make first case a default case.
        if(mc.scrutinee.tpe.typeSymbol.hasFlag(symtab.Flags.SEALED) && defaultV.isEmpty) {
          ndefault = cases.head.body
          cases = cases.tail
        }

        if(cases.length == 0) {
          ndefault
        } else if(cases.length == 1) {
          val CaseDef(lit,_,body) = cases.head
          makeIf(Equals(Select(Ident(mc.scrutinee),nme.tag),lit), body, ndefault) // *
        } else {
          val defCase = CaseDef(mk_(definitions.IntClass.tpe), EmptyTree, ndefault)
          Match(Select(Ident(mc.scrutinee),nme.tag), cases :::  defCase :: Nil) // *
        }

      case ml: MixLiterals =>
        val (branches, defaultV, defaultRepOpt) = ml.getTransition // tag body pairs
        if(settings_debug) {
          Console.println("[[mix literal transition: branches \n"+(branches.mkString("","\n","")))
          Console.println("defaults:"+defaultV)
          Console.println(defaultRepOpt)
          Console.println("]]")
        }
        val cases = branches map { case (tag, rep) => CaseDef(Literal(tag), EmptyTree, repToTree(rep, handleOuter)) }
        var ndefault = if(defaultRepOpt.isEmpty) failTree else repToTree(defaultRepOpt.get, handleOuter)

        renamingBind(defaultV, ml.scrutinee, ndefault) // each v in defaultV gets bound to scrutinee 
        if(cases.length == 1) {
          val CaseDef(lit,_,body) = cases.head
          makeIf(Equals(Ident(ml.scrutinee),lit), body, ndefault)
        } else {
          val defCase = CaseDef(mk_(definitions.IntClass.tpe), EmptyTree, ndefault)
          var selector:Tree = Ident(ml.scrutinee)
          if(isSameType(ml.scrutinee.tpe.widen, definitions.CharClass.tpe))
            selector = gen.mkAsInstanceOf(selector, definitions.IntClass.tpe)
          Match(selector, cases :::  defCase :: Nil)
        }

      case mm:MixTypes =>
        val (casted,srep,frep) = mm.getTransition
        //Console.println("--- mixture \n succ \n"+srep.toString+"\n fail\n"+frep.toString)
        //val cond = typed{gen.mkIsInstanceOf(Ident(mm.scrutinee), casted.tpe)}
        //Console.println("casted.tpe="+casted.tpe)
        val condUntyped = condition(casted.tpe, mm.scrutinee)
        //Console.println("condUntyped:" + condUntyped)
        var cond = handleOuter(typed { condUntyped }) // this thing throws exceptions in some obscure situations
        if(needsOuterTest(casted.tpe, mm.scrutinee.tpe)) // @todo merrge into def condition
          cond = addOuterCondition(cond, casted.tpe, typed{Ident(mm.scrutinee)}, handleOuter)

        val succ = repToTree(srep, handleOuter)

        val fail = if(frep.isEmpty) failTree else repToTree(frep.get, handleOuter)

        // dig out case field accessors that were buried in (***)
        val cfa  = casted.caseFieldAccessors
        //DEBUG("case field accessors, casted = "+casted.toString)
        //DEBUG("case field accessors, the things themselves = "+cfa.toString)
        val caseTemps = (if(!srep.temp.isEmpty && srep.temp.head == casted) srep.temp.tail else srep.temp).zip(cfa)

        //DEBUG("case temps"+caseTemps.toString)
        var vdefs     = caseTemps map { 
          case (tmp,meth) => 
            val typedAccess = typed { Apply(Select(typed{Ident(casted)}, meth),List()) }
            typedValDef(tmp, typedAccess)
        }

        if(casted ne mm.scrutinee) {
          vdefs = ValDef(casted, gen.mkAsInstanceOf(typed{Ident(mm.scrutinee)}, casted.tpe)) :: vdefs
        }
        typed { makeIf(cond, squeezedBlock(vdefs,succ), fail) }

      case mu: MixUnapply =>
        val (uacall/*:ValDef*/ , vdefs,srep,frep) = mu.getTransition // uacall is a Valdef
        //Console.println("getTransition"+(uacall,vdefs,srep,frep))
        val succ = repToTree(srep, handleOuter)
        val fail = if(frep.isEmpty) failTree else repToTree(frep.get, handleOuter)
        val cond = if(uacall.symbol.tpe.typeSymbol eq definitions.BooleanClass)
          typed{ Ident(uacall.symbol) }
                   else 
                     emptynessCheck(uacall.symbol)
        typed { squeezedBlock(List(handleOuter(uacall)), makeIf(cond,squeezedBlock(vdefs,succ),fail)) }
    }
  }

  /** subst: the bindings so far */
  case class Row(pat:List[Tree], subst:Binding, guard:Tree, bx:Int)

object Rep {
  type RepType = Product2[List[Symbol], List[Row]]
  final def unapply(x:Rep):Option[RepType] = 
    if(x.isInstanceOf[RepImpl]) Some(x.asInstanceOf[RepImpl]) else None
  
  private case class RepImpl(val temp:List[Symbol], val row:List[Row]) extends Rep with RepType {
    (row.find { case Row(pats, _, _, _) => temp.length != pats.length }) match {
      case Some(row) => assert(false, "temp == "+temp+" row.pats == "+row.pat);
      case _ =>
    }
    def _1 = temp
    def _2 = row
  }

  var vss: List[SymList] = _
  var labels:  Array[Symbol] = new Array[Symbol](4)
  var targets: List[Tree] = _
  var reached64: Set64 = _
  var reached: List[Int] = Nil

  final def apply(temp:List[Symbol], row:List[Row], targets: List[Tree], vss:List[SymList]): Rep = {
    this.targets   = targets
    if(targets.length > labels.length) 
      this.labels    = new Array[Symbol](targets.length)
    this.vss       = vss
    this.reached64 = if(targets.length < 64) new Set64 else null
    return apply(temp, row)
  }

  final def cleanup(tree: Tree)(implicit theOwner: Symbol): Tree = { 
    object lxtt extends Transformer {
      override def transform(tree:Tree): Tree = tree match {
        case blck @ Block(vdefs, ld @ LabelDef(name,params,body)) =>
          val bx = labelIndex(ld.symbol)
          if((bx >= 0) && !isReachedTwice(bx))
            squeezedBlock(vdefs,body)
          else 
            blck
        case t => super.transform(t)
      }
    }
    val res = lxtt.transform(tree)
    cleanup()
    res
  }
  final def cleanup() {
    var i = targets.length; 
    while (i>0) { i-=1; labels(i) = null; }; 
    reached = Nil 
  }
  final def isReached(bx:Int)   = { /*Console.println("isReached("+bx+")"); */labels(bx) ne null }
  final def markReachedTwice(bx:Int) = if(reached64 ne null) { reached64 |= bx } else { reached = insertSorted(bx, reached) }
  /** @pre labelIndex(bx) != -1 */
  final def isReachedTwice(bx:Int) = if(reached64 ne null) { reached64 contains bx } else { findSorted(bx,reached) }
  
  /* @returns bx such that labels(bx) eq label, -1 if no such bx exists */
  final def labelIndex(label:Symbol): Int = {
    var bx = 0; while((bx < labels.length) && (labels(bx) ne label)) { bx += 1 }
    if(bx >= targets.length) bx = -1
    return bx
  }
  /** first time bx is requested, a LabelDef is returned. next time, a jump.
   *  the function takes care of binding
   */
  final def requestBody(bx:Int, subst:Binding)(implicit theOwner: Symbol): Tree = {
    //Console.println("requestbody("+bx+", "+subst+")")
    if(!isReached(bx)) { // first time this bx is requested
      val argts = new ListBuffer[Type] // types of
      var vrev: List[Symbol] = Nil
      var vdefs:List[Tree] = Nil
      val it = vss(bx).elements; while(it.hasNext) {
        val v = it.next
        val substv = subst(v)
        if(substv ne null) {// might be bound elsewhere ( see `x @ unapply' )
          vrev   = v :: vrev 
          argts += v.tpe
          vdefs  = typedValDef(v, substv)::vdefs
        } 
      }
      val body  = targets(bx)
      val label = theOwner.newLabel(body.pos, "body%"+bx).setInfo(new MethodType(argts.toList, body.tpe/*resultType*/))
      //Console.println("label.tpe"+label.tpe)
      labels(bx) = label
      //Console.println("- !isReached returning LabelDef "+label)
      //Console.println("- !      and vdefs "+vdefs)
      return Block(vdefs, LabelDef(label, vrev.reverse, body))
    } 
 
    //Console.println("-  isReached before, jump to "+labels(bx))
    // jump
    markReachedTwice(bx) // if some bx is not reached twice, its LabelDef
    val args = new ListBuffer[Ident] // is replaces with body itself
    var vs   = vss(bx).elements; while(vs.hasNext) {
      val substv = subst(vs.next)
      assert(substv ne null) // if sharing takes place, then 'binding elsewhere' is not allowed
      args += substv
    }
    val label = labels(bx)
    label.tpe match {
      case MethodType(fmls,_) => 
        if (fmls.length != args.length) { // sanity check
          cunit.error(targets(bx).pos, "consistency problem ! "+fmls)
          System.exit(-1)
        }
        for((f,a) <- fmls.zip(args.toList)) {
          if(!(a.tpe <:< f)) {
            cunit.error(targets(bx).pos, "consistency problem ! "+a.tpe+" "+f)
            System.exit(-1)
          }
        }
    }
    return Apply(Ident(label),args.toList)
  }

  /** the injection here handles alternatives and unapply type tests */
  final def apply(temp:List[Symbol], row1:List[Row]): Rep = {
    //if(temp.isEmpty && row1.length > 1) return apply(Nil, row1.head::Nil) // small standalone optimization, we won't need other rows (check guard?)
    var unchanged: Boolean = true
    val row = row1 flatMap { 
      xx => 
        def isAlternative(p: Tree): Boolean = p match { 
          case Bind(_,p)       => isAlternative(p)
          case Alternative(ps) => true 
          case _               => false
        }
        def getAlternativeBranches(p:Tree): List[Tree] = {
          def get_BIND(pctx:Tree => Tree, p:Tree):List[Tree] = p match { 
            case b @ Bind(n,p)   => get_BIND({ x:Tree => pctx(copy.Bind(b, n, x) setType x.tpe) }, p)
            case Alternative(ps) => ps map pctx
          }
          get_BIND({x=>x}, p)
        }
        val Row(opatso, subst, g, bx) = xx
        var opats = opatso
        var pats:List[Tree] = Nil
        var indexOfAlternative = -1
        var j = 0; while(opats ne Nil) {
          //Console.println("opats.head = "+opats.head.getClass)
          opats.head match {
            case p if isAlternative(p) && indexOfAlternative == -1 => 
              indexOfAlternative = j
              unchanged = false
              pats = p :: pats

            case typat @ Typed(p:UnApply,tpt) => 
              pats = (if (temp(j).tpe <:< tpt.tpe) p else typat)::pats // what about the null-check?

            case o @ Ident(n) if n != nme.WILDCARD =>
              /*
               Console.println("/'''''''''''' 1"+o.tpe)
               Console.println("/'''''''''''' 2"+o.symbol)
               Console.println("/'''''''''''' 3"+o.symbol.tpe)
               Console.println("/'''''''''''' 4"+o.symbol.tpe.prefix)
               Console.println("/'''''''''''' 5"+o.symbol.tpe.prefix.isStable)
               
               Console.println("/'''''''''''' 6"+(o.symbol.tpe.typeSymbol hasFlag (Flags.CASE)))
               Console.println("/'''''''''''' 7"+(o.symbol.tpe.termSymbol.hasFlag (Flags.CASE)))
               */
              val stpe = 
                if (!o.symbol.isValue) {
		          singleType(o.tpe.prefix, o.symbol)
                } else {
                  singleType(NoPrefix, o.symbol) // equals-check
                }
              val p = Ident(nme.WILDCARD) setType stpe
              val q = Typed(p, TypeTree(stpe)) setType stpe
              pats = q::pats 

            case o @ Ident(nme.WILDCARD) => 
              pats = o::pats

            case o @ Select(_,_) =>
              /*
               Console.println("!/'''''''''''' 1"+o.tpe)
               Console.println("!/'''''''''''' 2"+o.symbol)
               Console.println("!/'''''''''''' 3"+o.symbol.tpe)
               Console.println("!'''''''''''' 4"+o.symbol.tpe.prefix)
               Console.println("!/'''''''''''' 5"+o.symbol.tpe.prefix.isStable)
               
               Console.println("!/'''''''''''' 6"+(o.symbol.tpe.typeSymbol hasFlag (Flags.CASE)))
               Console.println("!/'''''''''''' 7"+(o.symbol.tpe.termSymbol.hasFlag (Flags.CASE)))
               */
              val stpe = 
                if (!o.symbol.isValue) {
		          singleType(o.tpe.prefix, o.symbol)
                } else {
                  singleType(NoPrefix, o.symbol) // equals-check
                }
              val p = Ident(nme.WILDCARD) setType stpe
              val q = Typed(p, TypeTree(stpe)) setType stpe
              pats = q::pats 

            case ua @ UnApply(Apply(fn, _), arg) => 
              fn.tpe match {
                case MethodType(List(argtpe,_*),_) => 
                  pats = (if (temp(j).tpe <:< argtpe) ua else Typed(ua,TypeTree(argtpe)).setType(argtpe))::pats
              }

            /** something too tricky is going on if the outer types don't match
             */
            case o @ Apply(app, List()) if !isCaseClass(o.tpe) => 
              System.out.println("this should not happen (experimental code)")
              throw new RuntimeException("non-case apply encountered in parallelmatching")
              //Console.println(o)
              //val stpe = singleType(NoPrefix, o.symbol)
              val stpe =
                if (o.tpe.termSymbol.isModule) singleType(o.tpe.prefix, o.symbol)
                else {
                  singleType(NoPrefix, o.symbol) // equals-check 
                }
              val p = Ident(nme.WILDCARD) setType stpe
              val q = Typed(p, TypeTree(stpe)) setType stpe
              pats = q::pats 

            case o @ Apply(app, Nil) => //  no args case class pattern 
              assert(isCaseClass(o.tpe))
              val q = Typed(EmptyTree, TypeTree(o.tpe)) setType o.tpe
              pats = q :: pats

            case p =>
              pats = p :: pats
          }
          opats = opats.tail
          j += 1
        }
        pats = pats.reverse
        //i = pats findIndexOf isAlternative
        if (indexOfAlternative == -1)
          List(Row(pats, subst, g, bx))
        else {
          val prefix = pats.take( indexOfAlternative )
          val alts   = getAlternativeBranches(pats( indexOfAlternative ))
          val suffix = pats.drop(indexOfAlternative + 1)
          alts map { p => Row(prefix ::: p :: suffix, subst, g, bx) }
        }
    }
    if (unchanged) {
      val ri = RepImpl(temp,row).init
      //Console.println("Rep.apply / ri = "+ri)
      ri
    } else {
      //Console.println("Rep.apply / recursive ")
      Rep(temp,row) // recursive call
    }
  }
}

  abstract class Rep {
    val temp:List[Symbol]
    val row:List[Row] // (List[Tree], List[(Symbol,Symbol)], Tree, Tree)

    var sealedCols = List[Int]()
    var sealedComb = List[Set[Symbol]]()
    //Console.println(" the matrix "+this.toString)

    final def init: this.type = {
      temp.zipWithIndex.foreach {
      case (sym,i) => 
        //Console.println("sym! "+sym+" mutable? "+sym.hasFlag(symtab.Flags.MUTABLE)+" captured? "+sym.hasFlag(symtab.Flags.CAPTURED))
        if (sym.hasFlag(symtab.Flags.MUTABLE) &&  // indicates that have not yet checked exhaustivity
            !sym.hasFlag(symtab.Flags.CAPTURED) &&  // indicates @unchecked
            sym.tpe.typeSymbol.hasFlag(symtab.Flags.SEALED)) {
              
              sym.resetFlag(symtab.Flags.MUTABLE)
              sealedCols = i::sealedCols
              // this should enumerate all cases... however, also the superclass is taken if it is not abstract
              def candidates(tpesym: Symbol): SymSet = 
                if(!tpesym.hasFlag(symtab.Flags.SEALED)) emptySymbolSet else 
                  tpesym.children.flatMap { x => 
                    val z = candidates(x) 
                    if(x.hasFlag(symtab.Flags.ABSTRACT)) z else z + x 
                  }
              val cases = candidates(sym.tpe.typeSymbol)
              sealedComb = cases::sealedComb
            }
      }

      //  computes cartesian product, keeps indices available
      def combine(colcom: List[(Int,Set[Symbol])]): List[List[(Int,Symbol)]] = colcom match {
        case Nil => Nil
        case (i,syms)::Nil => syms.toList.map { sym => List((i,sym)) }
        case (i,syms)::cs  => for (s <- syms.toList; rest <- combine(cs)) yield (i,s) :: rest
      }
      
      if(!sealedCols.isEmpty) {
        //Console.println("cols"+sealedCols)
        //DEBUG("comb")
        //for (com <- sealedComb) //DEBUG(com.toString)
        
        val allcomb = combine(sealedCols zip sealedComb)
        //Console.println("all comb!" + allcomb)
        /** returns true if pattern vector pats covers a type symbols "combination"
         *  @param pats pattern vector
         *  @param comb pairs of (column index, type symbol)
         */
        def covers(pats: List[Tree], comb:List[(Int,Symbol)]) = 
          comb forall { 
            case (i,sym) => 
              val p = strip2(pats(i)); 
            val res = 
              isDefaultPattern(p) || {
                val symtpe = if(sym.hasFlag(symtab.Flags.MODULE)) {
                  singleType(sym.tpe.prefix, sym.linkedModuleOfClass) // e.g. None, Nil
                } else sym.tpe
                //Console.print("covers: sym="+sym+" symtpe="+symtpe+" p="+p+", p.tpe="+p.tpe+" ?")
                (p.tpe.typeSymbol == sym) || (symtpe <:< p.tpe) || 
                /* outer, see scala.util.parsing.combinator.lexical.Scanner */
                (p.tpe.prefix.memberType(sym) <:< p.tpe)
              }
            //Console.println(res)
            res
          }

        val coversAll = allcomb forall { combination => row exists { r => covers(r.pat, combination)}}
        //Console.println("all combinations covered? "+coversAll)
        if(!coversAll) {
          val sb = new StringBuilder()
          sb.append("match is not exhaustive!\n")
          for (open <- allcomb if !(row exists { r => covers(r.pat, open)})) {
            sb.append("missing combination ")
            val NPAD = 15
            def pad(s:String) = { 1.until(NPAD - s.length).foreach { x => sb.append(" ") }; sb.append(s) }
            List.range(0, temp.length) foreach {
              i => open.find { case (j,sym) => j==i } match {
                case None => pad("*")
                case Some((_,sym)) => pad(sym.name.toString)
              }
            }
            sb.append('\n')
          }
          cunit.warning(temp.head.pos, sb.toString)
        }
      }
      //if(settings_debug) Console.println("init done, rep = "+this.toString)
      return this
    } // end init
    
/*
    final def applyRule: RuleApplication = { 
      Console.println("entering applyRule!")
      val res = applyRule1
      Console.println("leaving applyRule!")
      res
    }
*/
    final def applyRule: RuleApplication = row match {
      case Nil =>
        ErrorRule
      case Row(pats, subst, g, bx)::xs => 
        if(pats forall isDefaultPattern) {
          val subst1 = pats.zip(temp) flatMap { 
            case (p,tmp) => 
              strip1(p).toList.zipAll(Nil,null,tmp) // == vars map { (v,tmp) }
          }
          VariableRule (subst1 ::: subst, g, Rep(temp, xs), bx)
        } else {
          val i = pats findIndexOf {x => !isDefaultPattern(x)}
          
          val column = row map { case Row(pats,_,_,_) => pats(i) }
          
          val restTemp =                                           temp.take(i) ::: temp.drop(i+1)
          val restRows = row map { case Row(pats, subst, g, bx) => Row(pats.take(i) ::: pats.drop(i+1), subst, g, bx) }
          
          MixtureRule(temp(i), column, Rep(restTemp,restRows))
        }
    } // applyRule

      // a fancy toString method for debugging
    override final def toString = {
      val sb   = new StringBuilder
      val NPAD = 15
      def pad(s:String) = { 1.until(NPAD - s.length).foreach { x => sb.append(" ") }; sb.append(s) }
      for (tmp <- temp) pad(tmp.name.toString)
      sb.append('\n')
      for ((r,i) <- row.zipWithIndex) {
        for (c <- r.pat ::: List(r.subst, r.guard, r.bx)) {
          pad(c.toString)
        }
        sb.append('\n')
      }
      sb.toString
    }
  }
  
  /** creates initial clause matrix
   */
  final def initRep(selector: Tree, cases: List[Tree], checkExhaustive: Boolean)(implicit theOwner: Symbol) = {
    val root = newVar(selector.pos, selector.tpe)
    // communicate whether exhaustiveness-checking is enabled via some flag
    if (!checkExhaustive)
      root.setFlag(symtab.Flags.CAPTURED)
    var bx = 0;
    val targets = new ListBuffer[Tree]
    val vss = new ListBuffer[SymList] 
    val row = new ListBuffer[Row]

    var cs = cases; while (cs ne Nil) cs.head match {  // stash away pvars and bodies for later
      case CaseDef(pat,g,b) => 
        vss     += definedVars(pat)
        targets += b
        row     += Row(List(pat), NoBinding, g, bx) 
        bx      += 1
        cs = cs.tail
    }
    //Console.println("leaving initRep")
    /*val res = */Rep(List(root), row.toList, targets.toList, vss.toList)
    //Console.println("left initRep")
    //res
  }

  // ----------------------------------   helper functions that extract information from patterns, symbols, types

  /** returns if pattern can be considered a no-op test ??for expected type?? */
  final def isDefaultPattern(pattern:Tree): Boolean = pattern match {
    case Bind(_, p)            => isDefaultPattern(p)
    case EmptyTree             => true // dummy
    case Ident(nme.WILDCARD)   => true
    case _                     => false
// -- what about the following? still have to test "ne null" :/
//  case Typed(nme.WILDCARD,_) => pattern.tpe <:< scrutinee.tpe
  }
  
  // ----------------------------------   helper functions that generate symbols, trees for type tests, pattern tests

  final def newVar(pos: Position, name: Name, tpe: Type)(implicit theOwner: Symbol): Symbol = {
    if(tpe eq null) assert(tpe ne null, "newVar("+name+", null)")
    val sym = theOwner.newVariable(pos, name) // careful: pos has special meaning
    sym setFlag symtab.Flags.TRANS_FLAG // should eventually be used for avoiding non-null checks?
    sym setInfo tpe
    sym
  }
  
  final def newVar(pos: Position, tpe: Type)(implicit theOwner: Symbol): Symbol =
    newVar(pos, cunit.fresh.newName("temp"), tpe) setFlag symtab.Flags.SYNTHETIC

  /** returns the condition in "if(cond) k1 else k2" 
   */
  final def condition(tpe: Type, scrut: Symbol): Tree = {
    val res = condition1(tpe, scrut)
    if (settings_debug)
      Console.println("condition, tpe = "+tpe+", scrut.tpe = "+scrut.tpe+", res = "+res)
    res
  }
  final def condition1(tpe: Type, scrut: Symbol): Tree = {
    assert(scrut ne NoSymbol)
    condition(tpe, Ident(scrut) setType scrut.tpe setSymbol scrut)
  }

  final def condition(tpe: Type, scrutineeTree: Tree): Tree = {
    assert(tpe ne NoType)
    assert(scrutineeTree.tpe ne NoType)
    //Console.println("tpe = "+tpe+" prefix="+tpe.prefix)
    //Console.println("singletontype?"+tpe.isInstanceOf[SingletonType])
    //Console.println("constanttype? "+tpe.isInstanceOf[ConstantType])
    //Console.println("value         "+tpe./*?term?*/symbol.isValue)
    //Console.println("module        "+tpe./*?term?*/symbol.isModule)
    if (tpe.isInstanceOf[SingletonType] && !tpe.isInstanceOf[ConstantType]) {

      if (tpe.termSymbol.isModule) {// object
        if (scrutineeTree.tpe <:< definitions.AnyRefClass.tpe)
          Eq(gen.mkAttributedRef(tpe.termSymbol), scrutineeTree)             // object
        else
          Equals(gen.mkAttributedRef(tpe.termSymbol), scrutineeTree)         // object
      } else { 
        //Console.print("111 ??")
        //Console.println("tpe.prefix         "+tpe.prefix)
        //Console.println("tpe stable         "+tpe.isStable)
        //Console.println("tpe prefix stable  "+tpe.prefix.isStable)
        //val x = Equals(Apply(gen.mkAttributedRef(tpe./*?term?*/symbol), List()), scrutineeTree) 
        val x = 
          if(tpe.prefix ne NoPrefix) gen.mkIsInstanceOf(scrutineeTree, tpe)
          else Equals(gen.mkAttributedRef(tpe.termSymbol), scrutineeTree)
        //Console.println(" = "+x)
        typed { x }
      }
    } else if (tpe.isInstanceOf[ConstantType]) {
      val value = tpe.asInstanceOf[ConstantType].value
      //if(false && value.isInstanceOf[NamedConstant]) 
      //  Equals(Ident(scrut), value.asInstanceOf[NamedConstant].tree)             // constant
      //assert(scrut.tpe <:< definitions.AnyRefClass.tpe, "stupid, should be caught by type checker "+value)
      //else 
      if (value == Constant(null) && scrutineeTree.tpe <:< definitions.AnyRefClass.tpe) 
        Eq(scrutineeTree, Literal(value))             // constant
      else
        Equals(scrutineeTree, Literal(value))             // constant
    } else if (scrutineeTree.tpe <:< tpe && tpe <:< definitions.AnyRefClass.tpe) {
      //if(scrutineeTree.symbol.hasFlag(symtab.Flags.SYNTHETIC)) Literal(Constant(true)) else 
      NotNull(scrutineeTree)
    } else 
      //Console.println(tpe.prefix.symbol.isTerm)
      //Console.println(tpe./*?type?*/symbol)
      //Console.println(tpe./*?type?*/symbol.linkedModuleOfClass)
      gen.mkIsInstanceOf(scrutineeTree, tpe)
  }

  final def needsOuterTest(tpe2test: Type, scrutinee: Type) = tpe2test.normalize match {
    case TypeRef(prefix,_,_) => 
      prefix.termSymbol.isTerm && 
      !prefix.termSymbol.isPackage && 
      outerAlwaysEqual(tpe2test, scrutinee) == Some(false)
    case _ =>
     false
  }

  /** returns a result if both are TypeRefs, returns Some(true) if left and right are statically known to have 
   *  the same outer, i.e. if their prefixes are the same 
   */
  final def outerAlwaysEqual(left: Type, right: Type): Option[Boolean] =
    (left.normalize, right.normalize) match {
      case (TypeRef(lprefix, _, _), TypeRef(rprefix, _, _)) =>
        //if(!(lprefix =:= rprefix)) {
          //DEBUG("DEBUG(outerAlwaysEqual) Some(f) for"+(left,right))
        //}
        Some(lprefix =:= rprefix)
      case _ =>
        None
    }

  /** adds a test comparing the dynamic outer to the static outer */
  final def addOuterCondition(cond:Tree, tpe2test: Type, scrutinee: Tree, handleOuter: Tree=>Tree) = {
    val TypeRef(prefix,_,_) = tpe2test
    var theRef = gen.mkAttributedRef(prefix.prefix, prefix.termSymbol) 
    
    // needs explicitouter treatment
    theRef = handleOuter(theRef)
    
    val outerAcc = outerAccessor(tpe2test.typeSymbol)
    if (outerAcc == NoSymbol) {
      if (settings_debug) cunit.warning(scrutinee.pos, "no outer acc for "+tpe2test.typeSymbol)
      cond
    } else
      And(cond,
          Eq(Apply(Select(
            gen.mkAsInstanceOf(scrutinee, tpe2test, true), outerAcc),List()), theRef))
    
  }
  
}
