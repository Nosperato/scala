package scala.xml ;


import java.io.{OutputStream,OutputStreamWriter,PrintWriter,Writer};
import java.io.UnsupportedEncodingException;
import java.net.URL;


/*import java.util.Map;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Stack;
import java.util.Iterator;
*/
import scala.collection.mutable.{HashMap,Stack};

import org.xml.sax.Attributes;
import org.xml.sax.ContentHandler;


import org.xml.sax.ErrorHandler;
import org.xml.sax.Locator;

import org.xml.sax.SAXException;
import org.xml.sax.SAXNotRecognizedException;
import org.xml.sax.SAXNotSupportedException;
import org.xml.sax.SAXParseException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;
import org.xml.sax.helpers.ParserAdapter;

import org.xml.sax.helpers.XMLReaderFactory;

abstract class FactoryAdapter  
	 extends DefaultHandler()
	 // with ContentHandler
	 // with ErrorHandler  // SAX2 
{
  // default settings

  /** Default parser name - included in JDK1.4 */
  val DEFAULT_PARSER_NAME   = "org.apache.crimson.parser.XMLReaderImpl"; 
  //val DEFAULT_PARSER_NAME   = "org.apache.xerces.parsers.SAXParser";
      
  /** Namespaces feature id (http://xml.org/sax/features/namespaces). */
  val NAMESPACES_FEATURE_ID = "http://xml.org/sax/features/namespaces";
      
  //
  // Constructors
  //

  val buffer = new StringBuffer();
  val attribStack = new Stack[HashMap[String,String]];
  val hStack  = new Stack[Node];   // [ element ] contains siblings
  val tagStack  = new Stack[String]; // [String]
  
  var curTag : String = null ;
  var capture:boolean = false;

  // abstract methods

  /** Tests if an XML element contains text.
  * @return true if element named <code>localName</code> contains text.
  */
   def nodeContainsText( localName:String ):boolean ; // abstract
  
  /** creates an new non-text(tree) node.
  * @param elemName
  * @param attribs
  * @param chIter
  * @return a new XML element.
  */
  def createNode(elemName:String , 
                 attribs:HashMap[String,String] , 
                 chIter:List[Node] ):Node; //abstract 
  
  /** creates a Text node.
   * @param text
   * @return a new Text node.
   */
  def createText( text:String ):Text; // abstract
  
  //
  // ContentHandler methods
  //

  /** Set document locator.
   * @param locator
  def setDocumentLocator( locator:Locator ):Unit = {} 
   */
      
  /** Start document.
  * @throws org.xml.sax.SAXException if ..
  def startDocument():Unit = {} 
  */

  val normalizeWhitespace = false;

  /** Characters.
  * @param ch
  * @param offset
  * @param length
  */
   override def characters( ch:Array[char] , offset:int , length:int ):Unit = {

        if (capture) {
          if( normalizeWhitespace ) { // normalizing whitespace is not compliant, but useful */
	    var i:int = offset;
            var ws:boolean = false;
	    while (i < offset + length) {
              if ( Character.isWhitespace( ch(i) ) ) {
                if (!ws) {
                  buffer.append(' ');
                  ws = true;
                }
              } else {
                buffer.append(ch(i));
                ws = false;
              }
	      i = i+1;
            }
          } else { // compliant:report every character

              buffer.append( ch, offset, length );

          }
	}
        //System.err.println( "after \""+buffer+"\"" );
        //System.err.println();
          
    }

    /** Ignorable whitespace.
    def ignorableWhitespace(ch:Array[char] , offset:int , length:int ):Unit = {}
     */

    /** End document.
     */
    //def endDocument():Unit = {}

    //var elemCount = 0; //STATISTICS

    /* ContentHandler methods */

    /* Start prefix mapping - use default impl. 
     def startPrefixMapping( prefix:String , uri:String ):Unit = {} 
     */

    /* Start element. */
    override def startElement(uri:String, 
                              localName:String, 
                              qname:String , 
                              attributes:Attributes ):Unit = {
        /*elemCount = elemCount + 1; STATISTICS */
        captureText();

        tagStack.push(curTag);
        curTag = localName ;

        capture = nodeContainsText(localName) ;

        hStack.push( null );
        var map:HashMap[String,String] = null:HashMap[String,String];
        
        if (attributes == null) {
              //fOut.println("null");
        }
        else {
              map = new HashMap[String,String];

	      for( val i <- List.range( 0, attributes.getLength() )) {
                val attrLocalName = attributes.getLocalName(i);
                //String attrQName = attributes.getQName(i);
                //String attrURI = attributes.getURI(i);
                val attrType = attributes.getType(i);
                val attrValue = attributes.getValue(i);
                if( attrType.equals("CDATA") ) { 
		  map.update( attrLocalName, attrValue ); 
		}
              }
	}
			     
        val _ = attribStack.push( map );
        
    } // startElement(String,String,String,Attributes)
      

    /** this way to deal with whitespace is quite nonstandard, but useful
     *  
     */
    def captureText():Unit = {
        if (capture == true) {
	    val text = buffer.toString();
	    if(( text.length() > 0 )&&( !( text.equals(" ")))) {
		val _ = hStack.push( createText( text ) );
	    }
        }
        buffer.setLength(0);
    }

    /** End element.
     * @param uri
     * @param localName
     * @param qname
     * @throws org.xml.sax.SAXException if ..
     */
    override def endElement(uri:String , localName:String , qname:String ):Unit
            /*throws SAXException*/ = {

        captureText();

        val attribMap = attribStack.top; attribStack.pop;

        // reverse order to get it right
        var v:List[Node] = Nil; 
        var child:Node = hStack.top; hStack.pop;
        while( child != null ) {
            v = child::v;
            child = hStack.top; hStack.pop;
        }

        // create element
        rootElem = createNode( localName, attribMap, v );
        hStack.push(rootElem);

        // set
        curTag = tagStack.top; tagStack.pop; 
            
        if (curTag != null) // root level
            capture = nodeContainsText(curTag);
        else
            capture = false;

    } // endElement(String,String,String)
      
    /** End prefix mapping.
     def endPrefixMapping(prefix:String ):Unit  = {}
     */

    /** Skipped entity.
     def skippedEntity(name:String ):Unit /*throws SAXException*/ = {}
     */

    //
    // ErrorHandler methods
    //

    /** Warning.*/
    override def warning(ex:SAXParseException ):Unit  = {
	// ignore warning, crimson warns even for entity resolution!
        //printError("Warning", ex);
    } 
    /** Error.     */
    override def error(ex:SAXParseException ):Unit = {
        printError("Error", ex);
    } 

    /** Fatal error.*/
    override def  fatalError(ex:SAXParseException ):Unit = {
        printError("Fatal Error", ex);
    } 

    //
    // Protected methods
    //

    /** Prints the error message */
    protected def printError( errtype:String , ex:SAXParseException ):Unit = {

        System.err.print("[");
        System.err.print(errtype);
        System.err.print("] ");

        var systemId = ex.getSystemId();
        if (systemId != null) {
            val index = systemId.lastIndexOf('/');
            if (index != -1)
                systemId = systemId.substring(index + 1);
            //System.err.print(systemId);
        }

        System.err.print(':');
        System.err.print(ex.getLineNumber());
        System.err.print(':');
        System.err.print(ex.getColumnNumber());
        System.err.print(": ");
        System.err.print(ex.getMessage());
        System.err.println();
        System.err.flush();

    } 

    var rootElem : Node = null:Node;

    //FactoryAdapter
    // MAIN
    //

    def  loadXML( url:URL ):Node = loadXML( url.getFile() );

    /** load XML document
     * @param fileName
     * @return a new XML document object
     */
    def  loadXML( fileName:String ):Node = {

        // variables
        //PrintWriter out = new PrintWriter(System.out);
        var parser:XMLReader  = null;

        // use default parser
        // create parser
        try {
             parser = XMLReaderFactory.createXMLReader(DEFAULT_PARSER_NAME);
        } catch {
	  case ( e:Exception ) => {
            System.err.println("error: Unable to instantiate parser (" +
                               DEFAULT_PARSER_NAME + ")");
            System.exit(-1);
          }
	}

        // set parser features
        try {
            parser.setFeature(NAMESPACES_FEATURE_ID, true);
        } catch { 
	  case ( e:SAXException ) => {
            System.err.println("warning: Parser does not support feature (" + 
                               NAMESPACES_FEATURE_ID + ")");
          }
	}
        // set handlers
        parser.setErrorHandler( this /*FA_ErrorHandler*/ );
                
        if (parser.isInstanceOf[ XMLReader ]) {
            parser.setContentHandler(this);
        }
        else {
            System.err.println("wrong parser");
            System.exit(-1);
        }
 
        // parse file
        try {
            //System.err.println("[parsing \"" + fileName + "\"]");
            parser.parse( fileName );
        } catch {
          case ( e:SAXParseException ) => {
            // ignore
          }
          case ( e:Exception ) => {
            System.err.println("error: Parse error occurred - " + e.getMessage());
            if (e.isInstanceOf[ SAXException ]) {
              (e.asInstanceOf[ SAXException ])
	       .getException()
	       .printStackTrace( System.err );
            } else {
               e.printStackTrace(System.err);
	    }
          }
	} // catch
      //System.err.println("[FactoryAdapter: total #elements = "+elemCount+"]");
      rootElem
                
    } // loadXML
      

}
