/*                     __                                               *\
**     ________ ___   / /  ___     Scala API                            **
**    / __/ __// _ | / /  / _ |    (c) 2003, LAMP/EPFL                  **
**  __\ \/ /__/ __ |/ /__/ __ |                                         **
** /____/\___/_/ |_/____/_/ | |                                         **
**                          |/                                          **
** $Id$
\*                                                                      */

package scala.collection.immutable;


object ListSet {
	def Empty[A] = new ListSet[A];
}

/** This class implements immutable sets using a list-based data
 *  structure. Instances of <code>ListSet</code> represent
 *  empty sets; they can be either created by calling the constructor
 *  directly, or by applying the function <code>ListSet.Empty</code>.
 *
 *  @author  Matthias Zenger
 *  @version 1.0, 09/07/2003
 */
class ListSet[A] with Set[A, ListSet[A]] {
	
	def size: Int = 0;
    
	def contains(elem: A): Boolean = false;
    
	def +(elem: A): ListSet[A] = new Node(elem);
    
	def -(elem: A): ListSet[A] = this;
	
	def elements: Iterator[A] = toList.elements;
	
	override def toList: List[A] = Nil;
	
	protected class Node(elem: A) extends ListSet[A] {
		override def size = ListSet.this.size + 1;
		override def isEmpty: Boolean = false;
		override def contains(e: A) = (e == elem) || ListSet.this.contains(e);
		override def +(e: A): ListSet[A] = if (contains(e)) this else new Node(e);
		override def -(e: A): ListSet[A] = if (e == elem) ListSet.this else {
			val y = ListSet.this - e; (new y.Node(elem)): ListSet[A]
		}
		override def toList: List[A] = elem :: ListSet.this.toList;
	}
}
