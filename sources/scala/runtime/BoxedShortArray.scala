/*                     __                                               *\
**     ________ ___   / /  ___     Scala API                            **
**    / __/ __// _ | / /  / _ |    (c) 2002, LAMP/EPFL                  **
**  __\ \/ /__/ __ |/ /__/ __ |                                         **
** /____/\___/_/ |_/____/_/ | |                                         **
**                          |/                                          **
\*                                                                      */
package scala.runtime;

final class BoxedShortArray(val value: Array[Short]) extends BoxedArray {

  def length: Int = value.length;

  def apply(index: Int): Object = BoxedShort.box(value(index));

  def update(index: Int, elem: Object): Unit = { 
    value(index) = elem.asInstanceOf[BoxedNumber].shortValue() 
  }

  def unbox(elemClass: Class): Object = value;

  override def equals(other: Any) =
    value == other ||
    other.isInstanceOf[BoxedShortArray] && value == other.asInstanceOf[BoxedShortArray].value;

  override def hashCode(): Int = value.hashCode();
}


