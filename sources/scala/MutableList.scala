/*                     __                                               *\
**     ________ ___   / /  ___     Scala API                            **
**    / __/ __// _ | / /  / _ |    (c) 2003, LAMP/EPFL                  **
**  __\ \/ /__/ __ |/ /__/ __ |                                         **
** /____/\___/_/ |_/____/_/ | |                                         **
**                          |/                                          **
** $Id$
\*                                                                      */

package scala;


class MutableList[A] with Seq[A] with PartialFunction[Int, A] {
    
    protected var first: LinkedList[A] = null;
    protected var last: LinkedList[A] = null;
    protected var len: Int = 0;
    
    def length: Int = len;
    
    def isDefinedAt(n: Int) = (n >= 0) && (n < len);
    
    def apply(n: Int): A = get(n) match {
        case None => null
        case Some(value) => value
    }
    
    def get(n: Int): Option[A] = first.get(n);
    
    def at(n: Int): A = apply(n);
    
    protected def prependElem(elem: A) = {
    	first = new LinkedList[A](elem, first);
        if (len == 0)
            last = first;
        len = len + 1;
    }
    
    protected def appendElem(elem: A) = {
        if (len == 0)
            prependElem(elem);
        else {
            last.next = new LinkedList[A](elem, null);
            last = last.next;
            len = len + 1;
        }
    }
    
    protected def reset: Unit = {
    	first = null;
    	last = null;
    	len = 0;
    }
    
    def elements: Iterator[A] =
    	if (first == null) Nil.elements else first.elements;
	
    def toList: List[A] = if (first == null) Nil else first.toList;
    
    override def toString() = toList.toString();
}
