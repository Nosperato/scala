/*                     __                                               *\
**     ________ ___   / /  ___     Scala API                            **
**    / __/ __// _ | / /  / _ |    (c) 2003, LAMP/EPFL                  **
**  __\ \/ /__/ __ |/ /__/ __ |                                         **
** /____/\___/_/ |_/____/_/ | |                                         **
**                          |/                                          **
** $Id$
\*                                                                      */

package scala.collection.immutable;


object Stack {
	def Empty[A] = new Stack[A];
}

/** This class implements immutable stacks using a list-based data
 *  structure. Instances of <code>Stack</code> represent
 *  empty stacks; they can be either created by calling the constructor
 *  directly, or by applying the function <code>Stack.Empty</code>.
 *
 *  @author  Matthias Zenger
 *  @version 1.0, 10/07/2003
 */
class Stack[+A] with Seq[A] {
	
	/** Checks if this stack is empty.
	 *
	 *  @returns true, iff there is no element on the stack.
	 */
	def isEmpty: Boolean = true;
	
	/** Returns the size of this stack.
	 *
	 *  @returns the stack size.
	 */
	def length: Int = 0;
    
    /** Push an element on the stack.
     *
     *  @param   elem		the element to push on the stack.
     *  @returns the stack with the new element on top.
     */
	def +[B >: A](elem: B): Stack[B] = new Node(elem);
    
    /** Push all elements provided by the given iterable object onto
     *  the stack. The last element returned by the iterable object
     *  will be on top of the new stack.
     *
     *  @param   elems		the iterable object.
     *  @returns the stack with the new elements on top.
     */
	def +[B >: A](elems: Iterable[B]): Stack[B] = {
		var res: Stack[B] = this;
		elems.elements.foreach { elem => res = res + elem; }
		res;
	}
	
	/** Push a sequence of elements onto the stack. The last element
	 *  of the sequence will be on top of the new stack.
     *
     *  @param   elems		the element sequence.
     *  @returns the stack with the new elements on top.
     */
	def push[B >: A](elems: B*): Stack[B] = this + elems;
	
	/** Returns the top element of the stack. An error is signaled if
	 *  there is no element on the stack.
     *
     *  @returns the top element.
     */
	def top: A = error("no element on stack");
	
	/** Removes the top element from the stack.
	 *
	 *  @returns the new stack without the former top element.
	 */
	def pop: Stack[A] = error("no element on stack");
	
	/** Returns the n-th element of this stack. The top element has index
	 *  0, elements below are indexed with increasing numbers.
	 *
	 *  @param   n		the index number.
	 *  @returns the n-th element on the stack.
	 */
	def apply(n: Int): A = error("no element on stack");
	
	/** Returns an iterator over all elements on the stack. The iterator
     *  issues elements in the reversed order they were inserted into the
     *  stack (LIFO order).
     *
     *  @returns an iterator over all stack elements.
     */
	def elements: Iterator[A] = toList.elements;
	
	/** Creates a list of all stack elements in LIFO order.
     *
     *  @returns the created list.
     */
	def toList: List[A] = Nil;
	
	/** Compares this stack with the given object.
     *
     *  @returns true, iff the two stacks are equal; i.e. they contain the
     *           same elements in the same order.
     */
	override def equals(obj: Any): Boolean =
		if (obj is Stack[A]) toList.equals((obj as Stack[A]).toList);
		else false;
	
	/** Returns the hash code for this stack.
	 *
	 *  @returns the hash code of the stack.
	 */
	override def hashCode(): Int = 0;
	
	// Here comes true magic: covariant lists with implicit tail references
	
	protected class Node[+B >: A](elem: B) extends Stack[B] {
		override def isEmpty: Boolean = false;
		override def length: Int = Stack.this.length + 1;
		override def +[C >: B](elem: C): Stack[C] = new Node(elem);
		override def +[C >: B](elems: Iterable[C]): Stack[C] = super.+(elems);
		override def top: B = elem;
		override def pop: Stack[B] = Stack.this;
		override def apply(n: Int): B = if (n > 0) Stack.this(n - 1) else elem;
		override def toList: List[B] = elem :: Stack.this.toList;
		override def hashCode(): Int = elem.hashCode() + Stack.this.hashCode();
	}
}
