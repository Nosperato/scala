/*     ____ ____  ____ ____  ______                                     *\
**    / __// __ \/ __// __ \/ ____/    SOcos COmpiles Scala             **
**  __\_ \/ /_/ / /__/ /_/ /\_ \       (c) 2002, LAMP/EPFL              **
** /_____/\____/\___/\____/____/                                        **
**                                                                      **
\*                                                                      */

// $Id$

package scala.tools.scalac.ast;

import scalac.ast._;

/** List of trees.
 */

final class TreeList(ts: Array[Tree]) {
  
  private var trees = ts;
  private var len = ts.length;

  def this() = { this(new Array[Tree](4)); len = 0 }

  def append(tree: Tree): TreeList = {
    if (len == trees.length) {
      val ts = new Array[Tree](if (len == 0) 4 else len * 2);
      System.arraycopy(trees.asInstanceOf[Array[Object]], 0, 
		       ts.asInstanceOf[Array[Object]], 0, len);
      trees = ts;
    }
    trees(len) = tree;
    len = len + 1;
    this
  }

  def append(ts: Array[Tree]): unit = {
    for (val j <- Iterator.range(0, ts.length)) do
      append(ts(j));
  }

  def append(tl: TreeList): unit = {
    for (val j <- Iterator.range(0, ts.length)) do
      append(tl.trees(j));
  }

  def clear(): unit = {
    trees = new Array[Tree](4);
    len = 0;
  }

  def length(): int = len;

  def get(i: int): Tree = trees(i);

  def first(): Tree = trees(0);

  def removeLast(): Tree = {
    len = len - 1;
    trees(len)
  }

  def toArray(): Array[Tree] = {
    val ts = new Array[Tree](len);
    System.arraycopy(trees.asInstanceOf[Array[Object]], 0, 
		     ts.asInstanceOf[Array[Object]], 0, len);
    ts
  }

  def copyTo[t <: Tree](ts: Array[t]): Array[t] = copyTo(ts, 0);
  
  def copyTo[t <: Tree](ts: Array[t], from: int): Array[t] = {
    System.arraycopy(trees.asInstanceOf[Array[java.lang.Object]], 0, 
		     ts.asInstanceOf[Array[java.lang.Object]], from, len);
    ts;
  }
}
