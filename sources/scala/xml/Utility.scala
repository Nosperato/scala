package scala.xml ;

import java.lang.StringBuffer ; /* Java dependency! */
import scala.collection.Map ;

/** Utility functions for processing instances of bound and not bound XML classes.
**/

object Utility {

  /** representation of text in a string that is well-formed XML.
  **  the characters &lt; &gt; &amp; and &quot; are escaped
  */
  def escape( text:String ) = {
    val s = new StringBuffer();
    for( val c <- new IterableString(text).elements ) c match {
      case '<' => val _ = s.append("&lt;");
      case '>' => val _ = s.append("&gt;");
      case '&' => val _ = s.append("&amp;");
      case '"' => val _ = s.append("&quot;");
      case _   => val _ = s.append( c );
    }
    s.toString();
  }

  /** serializes an instance of Node to a string that contains well-formed XML **/
  def toXML( n:Node ):String = { n match {
    case Text( t ) => 
      escape( t );
    case x:AttributedNode => 
      val s = new StringBuffer();
      s.append("<");
      s.append( x.label );
      if( null != x.attributes ) {
        s.append( attr2xml( x.attributes.elements ) );{}
      }
      s.append(">");
      s.append( toXML( x.children.elements ) );
      s.append("</");
      s.append( x.label );
      s.append(">");
      s.toString()
  }}

  def toXML( ch:Iterator[Node] ):String = {
    ch.foldLeft ("") { (s:String,n:Node) => { val t:String = toXML( n ); s + t }}
  }

  /** for a Node n, returns string representation of n.attributes **/
  def attr2xml( attrib:Iterator[Pair[String, String]] ):String = {
    attrib.foldLeft ("") { (s:String,x:Pair[String,String]) => { 
        val t = new StringBuffer(s); 
        t.append( " " );
        t.append( x._1 );
        t.append("=\"");
        t.append( x._2 );
        t.append("\"");
        t.toString()
    }
    }
  }

  /** returns a hashcode for the given constituents of a node */
  def hashCode( label:String, attribHashCode:int, children:Seq[Node] ) = {
    label.hashCode() + attribHashCode + children.hashCode()
  }

  /** returns a hashcode for the given constituents of a node */
  def hashCode( label:String, attribs:scala.collection.mutable.HashMap[String,String], children:Seq[Node] ) = {
    label.hashCode() + attribs.toList.hashCode() + children.hashCode()
  }

}
