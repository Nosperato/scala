/*                     __                                               *\
**     ________ ___   / /  ___     Scala API                            **
**    / __/ __// _ | / /  / _ |    (c) 2003-2004, LAMP/EPFL             **
**  __\ \/ /__/ __ |/ /__/ __ |                                         **
** /____/\___/_/ |_/____/_/ | |                                         **
**                          |/                                          **
** $Id$
\*                                                                      */
package scala.xml.nobinding ;

import org.xml.sax.InputSource;

import scala.collection.Map ;
import scala.collection.mutable.HashMap ;

import scala.xml.Utility ;

/** functions to load and save XML elements. use this when data binding is not 
**  desired, i.e. when XML is handled using Symbol nodes
**/
object XML with Function1[InputStream,Elem] with Function1[String,Elem] with Function1[Reader,Elem] with Function1[InputSource,Elem] {

  // functions for generic xml loading, saving

  /** loads XML from given file */
  def loadFile( file:File ):scala.xml.Elem = 
    new NoBindingFactoryAdapter().loadXML( new InputSource( 
      FileInputStream( file )
    ));

  /** loads XML from given file descriptor */
  def loadFile( fileDesc:FileDescriptor ):scala.xml.Elem = 
    new NoBindingFactoryAdapter().loadXML( new InputSource( 
      FileInputStream( fileDesc )
    ));

  /** loads XML from given file */
  def loadFile( fileName:String ):scala.xml.Elem = 
    new NoBindingFactoryAdapter().loadXML( new InputSource( 
      FileInputStream( fileName )
    ));

  /** loads XML from given InputStream */
  def load( is:InputStream ):scala.xml.Elem = 
    new NoBindingFactoryAdapter().loadXML( new InputSource( is ));

  /** loads XML from given Reader */
  def load( reader:Reader ):scala.xml.Elem = 
    new NoBindingFactoryAdapter().loadXML( new InputSource( reader ));

  /** loads XML from given sysID */
  def load( sysID:String ):scala.xml.Elem = 
    new NoBindingFactoryAdapter().loadXML( new InputSource( sysID ));
  
  /** loads XML from a given input source*/
  def load( source:InputSource ):scala.xml.Elem = 
    new NoBindingFactoryAdapter().loadXML( source );

  /** saves XML to filename with encoding ISO-8859-1 */
  def save( filename:String, doc:Elem ):Unit = {
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

  /** loads XML from given InputStream */
  def apply( is:InputStream ):scala.xml.Elem = 
    new NoBindingFactoryAdapter().loadXML( new InputSource( is ));

  /** loads XML from given Reader */
  def apply( reader:Reader ):scala.xml.Elem = 
    new NoBindingFactoryAdapter().loadXML( new InputSource( reader ));

  /** loads XML from given sysID */
  def apply( sysID:String ):scala.xml.Elem = 
    new NoBindingFactoryAdapter().loadXML( new InputSource( sysID ));
  
  /** loads XML from a given input source*/
  def apply( source:InputSource ):scala.xml.Elem = 
    new NoBindingFactoryAdapter().loadXML( source );

}
