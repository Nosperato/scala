/*                     __                                               *\
**     ________ ___   / /  ___     Scala API                            **
**    / __/ __// _ | / /  / _ |    (c) 2003, LAMP/EPFL                  **
**  __\ \/ /__/ __ |/ /__/ __ |                                         **
** /____/\___/_/ |_/____/_/ | |                                         **
**                          |/                                          **
\*                                                                      */

// $Id$

package scala.collection.mutable;


/** This trait represents mutable maps. Concrete map implementations
 *  just have to provide functionality for the abstract methods in
 *  <code>scala.collection.Map</code> as well as for <code>update</code>,
 *  and <code>remove</code>.
 *
 *  @author  Matthias Zenger
 *  @version 1.0, 08/07/2003
 */
trait Map[A, B] with scala.collection.Map[A, B] {
    
    def update(key: A, value: B): Unit;
    
    def -=(key: A): Unit;
    
    def +=(key: A): MapTo = new MapTo(key);
    
    def incl(mappings: Pair[A, B]*): Unit = {
        val ys = mappings.asInstanceOf[List[Pair[A, B]]];
        ys foreach { case Pair(key, value) => update(key, value); };
    }
    
    def incl(map: Iterable[Pair[A, B]]): Unit = map.elements foreach {
        case Pair(key, value) => update(key, value);
    }
    
    def excl(keys: A*): Unit = excl(keys);
    
    def excl(keys: Iterable[A]): Unit = keys.elements foreach -=;
    
    def clear: Unit = keys foreach -=;
    
    def map(f: (A, B) => B): Unit = elements foreach {
        case Pair(key, value) => update(key, f(key, value));
    }
    
    def filter(p: (A, B) => Boolean): Unit = toList foreach {
        case Pair(key, value) => if (p(key, value)) -=(key);
    }
    
    override def toString() =
        if (size == 0)
            "{}"
        else
            "{" + {
                val iter = elements;
                var res = mappingToString(iter.next);
                while (iter.hasNext) {
                    res = res + ", " + mappingToString(iter.next);
                }
                res;
            } + "}";
    
    def mappingToString(p: Pair[A, B]) = p._1.toString() + " -> " + p._2;
    
    class MapTo(key: A) {
        def ->(value: B): Unit = update(key, value);
    }
}
