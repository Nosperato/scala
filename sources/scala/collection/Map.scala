/*                     __                                               *\
**     ________ ___   / /  ___     Scala API                            **
**    / __/ __// _ | / /  / _ |    (c) 2003, LAMP/EPFL                  **
**  __\ \/ /__/ __ |/ /__/ __ |                                         **
** /____/\___/_/ |_/____/_/ | |                                         **
**                          |/                                          **
** $Id$
\*                                                                      */

package scala.collection;


/** This trait defines the interface of collections that unambiguously map
 *  keys to values (i.e. a key is mapped to at least one value).
 *  Trait <code>Map</code> may only be used for
 *  accessing elements from map implementations. Two different extensions
 *  of trait <code>Map</code> in the package <code>scala.collections.mutable</code>
 *  and  <code>scala.collections.immutable</code> provide functionality for
 *  adding new key/value mappings to a map. The trait in the first package is
 *  implemented by maps that are modified destructively, whereas the trait in
 *  the second package is used by functional map implementations that rely on
 *  immutable data structures.
 * 
 *  @author  Matthias Zenger
 *  @version 1.0, 08/07/2003
 */
trait Map[A, +B] with PartialFunction[A, B]
                 with Iterable[Pair[A, B]] {

    def size: Int;
    
    def get(key: A): Option[B];

    def isEmpty: Boolean = (size == 0);
    
    def apply(key: A): B = get(key) match {
        case None => error("key not found")
        case Some(value) => value
    }
    
    def contains(key: A): Boolean = get(key) match {
        case None => false
        case Some(_) => true
    }
    
    def isDefinedAt(key: A) = contains(key);
    
    def keys: Iterator[A] = new Iterator[A] {
        val iter = Map.this.elements;
        def hasNext = iter.hasNext;
        def next = iter.next._1;
    }
    
    def values: Iterator[B] = new Iterator[B] {
        val iter = Map.this.elements;
        def hasNext = iter.hasNext;
        def next = iter.next._2;
    }
    
    def foreach(f: (A, B) => Unit) = {
        val iter = elements;
        while (iter.hasNext) {
            val Pair(key, value) = iter.next;
            f(key, value);
        }
    }
    
    def toList: List[Pair[A, B]] = {
        var res: List[Pair[A, B]] = Nil;
        val iter = elements;
        while (iter.hasNext) {
            res = iter.next :: res;
        }
        res;
    }
    
    override def toString() =
        if (size == 0)
            "{}"
        else
            "{" + {
                val iter = elements;
                var res = iter.next.toString();
                while (iter.hasNext) {
                    res = res + ", " + iter.next;
                }
                res;
            } + "}";
}
