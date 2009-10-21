/*                     __                                               *\
**     ________ ___   / /  ___     Scala API                            **
**    / __/ __// _ | / /  / _ |    (c) 2006-2009, LAMP/EPFL             **
**  __\ \/ /__/ __ |/ /__/ __ |    http://scala-lang.org/               **
** /____/\___/_/ |_/____/_/ | |                                         **
**                          |/                                          **
\*                                                                      */

// $Id$


package scala.collection
package generic

import mutable.Builder

/** A template for companion objects of Set and subclasses thereof.
 *
 *  @since 2.8
 */
abstract class SortedSetFactory[CC[A] <: SortedSet[A] with SortedSetLike[A, CC[A]]] {
  type Coll = CC[_]

  def newBuilder[A](implicit ord: Ordering[A]): Builder[A, CC[A]]

  def empty[A](implicit ord: Ordering[A]): CC[A]

  def apply[A](elems: A*)(implicit ord: Ordering[A]): CC[A] = (newBuilder[A](ord) ++= elems).result

  implicit def newCanBuildFrom[A](implicit ord : Ordering[A]) : CanBuildFrom[Coll, A, CC[A]] = new SortedSetCanBuildFrom()(ord);

  class SortedSetCanBuildFrom[A](implicit ord: Ordering[A]) extends CanBuildFrom[Coll, A, CC[A]] {
    : Coll) = newBuilder[A](ord)
    def apply() = newBuilder[A](ord)
  }
}
