package scala.xml.path;

import scala.xml.Element ;

object Expression {
        def evaluate(expr:Expression):List[Element] = {
                Nil; // to do
        }
}

abstract class Expression ;

/*
case class Node[T<:Element]( conds:Condition* ) extends Expression {
        type t = T;
        
        def test( x:Element ):boolean = {
                x.isInstanceOf[t];
        }
};
*/
case class Node( label:String, cond:Option[List[List[Expression]]] ) extends Expression ;

case class Attribute( name:String ) extends Expression;

case object Wildcard extends Expression;

case object Descendant extends Expression;

/*
case class Present(attr:Attribute) extends Condition;
case class Equals(attr:Attribute, str:String) extends Condition;
*/
