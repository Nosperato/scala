/*                     __                                               *\
**     ________ ___   / /  ___     Scala API                            **
**    / __/ __// _ | / /  / _ |    (c) 2003, LAMP/EPFL                  **
**  __\ \/ /__/ __ |/ /__/ __ |                                         **
** /____/\___/_/ |_/____/_/ | |                                         **
**                          |/                                          **
** $Id$
\*                                                                      */

package scala.collection.mutable;


/** Buffers are used to create sequences of elements incrementally by
 *  appending or prepending new elements. It is also possible to
 *  access and modify elements in a random access fashion via the
 *  index of the element in the sequence.
 *  
 *  @author  Matthias Zenger
 *  @version 1.0, 08/07/2003
 */
class Buffer[A] with MutableList[A] with StructuralEquality[Buffer[A]] {
    
    def prepend(elem: A) = prependElem(elem);
    
    def append(elems: A*) = (this += elems);
    
    def +=(elem: A) = appendElem(elem);
    
    def +=(iter: Iterable[A]) = iter.elements.foreach(e => appendElem(e));
    
    def update(n: Int, newelem: A): Unit = {
        var elem = first;
        var i = n;
        while (i > 0) {
            elem = elem.next;
            if (elem == null)
                error("cannot update element " + n + " in Buffer");
            i = i - 1;
        }
        elem.elem = newelem;
    }
    
    def insert(n: Int, newelem: A): Unit = {
        if (n == 0)
            prepend(newelem);
        else if (n >= len)
            append(newelem);
        else {
            var elem = first;
            var i = n;
            while (i > 1) {
                elem = elem.next;
                if (elem == null)
                    error("cannot insert element " + n + " in Buffer");
                i = i - 1;
            }
            val old = elem.next;
            elem.next = new LinkedList[A](newelem, old);
        }
    }
    
    def remove(n: Int): A = {
        val old = apply(n);
        if (n >= len)
            error("cannot remove element " + n + " in Buffer");
        if ((n == 0) && (len == 1)) {
            first = null;
            last = null;
        } else if (n == 0) {
            first = first.next;
        } else {
            var elem = first;
            var i = n;
            while (i > 1) {
                elem = elem.next;
                i = i - 1;
            }
            elem.next = elem.next.next;
            if (n == (len - 1)) {
                last = elem.next;
            }
        }
        len = len - 1;
        old;
    }
    
    def clear: Unit = reset;
    
    /** Checks if two buffers are structurally identical.
     *  
     *  @returns true, iff both buffers contain the same sequence of elements.
     */
    override def ===[B >: Buffer[A]](that: B) =
        that.isInstanceOf[Buffer[A]] &&
        { val other = that.asInstanceOf[Buffer[A]];
          elements.zip(other.elements).forall {
            case Pair(thiselem, thatelem) => thiselem == thatelem;
        }};
}
