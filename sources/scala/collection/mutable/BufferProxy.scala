/*                     __                                               *\
**     ________ ___   / /  ___     Scala API                            **
**    / __/ __// _ | / /  / _ |    (c) 2003, LAMP/EPFL                  **
**  __\ \/ /__/ __ |/ /__/ __ |                                         **
** /____/\___/_/ |_/____/_/ | |                                         **
**                          |/                                          **
** $Id$
\*                                                                      */

package scala.collection.mutable;


/** This is a simple proxy class for <code>scala.collection.mutable.Buffer</code>.
 *  It is most useful for assembling customized set abstractions
 *  dynamically using object composition and forwarding.
 *
 *  @author  Matthias Zenger
 *  @version 1.0, 16/04/2004
 */
class BufferProxy[A](buf: Buffer[A]) extends Buffer[A] with Proxy(buf) {

    def length: Int = buf.length;
    
    def elements: Iterator[A] = buf.elements;
    
    def apply(n: Int): A = buf.apply(n);
    
    /** Append a single element to this buffer and return
     *  the identity of the buffer.
     *
     *  @param elem  the element to append.
     */
    def +(elem: A): Buffer[A] = buf.+(elem);
    
    /** Append a single element to this buffer.
     *
     *  @param elem  the element to append.
     */
    override def +=(elem: A): Unit = buf.+=(elem);
    
    /** Appends a number of elements provided by an iterable object
     *  via its <code>elements</code> method. The identity of the
     *  buffer is returned.
     *
     *  @param iter  the iterable object.
     */
    override def ++(iter: Iterable[A]): Buffer[A] = buf.++(iter);
    
    /** Appends a number of elements provided by an iterable object
     *  via its <code>elements</code> method.
     *
     *  @param iter  the iterable object.
     */
    override def ++=(iter: Iterable[A]): Unit = buf.++=(iter);

    /** Appends a sequence of elements to this buffer.
     *
     *  @param elems  the elements to append.
     */
    override def append(elems: A*): Unit = buf.++=(elems);
    
    /** Appends a number of elements provided by an iterable object
     *  via its <code>elements</code> method.
     *
     *  @param iter  the iterable object.
     */
    override def appendAll(iter: Iterable[A]): Unit = buf.appendAll(iter);
    
    /** Prepend a single element to this buffer and return
     *  the identity of the buffer.
     *
     *  @param elem  the element to append.
     */
    def +:(elem: A): Buffer[A] = buf.+:(elem);
    
    /** Prepends a number of elements provided by an iterable object
     *  via its <code>elements</code> method. The identity of the
     *  buffer is returned.
     *
     *  @param iter  the iterable object.
     */
    override def ++:(iter: Iterable[A]): Buffer[A] = buf.++:(iter);
    
    /** Prepend an element to this list.
     *
     *  @param elem  the element to prepend.
     */
    override def prepend(elems: A*): Unit = buf.prependAll(elems);
    
    /** Prepends a number of elements provided by an iterable object
     *  via its <code>elements</code> method. The identity of the
     *  buffer is returned.
     *
     *  @param iter  the iterable object.
     */
    override def prependAll(elems: Iterable[A]): Unit = buf.prependAll(elems);
    
    /** Inserts new elements at the index <code>n</code>. Opposed to method
     *  <code>update</code>, this method will not replace an element with a
     *  one. Instead, it will insert the new elements at index <code>n</code>.
     *
     *  @param n      the index where a new element will be inserted.
     *  @param elems  the new elements to insert.
     */
    override def insert(n: Int, elems: A*): Unit = buf.insertAll(n, elems);
    
    /** Inserts new elements at the index <code>n</code>. Opposed to method
     *  <code>update</code>, this method will not replace an element with a
     *  one. Instead, it will insert a new element at index <code>n</code>.
     *
     *  @param n     the index where a new element will be inserted.
     *  @param iter  the iterable object providing all elements to insert.
     */
    def insertAll(n: Int, iter: Iterable[A]): Unit = buf.insertAll(n, iter);
    
    /** Replace element at index <code>n</code> with the new element
     *  <code>newelem</code>.
     *
     *  @param n       the index of the element to replace.
     *  @param newelem the new element.
     */
    def update(n: Int, newelem: A): Unit = buf.update(n, newelem);

    /** Removes the element on a given index position.
     *
     *  @param n  the index which refers to the element to delete.
     */
    def remove(n: Int): A = buf.remove(n);
    
    /** Clears the buffer contents.
     */
    def clear: Unit = buf.clear;
    
    /** Return a clone of this buffer.
     *
     *  @return a <code>Buffer</code> with the same elements.
     */
    override def clone(): Buffer[A] = buf.clone();
}
