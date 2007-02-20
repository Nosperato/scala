
/*                     __                                               *\
**     ________ ___   / /  ___     Scala API                            **
**    / __/ __// _ | / /  / _ |    (c) 2002-2007, LAMP/EPFL             **
**  __\ \/ /__/ __ |/ /__/ __ |                                         **
** /____/\___/_/ |_/____/_/ | |                                         **
**                          |/                                          **
\*                                                                      */

// $Id$

// generated by genprod on Tue Feb 20 15:57:32 CET 2007

package scala

/** Tuple7 is the canonical representation of a @see Product7 */
case class Tuple7[+T1, +T2, +T3, +T4, +T5, +T6, +T7](_1:T1, _2:T2, _3:T3, _4:T4, _5:T5, _6:T6, _7:T7) 
  extends Product7[T1, T2, T3, T4, T5, T6, T7]  {

   override def toString() = {
     val sb = new compat.StringBuilder
     sb.append('(').append(_1).append(',').append(_2).append(',').append(_3).append(',').append(_4).append(',').append(_5).append(',').append(_6).append(',').append(_7).append(')')
     sb.toString
   }
}
