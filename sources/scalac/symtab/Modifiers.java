/*     ____ ____  ____ ____  ______                                     *\
**    / __// __ \/ __// __ \/ ____/    SOcos COmpiles Scala             **
**  __\_ \/ /_/ / /__/ /_/ /\_ \       (c) 2002, LAMP/EPFL              **
** /_____/\____/\___/\____/____/                                        **
**                                                                      **
** $Id$
\*                                                                      */

package scalac.symtab;

public interface Modifiers {
    
    // modifiers
    int ABSTRACT      = 0x00000001;
    int FINAL         = 0x00000002;
    int PRIVATE       = 0x00000004;
    int PROTECTED     = 0x00000008;

    int QUALIFIED     = 0x00000010;
    int OVERRIDE      = 0x00000020;
    int CASE          = 0x00000040;
    int ABSTRACTCLASS = 0x00000080;   // abstract class

    int DEF           = 0x00000100;   // a def parameter
    int REPEATED      = 0x00000200;   // a repeated parameter
    int SYNTHETIC     = 0x00000400;
    int DEPRECATED    = 0x00000800;

    int JAVA          = 0x00001000;   // symbol was defined by a Java class
    int MODUL         = 0x00002000;   // symbol is module or class implementing a module
    int MUTABLE       = 0x00004000;   // symbol is a mutable variable.
    int PARAM         = 0x00008000;   // symbol is a (type) parameter to a method

    int INITIALIZED   = 0x00010000;   // symbol's definition is complete
    int LOCKED        = 0x00020000;   // temporary flag to catch cyclic dependencies
    int ACCESSED      = 0x00040000;   // symbol was accessed at least once
    int SELECTOR      = 0x00080000;   // symbol was used as selector in Select

    int PACKAGE       = 0x00100000;   // symbol is a java packages.
    int LABEL         = 0x00200000;   // symbol is a label symbol
    int STATIC        = 0x00400000;   // "static" inner classes (i.e. after class norm.)
    int STABLE        = 0x00800000;   // functions that are assumed to be stable
                                    // (typically, access methods for valdefs)

    int CAPTURED      = 0x01000000;   // variables is accessed from nested function.

    int ACCESSOR      = 0x04000000;   // function is an access function for a 
                                      // value or variable
    int BRIDGE        = 0x0800000;   // function is a bridge method.

    int INTERFACE     = 0x10000000;   // symbol is a Java interface
    int TRAIT         = 0x20000000;   // symbol is a Trait

    int SNDTIME       = 0x40000000;   //debug
    
    // masks
    int SOURCEFLAGS   = 0x00000077 | PARAM | TRAIT;  // these modifiers can be set in source programs.
    int ACCESSFLAGS = PRIVATE | PROTECTED;
    
    public static class Helper {

        public static boolean isAbstract(int flags) {
            return (flags & (ABSTRACT | ABSTRACTCLASS))  != 0;
        }

        public static boolean isFinal(int flags) {
            return (flags & FINAL)     != 0;
        }

        public static boolean isPrivate(int flags) {
            return (flags & PRIVATE)   != 0;
        }

        public static boolean isProtected(int flags) {
            return (flags & PROTECTED) != 0;
        }

        public static boolean isQualified(int flags) {
            return (flags & QUALIFIED) != 0;
        }

        public static boolean isOverride(int flags) {
            return (flags & OVERRIDE)  != 0;
        }

        public static boolean isCase(int flags) {
            return (flags & CASE)      != 0;
        }

        public static boolean isInterface(int flags) {
            return (flags & INTERFACE) != 0;
        }

        public static boolean isDef(int flags) {
            return (flags & DEF)       != 0;
        }

        public static boolean isModClass(int flags) {
            return (flags & MODUL)  != 0;
        }

        public static boolean isStatic(int flags) {
            return (flags & STATIC)  != 0;
        }

        public static boolean isJava(int flags) {
            return (flags & JAVA)  != 0;
        }

        public static boolean isNoVal(int flags) {
            return (flags & PACKAGE)  != 0;
        }

        public static String toString(int flags) {
            StringBuffer buffer = new StringBuffer();
            toString(buffer, flags);
            return buffer.toString();
        }

        public static void toString(StringBuffer buffer, int flags) {
            //buffer.append(flags).append(": ");//debug
            int marker = buffer.length();
            if (isPrivate(flags)) buffer.append("private ");
            if (isProtected(flags)) buffer.append("protected ");
            if (isAbstract(flags)) buffer.append("abstract ");
            if (isFinal(flags)) buffer.append("final ");
            if (isQualified(flags)) buffer.append("qualified ");
            if (isInterface(flags)) buffer.append("interface ");
            if (isCase(flags)) buffer.append("case ");
            if (isDef(flags)) buffer.append("def ");
            if (isOverride(flags)) buffer.append("override ");
            int length = buffer.length();
            buffer.setLength(length - (length == marker ? 0 : 1));
        }
    }
}
