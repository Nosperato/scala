/*     ____ ____  ____ ____  ______                                     *\
**    / __// __ \/ __// __ \/ ____/    SOcos COmpiles Scala             **
**  __\_ \/ /_/ / /__/ /_/ /\_ \       (c) 2002, LAMP/EPFL              **
** /_____/\____/\___/\____/____/                                        **
**                                                                      **
\*                                                                      */

// $Id$

package scalac.util;

import java.util.HashMap;

import java.lang.reflect.Modifier;
import java.lang.reflect.Method;
import java.lang.reflect.Field;

import scalac.Global;
import scalac.ApplicationError;
import scalac.symtab.Type;
import scalac.symtab.Symbol;
import scalac.symtab.Scope;

/**
 * Debugging class, used e.g. to obtain string representations of
 * compiler data structures that are not "pretty-printed" and thus
 * easier to relate to the source code.
 *
 * All methods are static to be easily useable in any context.
 *
 * @author Michel Schinz
 * @version 1.0
 */

public abstract class Debug {

    //########################################################################
    // Debug interface - abort

    public static Error abort() {
        return new ApplicationError();
    }

    public static Error abort(String message) {
        return new ApplicationError(message);
    }

    public static Error abort(Object object) {
        return new ApplicationError(object);
    }

    public static Error abort(Throwable cause) {
        return new ApplicationError(cause);
    }

    public static Error abort(String message, Object object) {
        return new ApplicationError(message, object);
    }

    public static Error abort(String message, Throwable cause) {
        return new ApplicationError(message, cause);
    }

    public static Error abort(Object object, Throwable cause) {
        return new ApplicationError(object, cause);
    }

    public static Error abort(String message, Object object, Throwable cause) {
        return new ApplicationError(message, object, cause);
    }

    //########################################################################
    // Debug interface - log

    public static boolean log(String message) {
        return Global.instance.log(message);
    }

    public static boolean log(String message, Object object) {
        return log(message + ": " + show(object));
    }

    //########################################################################
    // Debug interface - handlers

    public static final HashMap/*<Class,DebugHandler>*/ handlers;

    static {
        handlers = new HashMap();
        handlers.put(String.class, DebugToStringHandler.instance);
        handlers.put(Type.class, DebugType.instance);
        handlers.put(Symbol.class, DebugSymbol.instance);
        handlers.put(Scope.class, DebugScope.instance);
    }

    public static DebugHandler getHandler(Object that) {
        if (that == null) return DebugDefaultHandler.instance;
        return getHandler(that.getClass());
    }

    public static DebugHandler getHandler(Class clasz) {
        if (clasz == null) return DebugDefaultHandler.instance;
        Object handler = handlers.get(clasz);
        if (handler != null) return (DebugHandler)handler;
        return getHandler(clasz.getSuperclass());
    }

    //########################################################################
    // Debug interface - toString

    public static String toString(Object that) {
        return show(that);
    }

    //########################################################################
    // Debug interface - show

    public static String show(Object that) {
        return show(that, getHandler(that));
    }

    public static String show(Object that, DebugHandler handler) {
        StringBuffer buffer = new StringBuffer();
        handler.append0(buffer, that);
        return buffer.toString();
    }

    //########################################################################
    // Debug interface - append

    public static void append(StringBuffer buffer, Object that) {
        getHandler(that).append0(buffer, that);
    }

    public static void appendDefault(StringBuffer buffer, Object that) {
        if (that == null) { buffer.append("null"); return; }
        if (!that.getClass().isArray()) {
            appendObject(buffer, that);
        } else {
            appendArray(buffer, (Object[])that);
        }
    }

    public static void appendObject(StringBuffer buffer, Object that) {
        if (that == null) { buffer.append("null"); return; }
        buffer.append(getClassName(that));
        Class owner = null;
        if (hasToString(that.getClass())) {
            buffer.append('(');
            buffer.append(that);
            buffer.append(')');
        } else {
            int code = System.identityHashCode(that);
            buffer.append('@');
            buffer.append(Integer.toHexString(code));
        }
    }

