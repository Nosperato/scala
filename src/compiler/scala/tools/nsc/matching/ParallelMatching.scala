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
 *  `p': pattern
 *  `bx': body index
 *
 *   internal representation is (temp:List[Symbol], row:List[Row])
 *   
 *         tmp1      tmp_n
 *    Row( p_11  ...  p_1n   g_1  b_1 ) + subst
 *
 *    Row( p_m1  ...  p_mn   g_m  b_m ) + subst
 *
 *  @author Burak Emir
 */
trait ParallelMatching  {
  self: transform.ExplicitOuter with PatternMatchers with PatternNodes with CodeFactory =>

  import global._
  import typer.typed
  import symtab.Flags

  /** here, we distinguish which rewrite rule to apply
   *  @pre column does not contain alternatives (ensured by initRep)
   */
  def MixtureRule(scrutinee:Symbol, column:List[Tree], rest:Rep)(implicit rep:RepFactory): RuleApplication = {

    def isSimpleSwitch: Boolean = {
      (isSameType(scrutinee.tpe.widen, definitions.IntClass.tpe)||
       isSameType(scrutinee.tpe.widen, definitions.CharClass.tpe)) && {
        var xs = column; while(!xs.isEmpty) { // forall
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
          case MethodType(List(argtpe,_*),_) =>     scrutinee.tpe <:< argtpe
          case _ => /* Console.println("wrong tpe, needs type test"); */ false
        }
        case x => /*Console.println("is something else"+x+" "+x.getClass) */ false
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
    /*
     DBG("/// MixtureRule("+scrutinee.name+":"+scrutinee.tpe+","+column+", rep = ")
     DBG(rest.toString)
     DBG(")///")
     */
    if(isEqualsPattern(column.head.tpe)) { DBG("\n%%% MixEquals");
      return new MixEquals(scrutinee, column, rest)
	}
    // the next condition is never true, @see isImplemented/CantHandleSeq
    if(column.head.isInstanceOf[ArrayValue]) { DBG("\n%%% MixSequence");
      throw new FatalError("not implemented yet");
      //return new MixSequence(scrutinee, column, rest)
    }
    if(isSimpleSwitch) { DBG("\n%%% MixLiterals")
      return new MixLiterals(scrutinee, column, rest)
    }

     if(settings_casetags && (column.length > 1) && isFlatCases(column)) {
       DBG("flat cases!"+column+"\n"+scrutinee.tpe.typeSymbol.children+"\n"+scrutinee.tpe.member(nme.tag))
       DBG("\n%%% MixCases") 
       return new MixCases(scrutinee, column, rest)
       // if(scrutinee.tpe./*?type?*/symbol.hasFlag(symtab.Flags.SEALED)) new MixCasesSealed(scrutinee, column, rest) 
       // else new MixCases(scrutinee, column, rest) 
    }
    if(isUnapplyHead) { DBG("\n%%% MixUnapply")
      return new MixUnapply(scrutinee, column, rest)
    }
    
    DBG("\n%%% MixTypes") 
    return new MixTypes(scrutinee, column, rest) // todo: handle type tests in unapply
  } /* def MixtureRule(scrutinee:Symbol, column:List[Tree], rest:Rep): RuleApplication */

  // ----------------------------------   data 

  sealed abstract class RuleApplication(rep:RepFactory) {
    def scrutinee:Symbol

    // used in MixEquals and MixSequence
    final protected def repWithoutHead(col:List[Tree],rest:Rep)(implicit theOwner:Symbol): Rep = {
      var fcol  = col.tail
      var frow  = rest.row.tail
      val nfailrow = new ListBuffer[Row]
      while(fcol ne Nil) {
        val p = fcol.head
        frow.head match {
          case Row(pats,binds,g,bx) => nfailrow += Row(p::pats,binds,g,bx)
        }
        fcol = fcol.tail
        frow = frow.tail
      }
      rep.make(scrutinee::rest.temp, nfailrow.toList)
    }

    /** translate outcome of the rule application into code (possible involving recursive application of rewriting) */
    def tree(implicit handleOuter: HandleOuter, localTyper: LocalTyper, theOwner: Symbol, failTree: Tree): Tree 
  }

  case class ErrorRule(implicit rep:RepFactory) extends RuleApplication(rep) {
    def scrutinee:Symbol = throw new RuntimeException("this never happens")
    final def tree(implicit handleOuter: HandleOuter, localTyper: LocalTyper, theOwner: Symbol, failTree: Tree) = failTree
  }

  /**  {case ... if guard => bx} else {guardedRest} */
  case class VariableRule(subst:Binding, guard: Tree, guardedRest:Rep, bx: Int)(implicit rep:RepFactory) extends RuleApplication(rep) {
    def scrutinee:Symbol = throw new RuntimeException("this never happens")
    final def tree(implicit handleOuter: HandleOuter, localTyper: LocalTyper, theOwner: Symbol, failTree: Tree): Tree = {
      val body = typed { rep.requestBody(bx, subst) }
      if(guard eq EmptyTree) 
        return body
      //Console.println("guard in variable rule"+guard)
      val vdefs = targetParams(subst)
      val typedElse = repToTree(guardedRest, handleOuter,localTyper) 

      // crucial: use local typer, it has the the context needed to enter new class symbols
      val otyper = localTyper//typer.atOwner(theOwner)
      val resetGuard  = resetAttrs(guard.duplicate)
      val typedGuard0 = atPhase(phase.prev) {otyper.typed{ resetGuard }}
      val typedGuard  = handleOuter(typedGuard0)
      val typedIf = typed{If(typedGuard, body, typedElse)}

          val r = try {
            typer.typed { squeezedBlock(vdefs, typedIf) }/*} */
          } catch {
            case e => e.printStackTrace(); 
              throw new FatalError(e.getMessage());
            null
          }
      //Console.println("genBody-guard PRE:"+r)
      //val r2 = handleOuter(r) 
      //Console.println("genBody-guard POST:"+r2)
      r
    } /* def tree(implicit handleOuter: HandleOuter, theOwner: Symbol, failTree: Tree) */
  } 


  abstract class CaseRuleApplication(rep:RepFactory) extends RuleApplication(rep) {

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
        val nbindings = s.add(vs.elements,scrutinee)
        Row(pats, nbindings, g, bx)
      }
    }

    /** inserts row indices using in to list of tagindexpairs*/
    protected def tagIndicesToReps(implicit theOwner: Symbol) = {
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
        trs = (tag, rep.make(this.grabTemps, tagRows ::: defaultRows)) :: trs
      }
      tagIndexPairs = old
      trs
    }

