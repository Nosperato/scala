/*                     __                                               *\
**     ________ ___   / /  ___     Scala API                            **
**    / __/ __// _ | / /  / _ |    (c) 2003, LAMP/EPFL                  **
**  __\ \/ /__/ __ |/ /__/ __ |                                         **
** /____/\___/_/ |_/____/_/ | |                                         **
**                          |/                                          **
\*                                                                      */

// $Id$

package scala.runtime.types;

import scala.Type;
import scala.Array;

/**
 * Type for Java arrays of references.
 *
 * @author Michel Schinz
 * @version 1.0
 */

public class JavaRefArrayType extends Type {
    public final Type elemType;

    public JavaRefArrayType(Type elemType) {
        this.elemType = elemType;
    }

    public Array newArray(int size) {
        throw new Error();      // TODO
    }

    public Object defaultValue() {
        return null;
    }

    public boolean isInstance(Object o) {
        assert Statistics.incInstanceOf();
        if (o instanceof Object[]) {
            if (elemType instanceof ClassType) {
                ClassType elemTypeClass = (ClassType)elemType;
                if (o.getClass().getComponentType() == elemTypeClass.clazz) {
                    if (elemTypeClass.isTrivial)
                        return true;
                    else
                        throw new Error("not able to compute isInstance");
                } else
                    return false;
            } else
                throw new Error("not able to compute isInstance");
        } else
            return false;
    }

    public boolean isSameType(Type that) {
        return (that instanceof JavaRefArrayType)
            && (elemType.isSameType(((JavaRefArrayType)that).elemType));
    }

    public boolean isSubType(Type that) {
        return isSameType(that);
    }

    public String toString() {
        return elemType.toString() + "[]";
    }

    public int hashCode() {
        return elemType.hashCode() * 11;
    }
}
