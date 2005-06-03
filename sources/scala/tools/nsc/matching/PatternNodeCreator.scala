package scala.tools.nsc.matching;

import scala.tools.util.Position;

/** PatternNode factory.
 *  we inherit the globals from PatternTool.
 */

trait PatternNodeCreator: (TransMatcher with PatternNodes) {

  import global._;

  def owner:Symbol;
  /** the owner of the variable symbols that might be created */
  //Symbol owner;
  
  def pSequencePat(pos: Int , tpe:Type ,  len:int) = {
    val sym = newVar(Position.FIRSTPOS, tpe);
    val node = new SequencePat(sym, len);
    node.pos = pos;
    node.tpe = tpe;
    node;
  }
  
  def pSeqContainerPat(pos: int, tpe: Type, seqpat:Tree ) = {
    val sym = newVar(Position.NOPOS, tpe);
    val node = new SeqContainerPat(sym, seqpat);
    node.pos = pos;
    node.setType(tpe);
    node;
  }
  
  def pDefaultPat(pos: int, tpe: Type) = {
    val node = new DefaultPat();
    node.pos = pos;
    node.setType(tpe);
    node;
  }
  
  def pConstrPat(pos: int, tpe: Type) = {
    val node = new ConstrPat(newVar(pos, tpe));
    node.pos = pos;
    node.setType(tpe);
    node;
  }
  
  def pConstantPat(pos: int, tpe: Type, value: Any /*AConstant*/ ) = {
    val node = new ConstantPat( value );
    node.pos = pos;
    node.setType(tpe);
    node;
  }
  
  def pVariablePat(pos: int,  tree:Tree) = {
    val node = new VariablePat( tree );
    node.pos = pos;
    node.setType(tree.tpe);
    node;
  }
  
  def pAltPat(pos: int, header:Header ) = {
    val node = new AltPat(header);
    node.pos = pos;
    node.setType(header.getTpe());
    node;
  }
  
  // factories
  
  def pHeader(pos: int, tpe: Type,  selector:Tree) = {
    val node = new Header(selector, null);
    node.pos = pos;
    node.setType(tpe);
    node;
  }
    
  def pBody(pos: int) = {
    val node = new Body(new Array[Array[ValDef]](0), new Array[Tree](0), new Array[Tree](0));
    node.pos = pos;
    node;
  }
  
  def pBody(pos: int, bound:Array[ValDef] ,  guard:Tree,  body:Tree) = {
    val node = new Body(Predef.Array[Array[ValDef]](bound), Predef.Array[Tree](guard), Predef.Array[Tree](body));
    node.pos = pos;
    node;
  }
  
  def newVar(pos: int, name: Name, tpe: Type): Symbol= {
    val sym = owner.newVariable(0, name);
    sym.setInfo(tpe);
    //System.out.println("PatternNodeCreator::newVar creates symbol "+sym);
    //System.out.println("owner: "+sym.owner());
    sym;
  }
  
  def newVar(pos: int, tpe: Type): Symbol = {
    newVar(pos, cunit.fresh.newName("temp"), tpe);
  }
}

