/*                     __                                               *\
**     ________ ___   / /  ___     Scala API                            **
**    / __/ __// _ | / /  / _ |    (c) 2002-2010, LAMP/EPFL             **
**  __\ \/ /__/ __ |/ /__/ __ |    http://scala-lang.org/               **
** /____/\___/_/ |_/____/_/ | |                                         **
**                          |/                                          **
\*                                                                      */

// $Id$

// generated by genprod on Mon Nov 30 12:09:35 PST 2009  (with extra methods)

package scala



/** <p>
 *    Function with 3 parameters.
 *  </p>
 *  
 */
trait Function3[-T1, -T2, -T3, +R] extends AnyRef { self =>
  def apply(v1:T1,v2:T2,v3:T3): R
  override def toString() = "<function3>"
  
  /** f(x1, x2, x3)  == (f.curry)(x1)(x2)(x3)
   */
  def curry: T1 => T2 => T3 => R = {
    (x1: T1) => (x2: T2) => (x3: T3) => apply(x1, x2, x3)
  }

  /* f(x1, x2, x3) == (f.tuple)(Tuple3(x1, x2, x3))
   */
  def tuple: Tuple3[T1, T2, T3] => R = {
    case Tuple3(x1, x2, x3) => apply(x1, x2, x3)
  }

}
