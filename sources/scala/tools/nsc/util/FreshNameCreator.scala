/* NSC -- new scala compiler
 * Copyright 2005 LAMP/EPFL
 * @author  Martin Odersky
 */
// $Id$
package scala.tools.nsc.util;

import scala.collection.mutable.HashMap;

class FreshNameCreator {
    
  protected var counter = 0;
  protected val counters = new HashMap[String,int];
    
  /**
   * Create a fresh name with the given prefix. It is guaranteed
   * that the returned name has never been returned by a previous
   * call to this function (provided the prefix does not end in a digit).
   */
  def newName(prefix: String): String = {
    val count = counters.get(prefix) match {
      case Some(last) => last + 1
      case None => 0
    }
    counters.update(prefix, count);
    prefix + count
  }

  def newName(): String = {
    counter = counter + 1;
    "$" + counter + "$"
  }
}
