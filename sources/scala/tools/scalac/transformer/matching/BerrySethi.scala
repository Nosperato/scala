/** $Id */
import scalac.CompilationUnit ;
import scalac.ApplicationError ;
import scalac.ast.Tree ;
import scalac.ast.TreeInfo ;
import scalac.util.Name ;
import Tree._ ;

import java.util._ ;

//import scala.compiler.printer.XMLAutomPrinter ;

/** a Berry-Sethi style construction for nfas. 
 *  this class plays is the "Builder" for the "Director" class WordRecognizer.
 */
package scala.tools.scalac.transformer.matching {

class BerrySethi(val unit: CompilationUnit ) { 

  def isStar(n: Name): boolean = {
    TreeInfo.isNameOfStarPattern( n );
  }
    /*

            String s = n.toString();
            return (s.indexOf("$") != -1)
                  &&(!s.startsWith("nest"));
      }
    */

  var labels: HashSet = _;
  
  var pos: int = _;
  // maps a literal pattern to an Integer ( the position )
  // is not *really* needed (postfix order determines position!)
  var posMap: HashMap = _;    // pos:    Patterns   -> Positions
  // don't let this fool you, only labelAt is a real, surjective mapping
  var labelAt: HashMap= _; // chi:    Positions -> Obj
  
  var globalFirst: TreeSet= _;
  
  // results which hold all info for the NondetWordAutomaton
  
  var follow: HashMap= _;    // follow: Positions -> Set[Positions]
  
  
  // Unit test ?
  def nullable(  pat: Tree ): boolean = {
    //System.out.print("<nullable>");
    //DEBUG.print( pat );
    //System.out.println("</nullable>");
    pat.match {
      case Apply(_, _) =>
        return false;

      case Sequence( trees ) =>
        return (trees.length == 0) || nullable( trees );
      //case Subsequence( Tree[] trees ):
      //return 
      case Bind(n, t) =>
        /* 
         if( isStar( n ) ) // generated for star/plus(?)
         return true;
         */
        return nullable( t );
      case Alternative( choices) =>
        var result = false;
      var i = 0; 
      while(i < choices.length && !result) {
        result = result || nullable( choices( i ) );
        i = i + 1 
      }
      return result;
      case _ =>
        return false;
    }
  }
  
  
  /** returns true if a Sequence pattern matches the empty sequence
   *  @param pat the sequence pattern.
   */
  def nullableSequence(pat: Tree): boolean = {
    pat.match {
      case Sequence( pats ) =>
        return nullable( pats );
    }
    return false;
  }

  /** returns true if a sequence of patterns (usually children of a 
   *  sequence or subsequence node) is nullable.
   *  @param pats the sequence of patterns
   */
  def nullable( pats:Array[Tree] ): boolean = {
    var result = true;
    var i = 0; 
    while(i < pats.length && result){
      result = result && nullable( pats( i ) );
      i = i + 1
    }
    return result;
  }
  
  /** computes first( alpha ) where alpha is a word regexp
   */
  
  def compFirst( pat:Tree  ): TreeSet = {
    //System.out.print("<compFirst>");
    //DEBUG.print( pat );
            //System.out.println("</compFirst>");
    pat.match {
      case Sequence( trees ) =>
        return compFirst( trees );
      case Typed(_,_) |  Select(_,_) | Apply(_, _) =>
        val tmp = new TreeSet();
        tmp.add( posMap.get( pat ).asInstanceOf[Integer]); // singleton set
        return tmp;

      case Literal( _ ) =>
        val tmp = new TreeSet();
        tmp.add( posMap.get( pat ).asInstanceOf[Integer]); // singleton set
        return tmp;
		  //case Subsequence( Tree[] trees ) =>
                  //return compFirst( trees );
      case Alternative( trees ) =>
        val tmp = new TreeSet();
        var i = 0;
        while(i < trees.length) {
          tmp.addAll( compFirst( trees( i ) ));
          i = i + 1
        }
        return tmp;

      case Bind( _, tree ) =>
        return compFirst( tree );
      
      case Ident(  name ) =>
        //if( name != Name.fromString("_") )
        //    throw new ApplicationError("unexpected pattern");
        val tmp = new TreeSet();
        tmp.add( posMap.get( pat ).asInstanceOf[Integer]); // singleton set
        return tmp;

      case _ =>
        throw new ApplicationError("unexpected pattern");
    }
  }     
  
  
  
  /** computes last( alpha ) where alpha is a word regexp
   */
  def compLast( pat:Tree  ): TreeSet = {
    //System.out.print("<last>");
    //DEBUG.print( pat );
    //System.out.println("</compLast>");
    pat.match {
      case Sequence( _ ) |  Apply(_, _) =>
        val tmp = new TreeSet();
        tmp.add(posMap.get( pat ).asInstanceOf[Integer]); // singleton set
        return tmp;                  

      case Literal( _ ) =>
        val tmp = new TreeSet();
        tmp.add( posMap.get( pat ).asInstanceOf[Integer]); // singleton set
        return tmp;

      case Alternative( trees ) =>
        val tmp = new TreeSet();
        var i = 0; 
        while(i < trees.length) {
          tmp.addAll( compLast( trees ));
          i = i + 1
        }
        return tmp;

      case Bind( _, tree ) =>
        return compLast( tree );

      case _ =>
        throw new ApplicationError("unexpected pattern");
    }
  }     
  

  /** computes first(w) where w=alpha_1...alpha_n  are successors of a 
   *  sequence node
   */      
  def compFirst( pats:Array[Tree]  ): TreeSet = {
    if( pats.length == 0 )
      return new TreeSet();
    
    var i = 0;
    var tmp = pats( i );
    val result = compFirst( tmp );
    i = i + 1;
    while( nullable(tmp) && (i < pats.length )) {
      tmp = pats( i );
      result.addAll( compFirst( tmp ));
      i = i + 1;
    }
    return result;
  }
  
  // Unit test ?
  
  /** computes last(w) where w are successors of a sequence node
   */
  def compLast(pats: Array[Tree]): TreeSet = {
    /*
     System.out.print("<last>");
     for( int k = 0; k<pats.length; k++) {
     DEBUG.print( pats[k] );
     System.out.print("  ");
     }
     System.out.println();
     */
    if( pats.length == 0 )
      return new TreeSet();
    
    var i = pats.length - 1;
    var tmp = pats( i );
    val result = compLast( tmp );
    i = i - 1;
    while( nullable(tmp) && (i >= 0 )) {
      tmp = pats( i );
      result.addAll( compLast( tmp ));
      i = i + 1;
    }
    return result;
  }
  
  // starts from the right-to-left
  // precondition: pos is final
  //               pats are successor patterns of a Sequence node
  def compFollow(pats: Array[Tree]): TreeSet = {
    var first:TreeSet  = null;
    this.recVars = new HashMap();
    var fol = new TreeSet();
    if( pats.length > 0 ) {//non-empty expr
      var i = pats.length;
      fol.add( new Integer( pos )); // don't modify pos !
      do { 
        i = i - 1;
        first = compFollow1( fol, pats( i ) );
        if( nullable( pats( i ) ))
          fol.addAll( first );
        else
          fol = first;
        //System.out.println("in compFollow: first"+first);
        //System.out.println("in compFollow: fol"+fol);
        
      } while( i > 0 );
    }
    if( null == first ) 
      first = new TreeSet();
    else {
      first = fol;
    }
    this.follow.put(new Integer( 0 ), first);
    return first;
  }
      
  var recVars: HashMap = _;

  /** returns the first set of an expression, setting the follow set along 
   *  the way
   */
  def compFollow1( fol1:TreeSet , pat:Tree  ): TreeSet = {
    var fol = fol1;
    //System.out.println("compFollow1("+fol+","+pat+")");
    pat.match {
      case Sequence( trees ) =>
        var first: TreeSet = null;
        var i = trees.length;
        if( i > 0 ) { // is nonempty
          do {
            i = i - 1;
            first = compFollow1(fol, trees( i ));
            if( nullable( trees( i ) ))
              fol.addAll( first );
            else
              fol = first;
          } while( i > 0 ) ;
        }
      if( null == first ) first = new TreeSet();
      return first;
      
      case Alternative( choices ) =>
        val first = new TreeSet();
      var  i = choices.length - 1;
      while(i >= 0) {
        first.addAll( compFollow1( fol, choices( i ) ));
        i = i - 1;
      }
      return first;
      
      case Bind( n, t ) =>  // == can also be star

        val first = compFirst( t );
                  //System.out.print("BIND" + first);
      recVars.put( pat.symbol(), first );
      
      // if( appearsRightmost( n, t ))
      // follow = oldfollw + ownfirst
      if( isStar( n ) )
        fol.addAll( first ); // an iterated pattern
      
      // continue to compute follow sets with adjusted fol
      return compFollow1( fol, t );
      
      case Ident(  n ) =>
        if ((pat.symbol() != null )
            && pat.symbol().isPrimaryConstructor()) {
              // same as Apply
              val pos = this.posMap.get( pat ).asInstanceOf[Integer];
              val tset = fol.clone().asInstanceOf[TreeSet];
              this.follow.put( pos, tset );
              val first = new TreeSet();
              first.add( pos );
              return first;
            }
      
      if ( recVars.keySet().contains( pat.symbol() )) { // grammar
        val first = recVars.get( pat.symbol() ).asInstanceOf[TreeSet];
        val follow = fol.clone().asInstanceOf[TreeSet];
        first.addAll( follow );
        //recVars.put//this.follow.put( pat.symbol(), follow );
        return first;
      }

      // --- --- only happens when called from BindingBerrySethi 
      // [... x ...] changed to [... x@_ ...]
      
      // non-generated, non-recursive variable should not appear,
      // so this is a wildcard pattern _
      
      val pos = this.posMap.get( pat ).asInstanceOf[Integer];
      val tset = fol.clone().asInstanceOf[TreeSet];
      this.follow.put( pos, tset );
      val first = new TreeSet();
      first.add( pos );
      //System.out.println("Ident("+n+",...) first:"+first);
      //System.out.println("Ident("+n+",...) follow:"+tset);
      return first;

      case Apply(_, _) | Literal( _ ) | Typed(_,_) | Select(_,_) =>
        val pos = this.posMap.get( pat ).asInstanceOf[Integer];
        val tset = fol.clone().asInstanceOf[TreeSet];
        this.follow.put( pos, tset );
        val first = new TreeSet();
        first.add( pos );
        return first;

      case _ =>
        throw new ApplicationError("unexpected pattern: "+pat.getClass());
    }
  }
  
  /** called at the leaves of the regexp
   */
  def seenLabel( pat:Tree , i:Integer , label:Label  ): Unit = {
    this.posMap.put( pat, i );
    this.labelAt.put( i, label );
    if( label != DefaultLabel() ) {
      /*
       if( this.labels.contains( label ) ) {
       switch(label) {
       case TreeLabel(Apply(_, Tree[] args)) =>
       if( args.length > 0 ) {
       unit.warning(pat.pos, "if this pattern in nondeterminism, it will not compile correctly");
       }
       }
       }
       */
      this.labels.add( label );
    }
    
  }
  
  /** overriden in BindingBerrySethi
   */
  def seenLabel( pat:Tree ,  label:Label ): Unit = {
    seenLabel( pat, new Integer( {pos = pos + 1; pos} ), label );
  }
  
  /** returns "Sethi-length" of a pattern, creating the set of position
   *  along the way
   */
  
  var activeBinders:Vector = _;

  // todo: replace global variable pos with acc
  def traverse( pat:Tree  ): Unit = {
    pat.match {
      
      // (is tree automaton stuff, more than Berry-Sethi)
      case Apply( _, _ ) | Typed( _, _ )| Select( _, _ ) =>
        val label = new TreeLabel( pat );
        seenLabel( pat, label ) ;
        return ; 
                  
      case p @ Literal( _ ) =>
        val label = new SimpleLabel( p );
        seenLabel( pat, label ) ;
      
        return ;
                  
      case Sequence( trees ) =>
        var i = 0; 
        while(i < trees.length) {
          traverse( trees( i ) );
          i = i + 1
        }                  
      return ;

      case Alternative( trees ) =>
        var i = 0; 
        while(i < trees.length) {
          traverse( trees( i ) );
          i = i + 1
        }                  
        return ;

      case Bind( name,  body) => 
        recVars.put( pat.symbol(), java.lang.Boolean.TRUE );
        if( !isStar( name ) ) {
          activeBinders.add( pat.symbol() );
          traverse( body );
          activeBinders.remove( pat.symbol() );
        }
        else                               
          traverse( body );
      return ;
      
      case Ident(name) =>
        if ((pat.symbol() != null )
            && pat.symbol().isPrimaryConstructor()) {
              // same as Apply
              val label = new TreeLabel( pat );
              seenLabel( pat, label ) ;
              
              return ; 
            }
      
      
      if( null != recVars.get( pat.symbol() ) ) {
        return ; 
      }
      // _ and variable x ( == x @ _ )
      val label = DefaultLabel();
      seenLabel( pat, label );
      
      return ;
      
    }
  }


  var finals: TreeMap  = _;      // final states
  
  //TreeSet initialsRev;      // final states
  
  var deltaq:Array[HashMap] = _;    // delta
  
  
  
  var  defaultq: Array[Vector] = _;  // default transitions
  
  
  //HashMap deltaqRev[];    // delta of Rev
  //Vector  defaultqRev[];  // default transitions of Rev
  

   def makeTransition(srcI:Integer, destI:Integer, label: Label): Unit = {
    var src  = srcI.intValue() ;
    var dest = destI.intValue() ;
    var  arrows: Vector = _; //, revArrows;
    //Label revLabel = new Pair( srcI, label );
    label.match {
      case DefaultLabel() =>
        arrows = defaultq( src );
      //revArrows = defaultqRev[ dest ];
      case _ =>
        arrows = deltaq( src ).get( label ).asInstanceOf[Vector];
        if( arrows == null )
          deltaq( src ).put( label, 
                            {arrows = new Vector(); arrows} );
      /*
       revArrows = (Vector) deltaqRev[ dest ].get( revLabel );
       if( revArrows == null )
       deltaqRev[ dest ].put( revLabel, 
       revArrows = new Vector() );
       */
    }
    arrows.add( destI );
    //revArrows.add( srcI );                              
  }
  
  
  var         initials: TreeSet = _;
  //NondetWordAutom revNfa ;
  
  def initialize( subexpr:Array[Tree]  ): Unit = {
    this.posMap = new HashMap();
    this.labelAt = new HashMap();
    
    
    this.follow = new HashMap();
    this.labels = new HashSet();
    this.recVars = new HashMap();
    this.pos = 0;
    // determine "Sethi-length" of the regexp
    activeBinders = new Vector();
    var  i = 0;
    while(i < subexpr.length ) {
      traverse( subexpr( i ));
      //assert ( activeBinders.isEmpty() )
      i = i + 1;
    }
    
    this.initials = new TreeSet();
    initials.add( new Integer( 0 ));
    
  }
  
   def initializeAutom(): Unit = {
    
    finals   = new TreeMap();        // final states
    deltaq   = new Array[HashMap]( pos );   // delta
    defaultq = new Array[Vector]( pos );    // default transitions
  
    var  j = 0;
    while(j < pos) {
      deltaq( j ) = new HashMap();
      defaultq( j ) = new Vector();
      j = j + 1;
    }
  }
  
  def collectTransitions(): Unit = {                 // make transitions
    var  j = 0;
    while(j < pos) {                  
      val q = new Integer( j );
                  
      //System.out.print( "--q="+q );
      //System.out.println(" labelAt:"+labelAt.get( q ));
      
      val fol = this.follow.get( q ).asInstanceOf[TreeSet];
      //assert fol != null;
      val it = fol.iterator(); 
      while(it.hasNext()) {
        val p = it.next().asInstanceOf[Integer];
        //System.out.println( "--  -- p="+p );
        if( p.intValue() == pos ) {
          finals.put( q, finalTag );
        } else {
          makeTransition( new Integer(j), p, 
                         labelAt.get( p ).asInstanceOf[Label]);
        }
      }
      j = j + 1
    }
  }    
  
  var finalTag: Integer = _;
  
  def automatonFrom(pat: Tree , finalTag: Integer):  NondetWordAutom = {
    
    this.finalTag = finalTag;
    
    //System.out.println( "enter automatonFrom("+pat+","+finalTag+")"); // UNIT TEST
    //System.out.println( pat );
    //System.out.println( nullableSequence( pat )); // UNIT TEST
    pat.match {
      case Sequence( subexpr ) =>
        initialize( subexpr );
      
      
      // (1) compute first
      
      //globalFirst = compFirst( subexpr );
      //System.out.println(globalFirst);
      
      // (2) compute follow;
     pos = pos + 1;
      //Set ignore = compFollow( subexpr );
      //System.out.println(ignore);
      //System.exit(0);
      //assert (ignore.equals( globalFirst ));
      
      globalFirst = compFollow( subexpr );
      
      //System.out.print("someFirst:");debugPrint(someFirst);
        
      // construct the automaton's explicit representation
      
      initializeAutom();
      
      
      // defaultqRev = new Vector[ pos ];  // default transitions
      
      collectTransitions();
        
      if( nullable( subexpr )) // initial state is final
	finals.put( new Integer(0), finalTag ); 
      
      //TreeSet initials = new TreeSet();
      //initials.add( new Integer( 0 ) );
      
      val result = 
        new NondetWordAutom(pos, // = nstates
                            labels,
                            initials,
                            finals,
                            deltaq,
                            defaultq,
                            null/*(Object) qbinders*/);
      
      /*
       System.out.println("inBerrySethi");
       XMLAutomPrinter pr = new XMLAutomPrinter( System.out );
       pr.begin();
       pr.print( result );
       pr.print( revNfa );
       pr.end();
       System.out.println("initialsRev = "+initialsRev);
       System.out.println("outBerrySethi");
       */
      //System.exit(0);
      //result.print();
      return result;                  
    }
    
    throw new ApplicationError("expected a sequence pattern");
  }
  
  def print1(): Unit = {
    Console.println("after sethi-style processing");
    Console.println("#positions:" + pos);
    Console.println("posMap:");
    
    var it = this.posMap.keySet().iterator();
    while(it.hasNext()) {
      val t = it.next().asInstanceOf[Tree];
      t.match {
        case Literal( _ ) =>
          Console.print( "(" + t.toString() + " -> ");
          val s2 = (posMap.get(t).asInstanceOf[Integer]).toString();
          Console.print( s2 +") ");
        }
      }
      Console.println("\nfollow: ");
    var  j = 1; 
    while(j < pos ) {
      val fol = this.follow.get(new Integer(j)).asInstanceOf[TreeSet];
      Console.print("("+j+" -> "+fol.toString()+") ");
      //debugPrint( fol );
      Console.println;
      j = j + 1;
    }
    
  }
}
}
