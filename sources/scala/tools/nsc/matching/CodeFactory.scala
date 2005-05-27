/*
** $Id$
*/                                                                      
package scala.tools.nsc.matching ;

import scala.tools.util.Position;

abstract class CodeFactory extends PatternUtil {

  import global._ ;

  import definitions._;             // standard classes and methods
  import typer.typed;               // methods to type trees
  import posAssigner.atPos;         // for filling in tree positions 

  // --------- these are new
  
  /** a faked switch statement
   */
  def Switch(condition: Array[Tree], body: Array[Tree], defaultBody: Tree): Tree = {
    //assert condition != null:"cond is null";
    //assert body != null:"body is null";
    //assert defaultBody != null:"defaultBody is null";
    var result = defaultBody;
    
    var i = condition.length-1; 
    while (i >= 0) {
      result = If(condition(i), body(i), result);
      i = i - 1
    }
    
    return result ;
  }
  
  /** returns code `<seqObj>.elements' */
  def  newIterator( seqObj:Tree  ): Tree = 
    Apply(Select(seqObj, newTermName("elements")), List());
  
  
  /** `it.next()'     */
  def  _next(iter: Tree) = 
    Apply(Select(iter, definitions.Iterator_next), List());
  
  
  /** `it.hasNext()'  */
  def _hasNext(iter: Tree) =  
    Apply(Select(iter, definitions.Iterator_hasNext), List());
  
  
  /** `!it.hasCur()'  */
  def  _not_hasNext( iter:Tree  ) = 
    Apply(Select(_hasNext(iter), definitions.Boolean_not), List());
  
  
  /** `trace.isEmpty' */
  def isEmpty( iter: Tree  ):  Tree = 
    Apply(Select(iter, definitions.List_isEmpty), List());
          
          
  def SeqTrace_headElem( arg: Tree  ) = { // REMOVE SeqTrace
    val t = Apply(Select(arg, definitions.List_head), List());    
    Apply(Select(t, definitions.tupleField(2,2)),List())
  }

  def SeqTrace_headState( arg: Tree  ) = { // REMOVE SeqTrace
    val t = Apply(Select(arg, definitions.List_head), List());    
    Apply(Select(t, definitions.tupleField(2,1)),List())
  }
  
  def SeqTrace_tail( arg: Tree ): Tree =  // REMOVE SeqTrace
    Apply(Select(arg, definitions.List_tail), List());    

  /** `arg.head' */
  def SeqList_head( arg: Tree ) = 
    Apply(Select(arg, definitions.List_head), List());    
  
  
  def Negate(tree: Tree) = tree match {
    case Literal(value:Boolean)=>
      Literal(!value)
    case _ =>
      Apply(Select(tree, definitions.Boolean_not), List());
  }

  /*protected*/ def And(left: Tree, right: Tree): Tree = left match {
    case Literal(value:Boolean) =>
      if(value) right else left;
    case _ => 
      right match {
        case Literal(true) =>
	  left;
        case _ =>
          Apply(Select(left, definitions.Boolean_and), List(right));
      }
  }

  /*protected*/ def Or(left: Tree, right: Tree): Tree = left match {
      case Literal(value: Boolean)=>
	if(value) left else right;
      case _ =>
        right match {
          case Literal(false) =>
	    left;
          case _ =>
            Apply(Select(left, definitions.Boolean_or), List(right));
        }
  }
  
  // used by Equals
  private def getCoerceToInt(left: Type): Symbol = {
    val sym = left.nonPrivateMember( nme.coerce );
    //assert sym != Symbol.NONE : Debug.show(left);

    sym.alternatives.find {
      x => x.info match {
        case MethodType(vparams, restpe) =>
          vparams.length == 0 && isSameType(restpe,definitions.IntClass.info)
      }
    }.get
  }
  
  // used by Equals
  private def getEqEq(left: Type, right: Type): Symbol = {
    val sym = left.nonPrivateMember( nme.EQEQ );

    //assert sym != Symbol.NONE
    //    : Debug.show(left) + "::" + Debug.show(left.members());

    var fun: Symbol  = null;
    var ftype:Type  = definitions.AnyClass.info;

    sym.alternatives.foreach {
      x => 
        val vparams = x.info.paramTypes;
        if (vparams.length == 1) {
          val vptype = vparams(0); 
          if (isSubType(right, vptype) && isSubType(vptype, ftype)) {
            fun = x;
            ftype = vptype;
          }
        }
    }
    //assert fun != null : Debug.show(sym.info());
    fun;
  }

  def  Equals(left1:Tree , right1:Tree ): Tree = {
    var left = left1;
    var right = right1;
    val ltype = left.tpe.widen;
    var rtype = right.tpe.widen;
    if (isSameType(ltype, rtype)
        && (isSameType(ltype, definitions.CharClass.info)
            || isSameType(ltype,definitions.ByteClass.info)
            || isSameType(ltype,definitions.ShortClass.info)))
      {
        right = Apply(Select(right, getCoerceToInt(rtype)), List());
        rtype = definitions.IntClass.info;
      }
    val eqsym = getEqEq(ltype, rtype);
    Apply(Select(left, eqsym), List(right));
  }
  
  def ThrowMatchError(pos: Int, tpe: Type ) = 
    Apply(
      gen.mkRef(definitions.MatchError_fail),
      List(
        Literal(unit.toString()),
        Literal(Position.line(pos))
      )
    );
    
  def ThrowMatchError(pos:int , tpe:Type , tree:Tree ) = 
   Apply(
     gen.mkRef(definitions.MatchError_report),
     List(
       Literal(unit.toString()),
       Literal(Position.line(pos)),
       tree
     )
   );
    
  def Error(pos: Int, tpe: Type) = 
    ThrowMatchError(pos: Int, tpe: Type );
  
  def newPair(left: Tree, right: Tree) = 
    New(
      Apply(
        gen.mkRef(definitions.TupleClass(2)),
        List(left,right)
      )
    );
  
}

