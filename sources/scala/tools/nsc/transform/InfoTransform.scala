/* NSC -- new scala compiler
 * Copyright 2005 LAMP/EPFL
 * @author
 */
// $Id$
package scala.tools.nsc.transform;

/** A base class for transforms. 
 *  A transform contains a compiler phase which applies a tree transformer.
 */ 
abstract class InfoTransform extends Transform {

  import global.{Symbol, Type, InfoTransformer, infoTransformers};

  def transformInfo(sym: Symbol, tpe: Type): Type;

  class Phase(prev: scala.tools.nsc.Phase) extends super.Phase(prev) {
    val infoTransformer = new InfoTransformer {
      val phase = Phase.this;
      def transform(sym: Symbol, tpe: Type): Type = transformInfo(sym, tpe);
    }
    infoTransformers.insert(infoTransformer)
  }
}

