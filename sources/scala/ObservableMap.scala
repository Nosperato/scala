/*                     __                                               *\
**     ________ ___   / /  ___     Scala API                            **
**    / __/ __// _ | / /  / _ |    (c) 2003, LAMP/EPFL                  **
**  __\ \/ /__/ __ |/ /__/ __ |                                         **
** /____/\___/_/ |_/____/_/ | |                                         **
**                          |/                                          **
** $Id$
\*                                                                      */

package scala;


abstract class ObservableMap[A, B, This <: ObservableMap[A, B, This]]: This
                    extends MutableMap[A, B]
                    with Publisher[ObservableUpdate[Pair[A, B]] with Undo, This] {
    
    override def update(key: A, value: B): Unit = get(key) match {
        case None => super.update(key, value);
                     publish(new Inclusion(Pair(key, value)) with Undo {
                                 def undo = remove(key);
                             });
        case Some(old) => super.update(key, value);
                          publish(new Modification(Pair(key, old), Pair(key, value)) with Undo {
                                      def undo = update(key, old._2);
                                  });
    }
    
    override def remove(key: A): Unit = get(key) match {
        case None =>
        case Some(old) => super.remove(key);
                          publish(new Removal(Pair(key, old)) with Undo {
                                      def undo = update(key, old);
                                  });
    }
    
    override def clear: Unit = {
        super.clear;
        publish(new Reset() with Undo { def undo = error("cannot undo"); });
    }
}
