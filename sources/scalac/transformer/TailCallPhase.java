/*     ____ ____  ____ ____  ______                                     *\
**    / __// __ \/ __// __ \/ ____/    SOcos COmpiles Scala             **
**  __\_ \/ /_/ / /__/ /_/ /\_ \       (c) 2002, LAMP/EPFL              **
** /_____/\____/\___/\____/____/                                        **
\*                                                                      */

// $Id$

package scalac.transformer;

import scalac.Global;
import scalac.Phase;
import scalac.PhaseDescriptor;
import scalac.Unit;

public class TailCallPhase extends Phase {

    //########################################################################
    // Public Constructors

    /** Initializes this instance. */
    public TailCallPhase(Global global, PhaseDescriptor descriptor) {
        super(global, descriptor);
    }

   //########################################################################
    // Public Methods

    /** Applies this phase to the given compilation units. */
     public void apply(Unit[] units) {
        for (int i = 0; i < units.length; i++)
            new TailCall(global, descriptor).apply(units[i]);
    }
   

}
