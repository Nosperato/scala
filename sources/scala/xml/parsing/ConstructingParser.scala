package scala.xml.parsing ;

object ConstructingParser {
  def fromSource(inp: scala.io.Source): ConstructingParser = 
    fromSource(new ConstructingHandler(), inp);
  
  def fromSource(theHandle: ConstructingHandler, inp: scala.io.Source): ConstructingParser  = {


    val p = new ConstructingParser() {
      val input = inp;
      override val handle = theHandle;
      def nextch = 
        if(input.hasNext) {
          ch = input.next; 
          pos = input.pos; 
        } else
          eof = true;
      
      override val preserveWS = true;

      /** report a syntax error */
      def reportSyntaxError(str: String): Unit = {
        //Console.println(inp.descr+":"+scala.io.Position.toString(pos)+":"+str);
        inp.reportError(pos, str)
      }

    };
    p.nextch;
    p
  }
}

/** an xml parser. parses XML and invokes callback methods of a MarkupHandler
 */
abstract class ConstructingParser extends MarkupParser {

  val handle = new ConstructingHandler();

  /** this method assign the next character to ch and advances in input */
  def nextch: Unit;
  
}