    protected def defaultsToRep(implicit theOwner: Symbol) = rep.make(rest.temp, getDefaultRows)

    protected def insertTagIndexPair(tag: Int, index: Int) { tagIndexPairs = TagIndexPair.insert(tagIndexPairs, tag, index) }

    /** returns 
     *  @return list of continuations, 
     *  @return variables bound to default continuation,  
     *  @return optionally, a default continuation,  
     **/
    def getTransition(implicit theOwner: Symbol): (List[(Int,Rep)],Set[Symbol],Option[Rep]) = 
      (tagIndicesToReps, defaultV, {if(haveDefault) Some(defaultsToRep) else None})
  } /* CaseRuleApplication */

  /**
   *  mixture rule for flat case class (using tags)
   *
   *  this rule gets translated to a switch of _.$tag()
  **/
  class MixCases(val scrutinee:Symbol, val column:List[Tree], val rest:Rep)(implicit rep:RepFactory) extends CaseRuleApplication(rep) {

    /** insert row indices using in to list of tagindexpairs */
    {
      var xs = column; var i  = 0; while(xs ne Nil) { // forall
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
        val nbindings = s.add(strip1(column(index)).elements, scrutinee)
        Row(column(tagIndexPairs.index)::pats, nbindings, g, bx)
    }

    final def tree(implicit handleOuter: HandleOuter, localTyper: LocalTyper, theOwner: Symbol, failTree: Tree): Tree = {
      val (branches, defaultV, default) = getTransition // tag body pairs
      DBG("[[mix cases transition: branches \n"+(branches.mkString("","\n","")+"\ndefaults:"+defaultV+" "+default+"]]"))

      var ndefault = if(default.isEmpty) failTree else repToTree(default.get, handleOuter,localTyper)
      var cases = branches map { 
        case (tag, r) => 
          CaseDef(Literal(tag), 
                  EmptyTree, 
                  {
                    val pat  = this.column(this.tagIndexPairs.find(tag));
                    val ptpe = pat.tpe
                    if(this.scrutinee.tpe.typeSymbol.hasFlag(symtab.Flags.SEALED) && strip2(pat).isInstanceOf[Apply]) {
                      //cast 
                      val vtmp = newVar(pat.pos, ptpe)
                      squeezedBlock(
                        List(typedValDef(vtmp, gen.mkAsInstanceOf(mkIdent(this.scrutinee), ptpe))),
                        repToTree(rep.make(vtmp :: r.temp.tail, r.row),handleOuter,localTyper)
                      )
                    } else repToTree(r, handleOuter,localTyper)
                  }
                )}
      
      renamingBind(defaultV, this.scrutinee, ndefault) // each v in defaultV gets bound to scrutinee 
      
      // make first case a default case.
      if(this.scrutinee.tpe.typeSymbol.hasFlag(symtab.Flags.SEALED) && defaultV.isEmpty) {
        ndefault = cases.head.body
        cases = cases.tail
      }
      
      cases.length match {
        case 0 => ndefault
        case 1 => val CaseDef(lit,_,body) = cases.head
                  If(Equals(Select(mkIdent(this.scrutinee),nme.tag),lit), body, ndefault) 
        case _ => val defCase = CaseDef(mk_(definitions.IntClass.tpe), EmptyTree, ndefault)
                  Match(Select(mkIdent(this.scrutinee),nme.tag), cases :::  defCase :: Nil)
      }
    } /* def tree(implicit handleOuter: HandleOuter, theOwner: Symbol, failTree: Tree) */
  } /* MixCases */

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
  class MixLiterals(val scrutinee:Symbol, val column:List[Tree], val rest:Rep)(implicit rep:RepFactory) extends CaseRuleApplication(rep) {

    private var defaultIndexSet: Set64 = if(column.length < 64) new Set64 else null
    
    override def insertDefault(tag: Int, vs: Set[Symbol]): Unit = 
      if(defaultIndexSet eq null) super.insertDefault(tag,vs)
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

    /*block*/{ 
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
    }/*end block*/

    final def tree(implicit handleOuter: HandleOuter, localTyper: LocalTyper, theOwner: Symbol, failTree: Tree): Tree = {
      val (branches, defaultV, defaultRepOpt) = this.getTransition // tag body pairs
      DBG("[[mix literal transition: branches \n"+(branches.mkString("","\n",""))+"\ndefaults:"+defaultV+"\n"+defaultRepOpt+"\n]]")
      val cases = branches map { case (tag, rep) => CaseDef(Literal(tag), EmptyTree, repToTree(rep, handleOuter,localTyper)) }
      var ndefault = if(defaultRepOpt.isEmpty) failTree else repToTree(defaultRepOpt.get, handleOuter,localTyper)

      renamingBind(defaultV, this.scrutinee, ndefault) // each v in defaultV gets bound to scrutinee 
      if(cases.length == 1) {
        val CaseDef(lit,_,body) = cases.head
        If(Equals(mkIdent(this.scrutinee),lit), body, ndefault)
      } else {
        val defCase = CaseDef(mk_(definitions.IntClass.tpe), EmptyTree, ndefault)


        if(isSameType(this.scrutinee.tpe.widen, definitions.CharClass.tpe)) {
          val zz = newVar(this.scrutinee.pos, this.scrutinee.tpe)
          // the valdef is a workaround for a bug in boxing -- Utility.parseCharRef contains an instance
          //  where erasure forgets the char to int conversion and emits Int.unbox in the scrutinee position
          //  of the match. This then fails at runtime, because it encounters a boxed Character, not boxedq Int
          return Block(
            List(typedValDef(zz, mkIdent(this.scrutinee))),
            Match(gen.mkAsInstanceOf(mkIdent(zz), definitions.IntClass.tpe), cases :::  defCase :: Nil)
          )
        }
        return Match(mkIdent(this.scrutinee), cases :::  defCase :: Nil)
      }
    } /* def tree(implicit handleOuter: HandleOuter, theOwner: Symbol, failTree: Tree) */
  } /* MixLiterals */
  
  /**
   * mixture rule for unapply pattern
   */
  class MixUnapply(val scrutinee:Symbol, val column:List[Tree], val rest:Rep)(implicit rep:RepFactory) extends RuleApplication(rep) {

    def newVarCapture(pos:Position,tpe:Type)(implicit theOwner:Symbol) = {
      val v = newVar(pos,tpe)
      if(scrutinee.hasFlag(symtab.Flags.TRANS_FLAG))
        v.setFlag(symtab.Flags.TRANS_FLAG) // propagate "unchecked"
      v
    }

    private def bindToScrutinee(x:Symbol) = typedValDef(x,mkIdent(scrutinee))

    val unapp = strip2(column.head)
    /** returns the (un)apply and two continuations */
    var rootvdefs:List[Tree] = Nil // later, via bindToScrutinee

    final def getTransition(implicit theOwner: Symbol): (Tree, List[Tree], Rep, Option[Rep]) = {
      unapp match {
        case ua @ UnApply(app @ Apply(fn, appargs), args) => 
          val ures = newVarCapture(ua.pos, app.tpe)
          val n    = args.length
          val uacall = ValDef(ures, Apply(fn, mkIdent(scrutinee) :: appargs.tail)) 
          //Console.println("uacall:"+uacall)
        
          val nrowsOther = column.tail.zip(rest.row.tail) flatMap { case (pat, Row(ps, subst, g, bx)) => strip2(pat) match { 
            case UnApply(app @ Apply(fn1,_),args) if fn.symbol==fn1.symbol => Nil
            case p                                                         => List(Row(pat::ps, subst, g, bx))
          }}
          val nrepFail = if(nrowsOther.isEmpty) None else Some(rep.make(scrutinee::rest.temp, nrowsOther))
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
              (uacall, rootvdefs, rep.make(ntemps, nrows), nrepFail)
 
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
              (uacall, rootvdefs:::List( typedValDef(vsym, Select(mkIdent(ures), nme.get))), rep.make(ntemps, nrows), nrepFail)

            case _ => // app.tpe is Option[? <: ProductN[T1,...,Tn]]
              val uresGet = newVarCapture(ua.pos, app.tpe.typeArgs(0))
              var vdefs = typedValDef(uresGet, Select(mkIdent(ures), nme.get))::Nil
              var ts = definitions.getProductArgs(uresGet.tpe).get
              var i = 1;
              //Console.println("typeargs"+ts)
              var vsyms:List[Symbol] = Nil
              var dummies:List[Tree] = Nil
              while(ts ne Nil) {
                val vtpe = ts.head
                val vchild = newVarCapture(ua.pos, vtpe)
                val accSym = definitions.productProj(uresGet, i)
                val rhs = typed(Apply(Select(mkIdent(uresGet), accSym), List())) // nsc !
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
              (uacall, rootvdefs:::vdefs.reverse, rep.make(ntemps, nrows), nrepFail)
          }}
    } /* def getTransition(...) */

    final def tree(implicit handleOuter: HandleOuter, localTyper: LocalTyper, theOwner: Symbol, failTree: Tree) = {
      val (uacall/*:ValDef*/ , vdefs,srep,frep) = this.getTransition // uacall is a Valdef
      //Console.println("getTransition"+(uacall,vdefs,srep,frep))
      val succ = repToTree(srep, handleOuter,localTyper)
      val fail = if(frep.isEmpty) failTree else repToTree(frep.get, handleOuter,localTyper)
      val cond = 
        if(uacall.symbol.tpe.typeSymbol eq definitions.BooleanClass)
          typed{ mkIdent(uacall.symbol) }
        else 
          emptynessCheck(uacall.symbol)
      typed { squeezedBlock(List(handleOuter(uacall)), If(cond,squeezedBlock(vdefs,succ),fail)) }
    } /* def tree(implicit handleOuter: HandleOuter, theOwner: Symbol, failTree: Tree) */ 
  } /* MixUnapply */

  /** handle sequence pattern and ArrayValue 
  class MixSequence(val scrutinee:Symbol, val column:List[Tree], val rest:Rep) extends RuleApplication {
    // context (to be used in IF), success and failure Rep 
    final def getTransition(implicit theOwner: Symbol): (Tree => Tree => Tree, Rep, Rep) = { 

      assert(isSubType(scrutinee.tpe, column.head.tpe), "problem "+scrutinee.tpe+" not <: "+column.head.tpe)

      val treeAsSeq = 
        if(!isSubType(scrutinee.tpe, column.head.tpe))
          typed(gen.mkAsInstanceOf(gen.mkAttributedRef(scrutinee), column.head.tpe, true))
        else
          gen.mkAttributedRef(scrutinee)
    
      val failRep = repWithoutHead(column,rest)

      column.head match {
        case av @ ArrayValue(_, xs) =>

          var childpats = new ListBuffer[Tree]
          var bindings  = new ListBuffer[Tree]
          var vs        = new ListBuffer[Symbol]
          var ix = 0

        // if is right ignoring, don't want last one
          var ys = if(isRightIgnoring(av)) xs.take(xs.length-1) else xs; while(ys ne Nil) {
            val p = ys.head
            childpats += p
            val temp = newVar(p.pos, p.tpe)
            Console.println("new temp:"+temp+":"+temp.tpe)
            Console.println("seqelem:"+seqElement(treeAsSeq.duplicate, ix))
            vs += temp
            bindings += typedValDef(temp, seqElement(treeAsSeq.duplicate, ix))
            ix += 1
            ys = ys.tail
          }
            
          val childpatList = childpats.toList
          val nrows = rest.row.map { 
            case Row(pats,subst,g,b) => Row(childpatList ::: pats,subst,g,b)
          }
          val succRep = rep.make( vs.toList ::: rest.temp, nrows)

          if(isRightIgnoring(av)) { // contains a _* at the end
            val minlen = xs.length-1;

              //typedValDef(casted, treeAsSeq.duplicate) :: bindings

              val tempTail = newVar(xs.last.pos, xs.last.tpe)
              bindings += typedValDef(tempTail, seqDrop(treeAsSeq.duplicate, minlen))

            val cond = seqLongerThan(treeAsSeq.duplicate, column.head.tpe, minlen)

            return ({thenp:Tree => {elsep:Tree => 
              If(cond, squeezedBlock(bindings.toList, thenp), elsep)
                                  }}, succRep, failRep)
          }
        
          // fixed length
          val cond = seqHasLength(treeAsSeq.duplicate, column.head.tpe, xs.length)
          return ({thenp:Tree => {elsep:Tree => If(cond, thenp, elsep)}}, succRep, failRep)
      }
    }

    final def tree(implicit handleOuter: HandleOuter, theOwner: Symbol, failTree: Tree) = {
      val (cx,srep,frep) = this.getTransition
      val succ = repToTree(srep, handleOuter)
      val fail = repToTree(frep, handleOuter)
      cx(succ)(fail)
    }

  }
  */
  // @todo: equals test for same constant
  class MixEquals(val scrutinee:Symbol, val column:List[Tree], val rest:Rep)(implicit rep:RepFactory) extends RuleApplication(rep) {
    /** condition (to be used in IF), success and failure Rep */
    final def getTransition(implicit theOwner: Symbol): (Tree, Rep, Rep) = {
      val nmatrix = rest
      val vlue = (column.head.tpe: @unchecked) match {
        case TypeRef(_,_,List(SingleType(pre,sym))) => 
          gen.mkAttributedRef(pre,sym)
      }
      val nsuccFst = rest.row.head match { case Row(pats,bnd,g,b) => Row(EmptyTree::pats,bnd,g,b) }
      val nsuccRow = nsuccFst :: (column.tail.zip(rest.row.tail) map { case (p, Row(pats,bnd,g,b)) => Row(p::pats,bnd,g,b) })
      val nsucc = rep.make(scrutinee :: rest.temp, nsuccRow)
      val nfail = repWithoutHead(column,rest)
      return (typed{ Equals(mkIdent(scrutinee) setType scrutinee.tpe, vlue) }, nsucc, nfail) 
    }

    final def tree(implicit handleOuter: HandleOuter, localTyper: LocalTyper, theOwner: Symbol, failTree: Tree) = {
      val (cond,srep,frep) = this.getTransition
      //Console.println("MixEquals::tree -- cond "+cond)
      val cond2 = try{
        typed { handleOuter(cond) }
      } catch {
        case e =>
          Console.println("failed to type-check cond2")
          Console.println("cond: "+cond)
          Console.println("cond2: "+handleOuter(cond))
        throw e
      }
      
      val succ = repToTree(srep, handleOuter,localTyper)
      val fail = repToTree(frep, handleOuter,localTyper)
      try {
        typed{ If(cond2, succ, fail) }
      } catch {
        case e =>
          Console.println("failed to type-check If")
        Console.println("cond2: "+cond2)
        throw e
      }
    } /* def tree(implicit handleOuter: HandleOuter, theOwner: Symbol, failTree: Tree) */
  } /* MixEquals */

  /**
   *   mixture rule for type tests
  **/
  class MixTypes(val scrutinee:Symbol, val column:List[Tree], val rest:Rep)(implicit rep:RepFactory) extends RuleApplication(rep) {
    
    var casted: Symbol = null
    var moreSpecific:   List[Tree]        = Nil
    var subsumed:  List[(Int,List[Tree])] = Nil // row index and subpatterns
    var remaining: List[(Int,Tree)]       = Nil // row index and pattern

    val isExhaustive = !scrutinee.tpe.typeSymbol.hasFlag(symtab.Flags.SEALED) || {
      //DEBUG("check exha for column "+column)
      val tpes = column.map {x => /*Console.println("--x:"+x+":"+x.tpe); */ x.tpe.typeSymbol}
      scrutinee.tpe.typeSymbol.children.forall { sym => tpes.contains(sym) }
    }

    private val headPatternType     = column.head match {
      case p @ (_:Ident | _:Select) => 
        singleType(p.symbol.tpe.prefix, p.symbol) //should be singleton object
      //case p@Apply(_,_) if !p.tpe./*?type?*/symbol.hasFlag(symtab.Flags.CASE) => ConstantType(new NamedConstant(p))
      case _ => column.head.tpe
    }
    private val isCaseHead = isCaseClass(headPatternType)
    private val dummies = if(!isCaseHead) Nil else getDummies(headPatternType.typeSymbol.caseFieldAccessors.length)
  
    //Console.println("headPatternType "+headPatternType)
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
      headPatternType =:= singleType(pat.symbol.tpe.prefix, pat.symbol)
    } catch {
      case e =>
        Console.println("object pattern test throws "+e.getMessage())
        //if(settings_debug)
        //  System.exit(-1);
        throw e
    }
    //Console.println("scrutinee == "+scrutinee+":"+scrutinee.tpe)
    //Console.println("column.head == "+column.head);
    /*init block*/ {
      var sr = (moreSpecific,subsumed,remaining)
      var j = 0; var pats = column; while(pats ne Nil) {
        val (ms,ss,rs) = sr // more specific, more general(subsuming current), remaining patterns
        val pat = pats.head 

        //Console.println("pat = "+pat+" (class "+pat.getClass+")of type "+pat.tpe)
        //Console.println("current pat is wild? = "+isDefaultPattern(pat))
        //Console.println("current pat.symbol = "+pat.symbol+", pat.tpe "+pat.tpe)
        //Console.println("headPatternType = "+headPatternType)
        //Console.println("(current)pat.tpe <:< headPatternType = "+(pat.tpe <:< headPatternType))
        //Console.println("headPatternType <:< (current)pat.tpe = "+(headPatternType <:< pat.tpe))
        //Console.println("(current)pat.tpe =:= headPatternType = "+(pat.tpe <:< headPatternType))

        sr = strip2(pat) match {
          // case _: Bind       => // cannot happen, using strip2
          // case a:Alternative => // cannot happen, alternatives should be preprocessed away

          case Literal(Constant(null)) if !(headPatternType =:= pat.tpe) => //special case for constant null pattern
            //Console.println("[1")
            (ms,ss,(j,pat)::rs);
          case _ if objectPattern(pat) =>
            //Console.println("[2")
            (EmptyTree::ms, (j,dummies)::ss, rs);                                 // matching an object
          
          case Typed(p:UnApply,_) if (pat.tpe /*is never <equals>*/ <:< headPatternType) => 
            //Console.println("unapply arg is same or *more* specific")          
            (p::ms, (j, dummies)::ss, rs); 

          case q @ Typed(pp,_) if (patternType_wrtEquals(pat.tpe) <:< headPatternType) => 
            //Console.println("current pattern is same or *more* specific")          
            ({if(pat.tpe =:= headPatternType /*never true for <equals>*/) EmptyTree else q}::ms, (j, dummies)::ss, rs); 

          case qq if (patternType_wrtEquals(pat.tpe) <:< headPatternType) && !isDefaultPattern(pat) => 
            //Console.println("current pattern is same or *more* specific")          
            ({if(pat.tpe =:= headPatternType /*never true for <equals>*/) EmptyTree else pat}::ms, (j,subpatterns(pat))::ss, rs); 
          
          case _ if (headPatternType <:< pat.tpe /*never true for <equals>*/) || isDefaultPattern(pat) => 
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
    } /* init block */

    override def toString = {
      "MixTypes("+scrutinee+":"+scrutinee.tpe+") {\n  moreSpecific:"+moreSpecific+"\n  subsumed:"+subsumed+"\n  remaining"+remaining+"\n}"
    }

    /** returns casted symbol, success matrix and optionally fail matrix for type test on the top of this column */
    final def getTransition(implicit theOwner: Symbol): (Symbol, Rep, Option[Rep]) = {
      //Console.println("*** getTransition! of "+this.toString)
      // the following works for type tests... what fudge is necessary for value comparisons?
      // type test
      casted = if(scrutinee.tpe =:= headPatternType) scrutinee else newVar(scrutinee.pos, headPatternType)
      if(scrutinee.hasFlag(symtab.Flags.TRANS_FLAG))
        casted.setFlag(symtab.Flags.TRANS_FLAG)
      // succeeding => transition to translate(subsumed) (taking into account more specific)
      val nmatrix = {
        var ntemps  = if(isCaseClass(casted.tpe)) casted.caseFieldAccessors map { 
          meth => 
            val ctemp = newVar(scrutinee.pos, casted.tpe.memberType(meth).resultType) 
            if(scrutinee.hasFlag(symtab.Flags.TRANS_FLAG))
              ctemp.setFlag(symtab.Flags.TRANS_FLAG)
            ctemp
        } else Nil // (***)
        var subtests = subsumed

        if(moreSpecific.exists { x => x != EmptyTree }) {
          ntemps   = casted::ntemps                                                                            // (***)
          subtests = moreSpecific.zip(subsumed) map { 
            case (mspat, (j,pats)) => (j,mspat::pats) 
          }
        }
        ntemps = ntemps ::: rest.temp
        val ntriples = subtests map {
          case (j,pats) => 
            val (vs,thePat) = strip(column(j))
            val Row(opats, osubst, og, bx) = rest.row(j)
            val nsubst = osubst.add(vs.elements, casted)
            Row(pats ::: opats, nsubst, og, bx) 
        }
        //DBG("ntemps   = "+ntemps.mkString("[["," , ","]]"))
        //DBG("ntriples = "+ntriples.mkString("[[\n","\n, ","\n]]"))
        rep.make(ntemps, ntriples) /*setParent this*/
      }
      // fails      => transition to translate(remaining)

      val nmatrixFail: Option[Rep] = {
        val ntemps   = scrutinee :: rest.temp 
        val ntriples = remaining map {
          case (j, pat) => val r = rest.row(j);  Row(pat :: r.pat, r.subst, r.guard, r.bx)
        }
        if(ntriples.isEmpty) None else Some(rep.make(ntemps, ntriples))
      }
      //DBG("nmatrixFail = \n\n"+nmatrixFail)
      (casted, nmatrix, nmatrixFail)
    } /* getTransition(implicit theOwner: Symbol): (Symbol, Rep, Option[Rep]) */

    final def tree(implicit handleOuter: HandleOuter, localTyper: LocalTyper, theOwner: Symbol, failTree: Tree) = {
      val (casted,srep,frep) = this.getTransition
      val condUntyped = condition(casted.tpe, this.scrutinee)
      var cond = handleOuter(typed { condUntyped }) // <- throws exceptions in some situations?
      if(needsOuterTest(casted.tpe, this.scrutinee.tpe)) // @todo merge into def condition
        cond = addOuterCondition(cond, casted.tpe, mkIdent(this.scrutinee), handleOuter)
      val succ = repToTree(srep, handleOuter, localTyper)
      
      val fail = if(frep.isEmpty) failTree else repToTree(frep.get, handleOuter,localTyper)
      
      // dig out case field accessors that were buried in (***)
      //Console.println("casted:"+casted+":  "+casted.tpe)
      val cfa  = if(isCaseHead) casted.caseFieldAccessors else Nil
      //Console.println("cfa's:"+cfa.toString)
      
      val caseTemps = (if(!srep.temp.isEmpty && srep.temp.head == casted) srep.temp.tail else srep.temp).zip(cfa)
      
      //Console.println("case temps"+caseTemps.toString)
      try{
      var vdefs     = caseTemps map { 
        case (tmp,accessorMethod) => 
          //Console.println("tmp: "+tmp+":"+tmp.tpe)
          //Console.println("accessorMethod: "+accessorMethod+":"+accessorMethod.tpe)
          val untypedAccess = Apply(Select(mkIdent(casted), accessorMethod),List()) 
          val typedAccess = typed { untypedAccess }
          //Console.println("ParallelMatching-- MixTypes "+typedAccess)
          typedValDef(tmp, typedAccess)
      }
      
      if(casted ne this.scrutinee) {
        vdefs = ValDef(casted, gen.mkAsInstanceOf(mkIdent(this.scrutinee), casted.tpe)) :: vdefs
      }
      typed { If(cond, squeezedBlock(vdefs,succ), fail) }
      } catch {
        case e =>
          throw new FatalError("EXCEPTION:"+e.getMessage())
      }
    } /* def tree(implicit handleOuter: HandleOuter, theOwner: Symbol, failTree: Tree) */
  } /* class MixTypes */

  /** converts given rep to a tree - performs recursive call to translation in the process to get sub reps
   */
  final def repToTree(r: Rep, handleOuter: HandleOuter, localTyper: LocalTyper)(implicit theOwner: Symbol, failTree: Tree, rep: RepFactory): Tree = {
    implicit val HandleOuter = handleOuter
    implicit val LocalTyper = localTyper
    r.applyRule.tree
    /*
    rep.applyRule match {
      case r : ErrorRule    => r.tree

      case vr: VariableRule => vr.tree

      case mc: MixCases     => mc.tree

      case ml: MixLiterals  => ml.tree

      case me: MixEquals    => me.tree

      case mm: MixTypes     => mm.tree

      case mu: MixUnapply   => mu.tree

    }
    */
  }

  /** subst: the bindings so far */
  case class Row(pat:List[Tree], subst:Binding, guard:Tree, bx:Int)

  object Rep {
    type RepType = Product2[List[Symbol], List[Row]]
    final def unapply(x:Rep)(implicit rep:RepFactory):Option[RepType] = 
      if(x.isInstanceOf[rep.RepImpl]) Some(x.asInstanceOf[RepType]) else None
  }
  class RepFactory {
  case class RepImpl(val temp:List[Symbol], val row:List[Row]) extends Rep with Rep.RepType {
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
  var shortCuts: List[Symbol] = Nil;

  final def make(temp:List[Symbol], row:List[Row], targets: List[Tree], vss:List[SymList])(implicit theOwner: Symbol): Rep = {
    // ensured that labels(i) eq null for all i, cleanup() has to be called after translation
    this.targets   = targets
    if(targets.length > labels.length) 
      this.labels    = new Array[Symbol](targets.length)
    this.vss       = vss
    this.reached64 = if(targets.length < 64) new Set64 else null
    //Console.println("targets: "+targets)
    //Console.print("labels: "); {for(s<-labels) Console.print({if(s ne null) {s} else "_"}+",")}
    return make(temp, row)
  }

  final def shortCut(theLabel:Symbol): Int = {
    //Console.println("adds shortcut:"+theLabel)
    this.shortCuts = shortCuts:::theLabel::Nil;
    return -shortCuts.length
  }

  final def cleanup(tree: Tree)(implicit theOwner: Symbol): Tree = { 
    object lxtt extends Transformer {
      override def transform(tree:Tree): Tree = tree match {
        case blck @ Block(vdefs, ld @ LabelDef(name,params,body)) =>
          val bx = labelIndex(ld.symbol)
          if((bx >= 0) && !isReachedTwice(bx)) {
            //Console.println("removing labeldef! ") 
            //Console.println("ld.symbol = "+ld.symbol) 
            //Console.println("bx = "+bx) 
            //Console.println("rtwice? "+isReachedTwice(bx)) 
            //Console.println("reached = "+reached) 
             squeezedBlock(vdefs,body)
           }
          else 
            blck

        //case If(Literal(Constant(true)),  thenp, elsep) =>       
        //  Console.println("1 short IF affecting "+elsep); thenp
        //case If(Literal(Constant(false)), thenp, elsep) => 
        //  Console.println("2 short IF affecting "+thenp); elsep
        case If(cond, Literal(Constant(true)), Literal(Constant(false))) => 
          super.transform(cond)
        case If(cond1, If(cond2, thenp, elsep1), elsep2) if (elsep1 equalsStructure elsep2) => 
          //Console.println("3 short IF affecting "+elsep2);
          super.transform(If(And(cond1,cond2), thenp, elsep1))
        case If(cond1, If(cond2, thenp, Apply(jmp,List())), ld:LabelDef) if (jmp.symbol eq ld.symbol) => 
          //Console.println("4 short IF affecting "+ld)
          super.transform(If(And(cond1,cond2), thenp, ld))

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
    shortCuts = Nil
  }
  final def isReached(bx:Int)   = { /*Console.println("isReached("+bx+")"); */labels(bx) ne null }
  final def markReachedTwice(bx:Int) = if(reached64 ne null) { reached64 |= bx } else { reached = insertSorted(bx, reached) }
  /** @pre bx < 0 || labelIndex(bx) != -1 */
  final def isReachedTwice(bx:Int) = (bx < 0) || (if(reached64 ne null) { reached64 contains bx } else { findSorted(bx,reached) })
  
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
    if(bx < 0) {// is shortcut
      //Console.println("requestBody ("+bx+") gets shortcut "+(shortCuts.length-bx)+ " all:"+shortCuts);
      val jlabel = shortCuts(-bx-1)
      //Console.println("is "+jlabel);
      val jump = Apply(mkIdent(jlabel), Nil)
      //Console.println(jump)
      //val jumpT = typed{ jump }
      //Console.println(jumpT)
      return jump
    }
    //Console.print("requestbody("+bx+", "+subst+") isReached(bx)?"+isReached(bx)+" labels:"); {for(s<-labels) Console.print({if(s ne null) {s} else "_"}+",")}
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
      // bug: typer is not able to digest a body of type Nothing being assigned result type Unit
      val tpe = if(body.tpe.typeSymbol eq definitions.AllClass) body.tpe else resultType
      val label = theOwner.newLabel(body.pos, "body%"+bx).setInfo(new MethodType(argts.toList, tpe))
      labels(bx) = label

      if(body.isInstanceOf[Throw] || body.isInstanceOf[Literal]) {
        return squeezedBlock(vdefs.reverse, body.duplicate setType tpe)
      }
      //Console.println("- !isReached returning LabelDef "+label)
      //Console.println("- !      and vdefs "+vdefs)
      return squeezedBlock(vdefs, LabelDef(label, vrev.reverse, body setType tpe))
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
          cunit.error(targets(bx).pos, "consistency problem in target generation ! I have args "+args+" and need to jump to a label with fmls "+fmls)
          //System.exit(-1)
          throw FatalError("consistency problem")
        }
        for((f,a) <- fmls.zip(args.toList)) {
          if(!(a.tpe <:< f)) {
            cunit.error(targets(bx).pos, "consistency problem ! "+a.tpe+" "+f)
            //System.exit(-1)
            throw FatalError("consistency problem")
          }
        }
    }
    val body = targets(bx)
    if(body.isInstanceOf[Throw] || body.isInstanceOf[Literal]) {
      val vdefs = new ListBuffer[Tree]
      val it = vss(bx).elements; while(it.hasNext) {
        val v = it.next
        val substv = subst(v)
        if(substv ne null) {// might be bound elsewhere ( see `x @ unapply' )
          vdefs  += typedValDef(v, substv)
        } 
      }
      return squeezedBlock(vdefs.toList, body.duplicate setType resultType)
    }


    return Apply(mkIdent(label),args.toList)
  }

  /** the injection here handles alternatives and unapply type tests */
  final def make(temp:List[Symbol], row1:List[Row])(implicit theOwner: Symbol): Rep = {
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
              val tpe = 
                if (!o.symbol.isValue) {
                  singleType(o.tpe.prefix, o.symbol)
                } else {
                  DBG("o.tpe              "+o.symbol.tpe)
                  DBG("o.tpe              "+o.symbol.tpe.isStable)
                  DBG("p.prefix           "+o.symbol.tpe.prefix)
                  singleType(NoPrefix, o.symbol) // equals-check
                  // call the above `stpe'. Then we could also return 
                  // `typeRef(definitions.ScalaPackageClass.tpe, definitions.EqualsPatternClass, List(stpe))'
                  // and force an equality check. However, exhaustivity checking would not work anymore.
                  // so first, extend exhaustivity check to equalspattern
                }
              val p = Ident(nme.WILDCARD) setType tpe
              val q = Typed(p, TypeTree(tpe)) setType tpe
              pats = q::pats 

            case o @ Ident(nme.WILDCARD) => 
              pats = o::pats

            case o @ Select(stor,_) =>
              val stpe = 
                if (!o.symbol.isValue) {
                  singleType(o.tpe.prefix, o.symbol)
                } else {
                  DBG("stor.tpe              "+stor.tpe)
                  DBG("stor.symbol           "+stor.symbol)
                  DBG("stor.symbol.tpe       "+stor.symbol.tpe)
                  singleType(NoPrefix, o.symbol) // equals-check
                }
              val p = Ident(nme.WILDCARD) setType stpe
              val q = Typed(p, TypeTree(stpe)) setType stpe
              pats = q::pats 

            case UnApply(Apply(TypeApply(sel @ Select(stor, nme.unapplySeq),List(tptArg)),_),ArrayValue(_,xs)::Nil) if(stor.symbol eq definitions.ListModule) => 
              //@pre: is not right-ignoring (no star pattern)
              // no exhaustivity check, please
              temp(j).setFlag(symtab.Flags.TRANS_FLAG)
              val listType = typeRef(mkThisType(definitions.ScalaPackage), definitions.ListClass, List(tptArg.tpe))
              val nmlzdPat = normalizedListPattern(xs, tptArg.tpe)
              pats = nmlzdPat :: pats
            case ua @ UnApply(Apply(fn, _), arg) => 
              fn.tpe match {
                case MethodType(List(argtpe,_*),_) => 
                  pats = (if (temp(j).tpe <:< argtpe) ua else Typed(ua,TypeTree(argtpe)).setType(argtpe))::pats
              }

            /** something too tricky is going on if the outer types don't match
             */
            case o @ Apply(fn, List()) if !isCaseClass(o.tpe) => 
              DBG("o.tpe   "+o.tpe)
              DBG("fn is   ")
              //Console.println("o.symbol is a module?  "+o.symbol+" "+o.symbol.isModule)
              //Console.println("o.tpe.termSymbol is a module?  "+o.tpe.termSymbol+" "+o.tpe.termSymbol.isModule)
              val stpe: Type = fn match { 
                case _ if (o.symbol.isModule) => 
                  DBG("o.symbol is module!  "+o.symbol)
                  DBG(nodeToString(fn))
                  singleType(o.tpe.prefix, o.symbol)
                case _ if (o.tpe.termSymbol.isModule) => 
                  //Console.println("o.tpe.termSymbol is a module!  "+sym)
                  singleType(o.tpe.prefix, o.symbol)
                case Select(path,sym) =>
                  DBG("path    "+path);
                  DBG("sym     "+sym);
                  path.tpe match {
                    case ThisType(sym) => 
                      singleType(path.tpe, o.symbol)                                 

                    case _ => // e.g. `case Some(p._2)' in scala.collection.jcl.Map
                      singleType(singleType(path.tpe.prefix, path.symbol) , o.symbol)  // old
                      
                  }
                case o @ Ident(_) => // lazy val
                  DBG("sym     "+o.symbol)
                  DBG("sym.isValue     "+o.symbol.isValue)
                  //singleType(NoPrefix, o.symbol)                                  // before
                if (!o.symbol.isValue) {
		  singleType(o.tpe.prefix, o.symbol)
                } else {
                  singleType(NoPrefix, o.symbol) // equals-check
                }

              }
              //Console.println("encoding in singleType:"+stpe)

              val ttst = typeRef(definitions.ScalaPackageClass.tpe, definitions.EqualsPatternClass, List(stpe))
              
              //Console.println("here's the result: "+ttst)

              val p = Ident(nme.WILDCARD) setType ttst
              val q = Typed(p, TypeTree(stpe)) setType ttst 
              pats = q::pats 

            case o @ Apply(fn, Nil) => //  no args case class pattern 
              assert(isCaseClass(o.tpe), o.toString)
              val q = Typed(EmptyTree, TypeTree(o.tpe)) setType o.tpe
              pats = q :: pats

            case p @ ArrayValue(_,xs) =>
          	  assert(false) // inactive, @see PatternMatchers::isImplemented
              pats = p :: pats

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
      this.make(temp,row) // recursive call
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
            !sym.hasFlag(symtab.Flags.TRANS_FLAG) &&  // indicates @unchecked
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
            //Console.println("covers? "+p+" "+sym);
            val res = 
              isDefaultPattern(p) || p.isInstanceOf[UnApply] || p.isInstanceOf[ArrayValue] || {
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
    } /* def init */
    

    /*   internal representation is (temp:List[Symbol], row:List[Row])
     *   
     *         tmp1       tmp_m
     */
     
    final def applyRule(implicit theOwner: Symbol, rep: RepFactory): RuleApplication = row match {
      case Nil =>
        ErrorRule
      case Row(pats, subst, g, bx)::xs => 
        var px = 0; var rpats = pats; var bnd = subst; var temps = temp; while((bnd ne null) && (rpats ne Nil)) {
          val (vs,p) = strip(rpats.head);
          if(!isDefaultPattern(p)) { /*break*/ bnd = null; } else {
            bnd = bnd.add(vs.elements,temps.head)
            rpats = rpats.tail
            temps = temps.tail
            px += 1 // pattern index
          }
        }
        /*    Row(   _   ...   _     g_1  b_1 ) :: rows     it's all default patterns
         */
        if(bnd ne null) {    // all default patterns
          val rest = if(g eq EmptyTree) null else rep.make(temp, xs)
          return VariableRule (bnd, g, rest, bx)
        }

      /*    Row( _  ... _ p_1i  ...  p_1n   g_m  b_m ) :: rows
       */

        // cut out column px that contains the non-default pattern
        // assert(px == pats findIndexOf {x => !isDefaultPattern(x)})
        val column   = rpats.head :: (row.tail map { case Row(pats,_,_,_) => pats(px) }) 
        // assert(column == row map { case Row(pats,_,_,_) => pats(px) })
        val restTemp =                                               temp.take(px) ::: temp.drop(px+1)
        val restRows = row map { case Row(pats, subst, g, bx) => Row(pats.take(px) ::: pats.drop(px+1), subst, g, bx) }
        return MixtureRule(temps.head, column, rep.make(restTemp,restRows))
    } /* applyRule */

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
    } /* def toString */
  } /* class Rep */
  
  /** creates initial clause matrix
   */
  final def initRep(selector: Tree, cases: List[Tree], checkExhaustive: Boolean, rep:RepFactory)(implicit theOwner: Symbol) = {
    val root = newVar(selector.pos, selector.tpe)
    // communicate whether exhaustiveness-checking is enabled via some flag
    if (!checkExhaustive)
      root.setFlag(symtab.Flags.TRANS_FLAG)
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
    /*val res = */rep.make(List(root), row.toList, targets.toList, vss.toList)
    //Console.println("left initRep")
    //res
  }

  
  // ----------------------------------   helper functions that generate symbols, trees for type tests, pattern tests

  final def newVar(pos: Position, name: Name, tpe: Type)(implicit theOwner: Symbol): Symbol = {
    if(tpe eq null) assert(tpe ne null, "newVar("+name+", null)")
    val sym = theOwner.newVariable(pos, name) // careful: pos has special meaning
    sym setInfo tpe
    sym
  }
  
  final def newVar(pos: Position, tpe: Type)(implicit theOwner: Symbol): Symbol =
    newVar(pos, cunit.fresh.newName("temp"), tpe) setFlag symtab.Flags.SYNTHETIC

  /** returns the condition in "if(cond) k1 else k2" 
   */
  final def condition(tpe: Type, scrut: Symbol): Tree = {
    val res = condition1(tpe, scrut)
    //if (settings_debug)
    //  Console.println("condition, tpe = "+tpe+", scrut.tpe = "+scrut.tpe+", res = "+res)
    res
  }
  final def condition1(tpe: Type, scrut: Symbol): Tree = {
    assert(scrut ne NoSymbol)
    condition(tpe, mkIdent(scrut))
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
  final def addOuterCondition(cond:Tree, tpe2test: Type, scrutinee: Tree, handleOuter: HandleOuter) = {
    val TypeRef(prefix,_,_) = tpe2test
    //Console.println("addOuterCondition: "+prefix)
    assert(prefix ne NoPrefix)
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
