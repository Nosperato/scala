/*                     __                                               *\
**     ________ ___   / /  ___     Scala API                            **
**    / __/ __// _ | / /  / _ |    (c) 2003, LAMP/EPFL                  **
**  __\ \/ /__/ __ |/ /__/ __ |                                         **
** /____/\___/_/ |_/____/_/ | |                                         **
**                          |/                                          **
\*                                                                      */

// $Id$

package scala.runtime.types;

import scala.runtime.RunTime;
import scala.Type;
import scala.Array;

public class TypeFloat extends ValueType {
    private final scala.Float ZERO = RunTime.box_fvalue(0.0f);
    public Array newArray(int size) {
        return RunTime.box_farray(new float[size]);
    }
    public Object checkCastability(Object o) {
        if (! (o == null || o instanceof scala.Float))
            throw new ClassCastException(); // TODO error message
        return o;
    }
    public Object defaultValue() { return ZERO; }
    public String toString() { return "scala.Float"; }
    public int hashCode() { return 0x22222222; }
};