    public static void appendArray(StringBuffer buffer, Object[] that) {
        if (that == null) { buffer.append("null"); return; }
        buffer.append('[');
        for (int i = 0; i < that.length; i++) {
            if (i > 0) buffer.append(',');
            append(buffer, that[i]);
        }
        buffer.append(']');
    }

    //########################################################################
    // Debug interface - utils

    public static final Class OBJECT_CLASS = Object.class;

    public static boolean hasToString(Class clasz) {
        try {
            Method toString = clasz.getMethod("toString", new Class[0]);
            return toString.getDeclaringClass() != OBJECT_CLASS;
        } catch (NoSuchMethodException excpetion) {
            return false;
        } catch (SecurityException excpetion) {
            return false;
        }
    }

    public static String getClassName(Object object) {
        if (object == null) return "null";
        Class clasz = object.getClass();
        String name = clasz.getName();
        if (!name.endsWith("$$Var")) return name;
        Class superclass = clasz.getSuperclass();
        Field[] fields = superclass.getDeclaredFields();
        for (int i = 0; i < fields.length; i++) {
            try {
                Field field = fields[i];
                if (field.getType() != clasz) continue;
                if (!Modifier.isStatic(field.getModifiers())) continue;
                Object value = field.get(null);
                if (value != object) continue;
                return name + "[" + field.getName() + "]";
            } catch (IllegalAccessException exception) {
                // continue
            }
        }
        return name;
    }

    //########################################################################
}

public interface DebugHandler {

    //########################################################################
    // DebugHandler interface

    public void append0(StringBuffer buffer, Object that);

    //########################################################################
}

public abstract class DebugAbstractHandler implements DebugHandler {

    //########################################################################
    // DebugAbstractHandler interface

    public String show(Object that) {
        return Debug.show(that, this);
    }

    //########################################################################
}

public class DebugDefaultHandler extends DebugAbstractHandler {

    //########################################################################
    // DebugDefaultHandler interface

    public static DebugDefaultHandler instance = new DebugDefaultHandler();

    //########################################################################
    // DebugHandler interface

    public void append0(StringBuffer buffer, Object that) {
        Debug.appendDefault(buffer, that);
    }

    //########################################################################
}

public class DebugToStringHandler extends DebugAbstractHandler {

    //########################################################################
    // DebugToStringHandler interface

    public static DebugToStringHandler instance = new DebugToStringHandler();

    //########################################################################
    // DebugHandler interface

    public void append0(StringBuffer buffer, Object that) {
        buffer.append(that);
    }

    //########################################################################
}

public class DebugObject extends DebugAbstractHandler {

    //########################################################################
    // DebugObject interface

    public static DebugObject instance = new DebugObject();

    public void append1(StringBuffer buffer, Object that) {
        Debug.appendObject(buffer, that);
    }

    //########################################################################
    // DebugHandler interface

    public void append0(StringBuffer buffer, Object that) {
        append1(buffer, (Object)that);
    }

    //########################################################################
}

public class DebugArray extends DebugAbstractHandler {

    //########################################################################
    // DebugArray interface

    public static DebugArray instance = new DebugArray();

    public void append1(StringBuffer buffer, Object[] that) {
        Debug.appendArray(buffer, that);
    }

    //########################################################################
    // DebugHandler interface

    public void append0(StringBuffer buffer, Object that) {
        append1(buffer, (Object[])that);
    }

    //########################################################################
}

public class DebugType extends DebugAbstractHandler {

    //########################################################################
    // DebugType interface

    public static DebugType instance = new DebugType();

