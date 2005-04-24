import java.io.StringReader;
import org.xml.sax.InputSource;
import scala.xml._;
import scala.util.logging._;

import scala.testing.UnitTest._ ;

object Test with Application {
  val e:  scala.xml.MetaData         = Null; //Node.NoAttributes;
  val sc: scala.xml.NamespaceBinding = TopScope;

  val xmlFile1 = "<hello><world/></hello>";
  val isrc1 = new InputSource( new StringReader( xmlFile1 ) );
  val parsedxml1 = XML.load( isrc1 );
  val isrc11 = new InputSource( new StringReader( xmlFile1 ) );
  val parsedxml11 = XML.load( isrc11 );

  val c = new Node {
    def label = "hello";
    override def hashCode() = 
      Utility.hashCode(prefix, label, attributes.hashCode(), scope.hashCode(), child);
    def child = Elem(null, "world", e, sc);
    //def attributes = e;
  };

  assertSameElements( List( 3 ), List( 3 ));

  Console.println("equality");
  assertEquals( c, parsedxml11 );
  assertEquals( parsedxml1, parsedxml11 );
  assertSameElements( List(parsedxml1), List(parsedxml11));
  assertSameElements( Iterator.fromArray(Predef.Array(parsedxml1)).toList, List(parsedxml11));

  val x2 = "<book><author>Peter Buneman</author><author>Dan Suciu</author><title>Data on ze web</title></book>";

  val i = new InputSource( new StringReader( x2 ));
  val x2p = XML.load( i );

  assertEquals(x2p, Elem(null,"book",e,sc,
                          Elem(null,"author",e,sc,Text("Peter Buneman")), 
                          Elem(null,"author",e,sc,Text("Dan Suciu")), 
                          Elem(null,"title",e,sc,Text("Data on ze web"))));

  val xmlFile2 = "<bib><book><author>Peter Buneman</author><author>Dan Suciu</author><title>Data on ze web</title></book><book><author>John Mitchell</author><title>Foundations of Programming Languages</title></book></bib>";
  val isrc2 = new InputSource( new StringReader( xmlFile2 ) );
  val parsedxml2 = XML.load( isrc2 );

  // xmlFile2/book -> book,book
  Console.println("xpath \\");


  assertSameElements( parsedxml1 \ "_" ,    List( Elem(null,"world",e,sc) ) );

  assertSameElements( parsedxml1 \ "world", List( Elem(null,"world",e,sc) ) );

/*
  Console.println( parsedxml2 \ "_" );
  Console.println( (parsedxml2 \ "_" ).elements);
  for( val i <- (parsedxml2 \ "_" ).elements) {
    Console.println( i );
    };
  */

  assertSameElements( 
      parsedxml2 \ "_" , 
      
      List(
        Elem(null,"book", e,sc,
             Elem(null,"author",e,sc,Text("Peter Buneman")), 
             Elem(null,"author",e,sc,Text("Dan Suciu")), 
             Elem(null,"title",e,sc,Text("Data on ze web"))),
        Elem(null,"book",e,sc,
             Elem(null,"author",e,sc,Text("John Mitchell")),
             Elem(null,"title",e,sc,Text("Foundations of Programming Languages"))))
  );
  assertEquals( (parsedxml2 \ "author").length, 0 );

  assertSameElements( 
      parsedxml2 \ "book", 
      
      List(
        Elem(null,"book",e,sc,
             Elem(null,"author",e,sc,Text("Peter Buneman")), 
             Elem(null,"author",e,sc,Text("Dan Suciu")), 
             Elem(null,"title",e,sc,Text("Data on ze web"))),
        Elem(null,"book",e,sc,
             Elem(null,"author",e,sc,Text("John Mitchell")),
             Elem(null,"title",e,sc,Text("Foundations of Programming Languages")))
      )
  );

  assertSameElements( 

      parsedxml2 \ "_" \ "_",

    List(
      Elem(null,"author",e,sc,Text("Peter Buneman")), 
      Elem(null,"author",e,sc,Text("Dan Suciu")), 
      Elem(null,"title",e,sc,Text("Data on ze web")),
      Elem(null,"author",e,sc,Text("John Mitchell")),
      Elem(null,"title",e,sc,Text("Foundations of Programming Languages"))
    )
  );

  assertSameElements( 

      parsedxml2 \ "_" \ "author",

      List(
        Elem(null,"author",e,sc,Text("Peter Buneman")), 
        Elem(null,"author",e,sc,Text("Dan Suciu")), 
        Elem(null,"author",e,sc,Text("John Mitchell"))
      )
    
  );

  assertSameElements( (parsedxml2 \ "_" \ "_" \ "author"), List() );

  Console.println("xpath \\\\ DESCENDANTS");

  assertSameElements( 

      parsedxml2 \\ "author",

      List(
        Elem(null,"author",e,sc,Text("Peter Buneman")), 
        Elem(null,"author",e,sc,Text("Dan Suciu")), 
        Elem(null,"author",e,sc,Text("John Mitchell"))
      )

 );
  assertEquals( 

      (new NodeSeq { val theSeq = List( parsedxml2 ) }) \\ "_",

      List(
        Elem(null,"bib",e,sc,
             Elem(null,"book",e,sc,
                  Elem(null,"author",e,sc,Text("Peter Buneman")), 
                  Elem(null,"author",e,sc,Text("Dan Suciu")), 
                  Elem(null,"title",e,sc,Text("Data on ze web"))),
             Elem(null,"book",e,sc,
                  Elem(null,"author",e,sc,Text("John Mitchell")),
                  Elem(null,"title",e,sc,Text("Foundations of Programming Languages")))),
        Elem(null,"book",e,sc,
             Elem(null,"author",e,sc,Text("Peter Buneman")), 
             Elem(null,"author",e,sc,Text("Dan Suciu")), 
             Elem(null,"title",e,sc,Text("Data on ze web"))),
        Elem(null,"author",e,sc,Text("Peter Buneman")),
        Text("Peter Buneman"),
        Elem(null,"author",e,sc,Text("Dan Suciu")),
        Text("Dan Suciu"),
        Elem(null,"title",e,sc,Text("Data on ze web")),
        Text("Data on ze web"),
        Elem(null,"book",e,sc,
             Elem(null,"author",e,sc,Text("John Mitchell")),
             Elem(null,"title",e,sc,Text("Foundations of Programming Languages"))),
        Elem(null,"author",e,sc,Text("John Mitchell")),
        Text("John Mitchell"),
        Elem(null,"title",e,sc,Text("Foundations of Programming Languages")),
        Text("Foundations of Programming Languages")
      )
  );


  assertSameElements( 

      parsedxml2 \\ "title",

      List(
        Elem(null,"title",e,sc,Text("Data on ze web")),
        Elem(null,"title",e,sc,Text("Foundations of Programming Languages")))
  );
}
