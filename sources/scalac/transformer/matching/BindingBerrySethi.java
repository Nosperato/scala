package scalac.transformer.matching ;

import scalac.Unit;
import scalac.Global ;
import scalac.ApplicationError ;
import scalac.ast.Tree ;
import scalac.util.Name ;
import scalac.util.Names ;
import Tree.* ;

import java.util.* ;

//import scala.compiler.printer.XMLTreePrinter ;
//import scala.compiler.printer.XMLAutomPrinter ;

/** a Berry-Sethi style construction for nfas. 
 *  this class plays is the "Builder" for the "Director" class 
 *  WordRecognizer.
 */

public class BindingBerrySethi extends BerrySethi {

    public BindingBerrySethi(Unit unit) {
        super(unit);
    }

      HashMap deltaqRev[];    // delta of Rev
      Vector  defaultqRev[];  // default transitions of Rev
      Vector qbinders[];  // transitions <-> variables

      protected void makeTransition( Integer srcI, Integer destI, Label label ) {
            int src  = srcI.intValue() ;
            int dest = destI.intValue() ;
            Vector arrows, revArrows;
            Label revLabel = new Label.Pair( srcI, label  );
            switch( label ) {
            case DefaultLabel:
                  arrows = defaultq[ src ];
                  revArrows = defaultqRev[ dest ];
                  break;
            default:                                    
                  arrows = (Vector) deltaq[ src ].get( label );
                  if( arrows == null )
                        deltaq[ src ].put( label, 
                                           arrows = new Vector() );
                  revArrows = (Vector) deltaqRev[ dest ].get( revLabel );
                  if( revArrows == null )
                        deltaqRev[ dest ].put( revLabel, 
                                               revArrows = new Vector() );
            }
            arrows.add( destI );
            revArrows.add( srcI );                              
      }
      
      NondetWordAutom revnfa ;

      void seenLabel( Tree pat, Label label ) {
            Integer i = new Integer( ++pos );
            seenLabel( pat, i, label );
            switch( pat ) {
            case Apply(_, _):
            case Literal( _ ):
            case Select(_, _):
            case Typed(_,_):
                  this.varAt.put( i, activeBinders.clone() ); // below @ ?
                  break;
            case Ident( Name name ):
                  assert ( pat.symbol() == Global.instance.definitions.PATTERN_WILDCARD )||( name.toString().indexOf("$") > -1 ) : "found variable label "+name;

                  Vector binders = (Vector) activeBinders.clone();
                  /*
                  if( pat.symbol() != Global.instance.definitions.PATTERN_WILDCARD) {
                        binders.add( pat.symbol() );
                  }
                  */
                  this.varAt.put( i, binders );
                  break;
            default:
                throw new ApplicationError("unexpected atom:"+pat.getClass());
            }
      }

      HashMap varAt;   // chi:    Positions -> Vars (Symbol)

      protected void initialize( Tree[] pats ) {
            this.varAt = new HashMap(); // Xperiment
            super.initialize( pats );
      }

      protected void initializeAutom() {
            super.initializeAutom();
            deltaqRev = new HashMap[ pos ];   // deltaRev
            defaultqRev = new Vector[ pos ];  // default transitions
            qbinders = new Vector[ pos ];    // transitions <-> variables
            
            for( int j = 0; j < pos; j++ ) {
                  deltaqRev[ j ] = new HashMap();
                  defaultqRev[ j ] = new Vector();
                  qbinders[ j ] = (Vector) varAt.get( new Integer( j ) );
            }
            varAt.clear(); // clean up
            
      }
      


      public NondetWordAutom automatonFrom( Tree pat, Integer finalTag ) {

            this.finalTag = finalTag ;
            //System.out.println( "enter automatonFrom("+ pat +")");
            switch( pat ) {
            case Sequence( Tree[] subexpr ):
                  
                  initialize( subexpr );
                  
                  // (1) compute first + follow;
                  ++pos;

                  globalFirst = compFollow( subexpr );
                  
                  

                  initializeAutom();   // explicit representation

                  collectTransitions();
                  
                  NondetWordAutom result = 
                        new NondetWordAutom(pos, // = nstates
                                            labels,
                                            initials,
                                            finals,
                                             deltaq,
                                             defaultq,
                                            (Object) qbinders);
                  
                  result.leftTrans = true;
                  
                  TreeSet revInitials = new TreeSet( finals.keySet() );
                  /*
                  pos++; // adding a state
                  HashSet deltaqRev2[]   = new HashSet[ deltaqRev.length + 1];
                  HashSet defaultqRev2[] = new HashSet[ deltaqRev.length + 1];
                  HashSet qbinders[]     = new HashSet[ deltaqRev.length + 1];
                  for(Iterator it = finals.keySet().iterator(); it.hasNext(); ) {
                        
                  }
                  */
                  TreeMap revFinals = new TreeMap();
                  for(Iterator it = initials.iterator(); it.hasNext(); ) {
                        revFinals.put( it.next(), finalTag);
                  }
                  revnfa = new NondetWordAutom(pos,
                                               labels,
                                               revInitials,
                                               revFinals,
					       deltaqRev,
					       defaultqRev,
                                               (Object) qbinders);

                  revnfa.rightTrans = true;

                  /*
                  System.out.println("inBerrySethi");
                  XMLAutomPrinter pr = new XMLAutomPrinter( System.out );
                  pr.begin();
                  pr.print( result );
                  pr.print( revnfa );
                  pr.end();
                  System.out.println("initialsRev = "+initialsRev);
                  System.out.println("outBerrySethi");
                  */
                  //System.exit(0);
                  return result;                  //print();
            }

            throw new ApplicationError("expected a sequence pattern");
      }
      
}
