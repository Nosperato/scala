import java.io.StringReader;
import org.xml.sax.InputSource;
import scala.xml.nobinding.XML;
import scala.testing.UnitTest._ ;
import scala.xml.{Node,NodeSeq,Elem,Text};

object Test with Application {
/*
  def eq( a:Seq[Node], b:Seq[Node] ):boolean = {
    (a.length == b.length) && eq(a.elements,b.elements)
  }
  def eq( ita:Iterator[Node], itb:Iterator[Node] ) = {
    var res = true;
    while( ita.hasNext && itb.hasNext && res ) {
      res = (ita.next == itb.next);
    };
    !ita.hasNext && !itb.hasNext && res
  }
  */
  val xmlFile1 = "<hello><world/></hello>";
  val isrc1 = new InputSource( new StringReader( xmlFile1 ) );
  val parsedxml1 = XML.load( isrc1 );
  val isrc11 = new InputSource( new StringReader( xmlFile1 ) );
  val parsedxml11 = XML.load( isrc11 );

  val c = new Node {
    def label = "hello";
    def child = List(Elem("world"));
    def attribute = List();
  };

  assertSimilar( List( 3 ), List( 3 ));

  Console.println("equality");
  assertEquals( c, parsedxml11 );
  assertEquals( parsedxml1, parsedxml11 );
  assertSimilar( List(parsedxml1), List(parsedxml11));
  assertSimilar( Iterator.fromArray(Predef.Array(parsedxml1)).toSeq(1), List(parsedxml11));

  val x2 = "<book><author>Peter Buneman</author><author>Dan Suciu</author><title>Data on ze web</title></book>";

  val i = new InputSource( new StringReader( x2 ));
  val x2p = XML.load( i );

  assertSimilar(x2p, Elem("book",
                          Elem("author",Text("Peter Buneman")), 
                          Elem("author",Text("Dan Suciu")), 
                          Elem("title",Text("Data on ze web"))));

  val xmlFile2 = "<bib><book><author>Peter Buneman</author><author>Dan Suciu</author><title>Data on ze web</title></book><book><author>John Mitchell</author><title>Foundations of Programming Languages</title></book></bib>";
  val isrc2 = new InputSource( new StringReader( xmlFile2 ) );
  val parsedxml2 = XML.load( isrc2 );

  // xmlFile2/book -> book,book
  Console.println("xpath \\");


  //assertSimilar( parsedxml1 \ '_ ,    List( 'world() ) );
  assertSimilar( parsedxml1 \ '_ ,    List( Elem("world") ) );

  //assertSimilar( parsedxml1 \ 'world, List( 'world() ) );
  assertSimilar( parsedxml1 \ 'world, List( Elem("world") ) );

/*
  Console.println( parsedxml2 \ '_ );
  Console.println( (parsedxml2 \ '_).elements);
  for( val i <- (parsedxml2 \ '_).elements) {
    Console.println( i );
    };
  */
  assertSimilar( 
      parsedxml2 \ '_ , 
      
      List(
        Elem("book", 
             Elem("author",Text("Peter Buneman")), 
             Elem("author",Text("Dan Suciu")), 
             Elem("title",Text("Data on ze web"))),
        Elem("book",
             Elem("author",Text("John Mitchell")),
             Elem("title",Text("Foundations of Programming Languages"))))
  );
  assertEquals( (parsedxml2 \ 'author).length, 0 );

  assertSimilar( 
      parsedxml2 \ 'book, 
      
      List(
        Elem("book",
             Elem("author",Text("Peter Buneman")), 
             Elem("author",Text("Dan Suciu")), 
             Elem("title",Text("Data on ze web"))),
        Elem("book",
             Elem("author",Text("John Mitchell")),
             Elem("title",Text("Foundations of Programming Languages")))
      )
  );

  assertSimilar( 

      parsedxml2 \ '_ \ '_,

    List(
      Elem("author",Text("Peter Buneman")), 
      Elem("author",Text("Dan Suciu")), 
      Elem("title",Text("Data on ze web")),
      Elem("author",Text("John Mitchell")),
      Elem("title",Text("Foundations of Programming Languages"))
    )
  );

  assertSimilar( 

      parsedxml2 \ '_ \ 'author,

      List(
        Elem("author",Text("Peter Buneman")), 
        Elem("author",Text("Dan Suciu")), 
        Elem("author",Text("John Mitchell"))
      )
    
  );

  assertSimilar( (parsedxml2 \ '_ \ '_ \ 'author), List() );

  Console.println("xpath \\\\ DESCENDANTS");

  assertSimilar( 

      parsedxml2 \\ 'author,

      List(
        Elem("author",Text("Peter Buneman")), 
        Elem("author",Text("Dan Suciu")), 
        Elem("author",Text("John Mitchell"))
      )

 );

  assertSimilar( 

      new NodeSeq(List( parsedxml2 ))\\ '_,

      List(
        Elem("bib",
             Elem("book",
                  Elem("author",Text("Peter Buneman")), 
                  Elem("author",Text("Dan Suciu")), 
                  Elem("title",Text("Data on ze web"))),
             Elem("book",
                  Elem("author",Text("John Mitchell")),
                  Elem("title",Text("Foundations of Programming Languages")))),
        Elem("book",
             Elem("author",Text("Peter Buneman")), 
             Elem("author",Text("Dan Suciu")), 
             Elem("title",Text("Data on ze web"))),
        Elem("author",Text("Peter Buneman")),
        Text("Peter Buneman"),
        Elem("author",Text("Dan Suciu")),
        Text("Dan Suciu"),
        Elem("title",Text("Data on ze web")),
        Text("Data on ze web"),
        Elem("book",
             Elem("author",Text("John Mitchell")),
             Elem("title",Text("Foundations of Programming Languages"))),
        Elem("author",Text("John Mitchell")),
        Text("John Mitchell"),
        Elem("title",Text("Foundations of Programming Languages")),
        Text("Foundations of Programming Languages")
      )
  );


  assertSimilar( 

      parsedxml2 \\ 'title,

      List(
        Elem("title",Text("Data on ze web")),
        Elem("title",Text("Foundations of Programming Languages")))
);
}
