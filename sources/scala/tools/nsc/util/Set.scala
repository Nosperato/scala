/* NSC -- new scala compiler
 * Copyright 2005 LAMP/EPFL
 * @author  Martin Odersky
 */
// $Id$
package scala.tools.nsc.util;

/** A common trait for lightweight sets.
 */
abstract class Set[T <: AnyRef] {

  def findEntry(x: T): T;

  def addEntry(x: T): unit;

  def elements: Iterator[T];

  def contains(x: T): boolean = 
    findEntry(x) != null;

  def toList = elements.toList;

}
