package scala.util.automata ;

import scala.collection.{ Set, Map };

/** A deterministic automaton. States are integers, where
 *  0 is always the only initial state. Transitions are represented
 *  in the delta function. A default transitions is one that
 *  is taken when no other transition can be taken.
 *  All states are reachable. Accepting states are those for which
 *  the partial function 'finals' is defined.
 */
abstract class DetWordAutom {

  type T_label;

  val nstates:  Int;
  val finals:   PartialFunction[Int,Int] ;
  val delta:    Function1[Int,Map[T_label,Int]];
  val default:  PartialFunction[Int,Int] ;

  override def toString() = {
    val sb = new StringBuffer();
    sb.append("[nfa nstates=");
    sb.append(nstates);
    sb.append(" finals=");
    var map = new scala.collection.immutable.ListMap[Int,Int];
    var j = 0; while( j < nstates ) {
      if(finals.isDefinedAt(j)) 
        map = map.update(j,finals(j))
    }
    sb.append(map.toString());
    sb.append(" delta=\n");
    for( val i <- Iterator.range(0,nstates)) {
      sb.append( i );
      sb.append("->");
      sb.append(delta(i).toString());
      sb.append('\n');
      if(default.isDefinedAt(i)) {
        sb.append("_>");
        sb.append(default(i).toString());
        sb.append('\n');
      }
    }
    sb.toString();
  }
}