    public void append1(StringBuffer buffer, Type that) {
        switch (that) {

        case ErrorType:
            buffer.append("ErrorType");
            return;

        case AnyType:
            buffer.append("AnyType");
            return;

        case NoType:
            buffer.append("NoType");
            return;

	case ThisType(Symbol symbol):
            buffer.append("ThisType(");
            Debug.append(buffer, symbol);
            buffer.append(')');
            return;

        case TypeRef(Type prefix, Symbol symbol, Type[] args) :
            buffer.append("TypeRef(");
            Debug.append(buffer, prefix);
            buffer.append(',');
            Debug.append(buffer, symbol);
	    buffer.append(',');
	    Debug.append(buffer, args);
            buffer.append(')');
            return;

        case SingleType(Type prefix, Symbol symbol):
            buffer.append("SingleType(");
            Debug.append(buffer, prefix);
            buffer.append(',');
            Debug.append(buffer, symbol);
            buffer.append(')');
            return;

        case CompoundType(Type[] basetypes, Scope members):
            buffer.append("CompoundType(");
            Debug.append(buffer, basetypes);
            buffer.append(',');
            Debug.append(buffer, members);
            buffer.append(',');
            Debug.append(buffer, that.symbol());
            buffer.append(')');
            return;

        case MethodType(Symbol[] vparams, Type result):
            buffer.append("MethodType(");
            Debug.append(buffer, vparams);
            buffer.append(',');
            Debug.append(buffer, result);
            buffer.append(')');
            return;

        case PolyType(Symbol[] tparams, Type result):
            buffer.append("PolyType(");
            Debug.append(buffer, tparams);
            buffer.append(',');
            Debug.append(buffer, result);
            buffer.append(')');
            return;

        case OverloadedType(Symbol[] alts, Type[] alttypes):
            buffer.append("OverloadedType(");
            Debug.append(buffer, alts);
            buffer.append(',');
            Debug.append(buffer, alttypes);
            buffer.append(')');
            return;

        case CovarType(Type tp):
            buffer.append("CovarType(");
            Debug.append(buffer, tp);
            buffer.append(')');
            return;

        case LazyType():
            buffer.append("LazyType()");
            return;

        case TypeVar(Type origin, Type.Constraint constr):
            buffer.append("TypeVar(");
            Debug.append(buffer, origin);
            buffer.append(',');
            Debug.append(buffer, constr);
            buffer.append(')');
            return;

        case UnboxedType(int tag):
            buffer.append("UnboxedType(");
            buffer.append(tag);
            buffer.append(')');
            return;

        case UnboxedArrayType(Type elemtp):
            buffer.append("UnboxedArrayType(");
            Debug.append(buffer, elemtp);
            buffer.append(')');
            return;

        default:
            Debug.appendDefault(buffer, that);
            return;
        }
    }

    //########################################################################
    // DebugHandler interface

    public void append0(StringBuffer buffer, Object that) {
        append1(buffer, (Type)that);
    }

    //########################################################################
}

public class DebugSymbol extends DebugAbstractHandler {

    //########################################################################
    // DebugSymbol interface

    public static DebugSymbol instance = new DebugSymbol();

    public void append1(StringBuffer buffer, Symbol that) {
        if (that != Symbol.NONE && that != Symbol.ERROR) {
            buffer.append(that.kindString()).append(' ');
            if (that.owner() != Global.instance.definitions.ROOT_CLASS) {
                Debug.append(buffer, that.owner());
                buffer.append(".");
            }
        }
        buffer.append(that.name);
        if (Global.instance.uniqid) {
            buffer.append('#');
            buffer.append(Global.instance.uniqueID.id(that));
        }
    }

    //########################################################################
    // DebugHandler interface

    public void append0(StringBuffer buffer, Object that) {
        append1(buffer, (Symbol)that);
    }

    //########################################################################
}

public class DebugScope extends DebugAbstractHandler {

    //########################################################################
    // DebugScope interface

    public static DebugScope instance = new DebugScope();

    public void append1(StringBuffer buffer, Scope that) {
        buffer.append('{');
        for (Scope.SymbolIterator i = that.iterator(); i.hasNext();) {
            Debug.append(buffer, i.next());
            if (i.hasNext()) buffer.append(',');
        }
        buffer.append('}');
    }

    //########################################################################
    // DebugHandler interface

    public void append0(StringBuffer buffer, Object that) {
        append1(buffer, (Scope)that);
    }

    //########################################################################
}

