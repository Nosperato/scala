package scala.xml.nobinding ;

import org.xml.sax.InputSource;

import scala.collection.Map ;
import scala.collection.mutable.HashMap ;

import scala.xml.Utility ;
/** functions to load and save XML elements. use this when data binding is not 
**  desired.
**/
object XML {

  // functions for generic xml loading, saving
  
  /** loads XML from a given file*/
  def load( source:InputSource ):Symbol = 
    new NoBindingFactoryAdapter().loadXML( source );

  /** saves XML to filename with encoding ISO-8859-1 */
  def save( filename:String, doc:Symbol ):Unit = {
    /* using NIO classes of JDK 1.4 */
    import java.io.{FileOutputStream,Writer};
    import java.nio.channels.{Channels,FileChannel};

    val fos = new FileOutputStream( filename );
    val w:Writer = Channels.newWriter( fos.getChannel(), "ISO-8859-1" );
    
    /* 2do: optimize by giving writer parameter to toXML*/
    w.write( Utility.toXML( doc ));
    
    w.close();
    fos.close();
  }

}
