/* NSC -- new scala compiler
 * Copyright 2005 LAMP/EPFL
 * @author  Martin Odersky
 */
// $Id$
package scala.tools.nsc.symtab;

import scala.tools.nsc.util.NameTransformer;

abstract class StdNames: SymbolTable {

  object nme {

    // Scala keywords; enter them first to minimize scanner.maxKey
    val ABSTRACTkw = newTermName("abstract");
    val CASEkw = newTermName("case");
    val CLASSkw = newTermName("class");
    val CATCHkw = newTermName("catch");
    val DEFkw = newTermName("def");
    val DOkw = newTermName("do");
    val ELSEkw = newTermName("else");
    val EXTENDSkw = newTermName("extends");
    val FALSEkw = newTermName("false");
    val FINALkw = newTermName("final");
    val FINALLYkw = newTermName("finally");
    val FORkw = newTermName("for");
    val IFkw = newTermName("if");
    val IMPLICITkw = newTermName("implicit");
    val IMPORTkw = newTermName("import");
    val MATCHkw = newTermName("match");
    val NEWkw = newTermName("new");
    val NULLkw = newTermName("null");
    val OBJECTkw = newTermName("object");
    val OUTER = newTermName("$outer");
    val OVERRIDEkw = newTermName("override");
    val PACKAGEkw = newTermName("package");
    val PRIVATEkw = newTermName("private");
    val PROTECTEDkw = newTermName("protected");
    val RETURNkw = newTermName("return");
    val SEALEDkw = newTermName("sealed");
    val SUPERkw = newTermName("super");
    val THISkw = newTermName("this");
    val THROWkw = newTermName("throw");
    val TRAITkw = newTermName("trait");
    val TRUEkw = newTermName("true");
    val TRYkw = newTermName("try");
    val TYPEkw = newTermName("type");
    val VALkw = newTermName("val");
    val VARkw = newTermName("var");
    val WITHkw = newTermName("with");
    val WHILEkw = newTermName("while");
    val YIELDkw = newTermName("yield");
    val DOTkw = newTermName(".");
    val USCOREkw = newTermName("_");
    val COLONkw = newTermName(":");
    val EQUALSkw = newTermName("=");
    val ARROWkw = newTermName("=>");
    val LARROWkw = newTermName("<-");
    val SUBTYPEkw = newTermName("<:");
    val VIEWBOUNDkw = newTermName("<%");
    val SUPERTYPEkw = newTermName(">:");
    val HASHkw = newTermName("#");
    val ATkw = newTermName("@");

    private val LOCAL_PREFIX_STRING = "local$";
    private val MIXIN_PREFIX_STRING = "mixin$";
    private val OUTER_PREFIX_STRING = "outer$";
    private val SUPER_PREFIX_STRING = "super$";
    private val ACCESS_PREFIX_STRING = "access$";
    private val TUPLE_FIELD_PREFIX_STRING = "_";
    private val TYPE_PREFIX_STRING = "type$";

    val LOCAL_PREFIX = newTermName(LOCAL_PREFIX_STRING);

    def LOCAL(clazz: Symbol) = newTermName(LOCAL_PREFIX_STRING + clazz.name);

    def TUPLE_FIELD(index: int) = newTermName(TUPLE_FIELD_PREFIX_STRING + index);

    def isLocalName(name: Name) = originalName(name).endsWith(LOCAL_SUFFIX);
    def isSetterName(name: Name) = originalName(name).endsWith(_EQ);

    def originalName(name: Name) = name.subName(0, name.pos("$$"));

    def applyToOriginal(name: Name, f: Name => Name): Name = {
      val oname = originalName(name);
      if (oname == name) f(name) 
      else newTermName(f(oname).toString() + name.subName(oname.length, name.length))
    }

    def localToGetter(name: Name): Name = {
      assert(isLocalName(name));//debug
      applyToOriginal(name, oname => oname.subName(0, oname.length - LOCAL_SUFFIX.length));
    }

    def getterToLocal(name: Name): Name = {
      assert(!isLocalName(name) && !isSetterName(name));//debug
      applyToOriginal(name, oname => newTermName(oname.toString() + LOCAL_SUFFIX));
    }
                     
    def getterToSetter(name: Name): Name = {
      assert(!isLocalName(name) && !isSetterName(name));//debug
      applyToOriginal(name, oname => newTermName(oname.toString() + SETTER_SUFFIX));
    }

    def setterToGetter(name: Name): Name = {
      assert(isSetterName(name));//debug
      applyToOriginal(name, oname => oname.subName(0, oname.length - SETTER_SUFFIX.length));
    }

    def superName(name: Name) = newTermName("super$" + name);

    val ERROR = newTermName("<error>");
    val ERRORtype = newTypeName("<error>");

    val NOSYMBOL = newTermName("<none>");
    val EMPTY = newTermName("");
    val ANYNAME = newTermName("<anyname>");
    val WILDCARD = newTermName("_");
    val WILDCARD_STAR = newTermName("_*");
    val COMPOUND_NAME = newTermName("<ct>");
    val ANON_CLASS_NAME = newTermName("$anon");
    val ANONFUN_CLASS_NAME = newTermName("$anonfun");
    val IMPL_CLASS_SUFFIX = newTermName("$class");
    val REFINE_CLASS_NAME = newTermName("<refinement>");
    val EMPTY_PACKAGE_NAME = newTermName("<empty>");
    val IMPORT = newTermName("<import>");
    val ZERO = newTermName("<zero>");
    val STAR = newTermName("*");
    val ROOT = newTermName("<root>");
    val REPEATED_PARAM_CLASS_NAME = newTermName("<repeated>");
    val BYNAME_PARAM_CLASS_NAME = newTermName("<byname>");
    val SELF = newTermName("$self");

    val CONSTRUCTOR = newTermName("<init>");
    val MIXIN_CONSTRUCTOR = newTermName("$init$");
    val INITIALIZER = newTermName("<init>");
    val INLINED_INITIALIZER = newTermName("$init$");

    val _EQ = encode("_=");
    val MINUS = encode("-");
    val PLUS = encode("+");
    val TILDE = encode("~");
    val EQEQ = encode("==");
    val BANG = encode("!");
    val BANGEQ = encode("!=");
    val BARBAR = encode("||");
    val AMPAMP = encode("&&");
    val COLONCOLON = encode("::");
    val PERCENT = encode("%");

    val All = newTermName("All");
    val AllRef = newTermName("AllRef");
    val Any = newTermName("Any");
    val AnyVal = newTermName("AnyVal");
    val AnyRef = newTermName("AnyRef");
    val Array = newTermName("Array");
    val Byte = newTermName("Byte");
    val CaseClass = newTermName("CaseClass");
    val Catch = newTermName("Catch");
    val Char = newTermName("Char");
    val Boolean = newTermName("Boolean");
    val Do = newTermName("Do");
    val Double = newTermName("Double");
    val Element = newTermName("Element");
    val Finally = newTermName("Finally");
    val Float = newTermName("Float");
    val Function = newTermName("Function");
    val Int = newTermName("Int");
    val Labelled = newTermName("Labelled");
    val List = newTermName("List");
    val Long = newTermName("Long");
    val Nil = newTermName("Nil");
    val Object = newTermName("Object");
    val PartialFunction = newTermName("PartialFunction");
    val Predef = newTermName("Predef");
    val ScalaObject = newTermName("ScalaObject");
    val ScalaRunTime = newTermName("ScalaRunTime");
    val Seq = newTermName("Seq");
    val Short = newTermName("Short");
    val SourceFile = newTermName("SourceFile");
    val String = newTermName("String");
    val Symbol = newTermName("Symbol");
    val Synthetic = newTermName("Synthetic");

    val Text = newTermName("Text");
    val Throwable = newTermName("Throwable");
    val Try = newTermName("Try");
    val Tuple = newTermName("Tuple");
    val Type = newTermName("Type");
    val Tuple2 = newTermName("Tuple2");
    val Unit = newTermName("Unit");
    val While = newTermName("While");
    val apply = newTermName("apply");
    val array = newTermName("array");
    val asInstanceOf = newTermName("asInstanceOf");
    val asInstanceOfErased = newTermName("asInstanceOf$erased");
    val box = newTermName("box");
    val caseArity = newTermName("caseArity");
    val caseElement = newTermName("caseElement");
    val caseName = newTermName("caseName");
    val checkCastability = newTermName("checkCastability");
    val coerce = newTermName("coerce");
    val defaultValue = newTermName("defaultValue");
    val dummy = newTermName("$dummy");
    val elem = newTermName("elem");
    val elements = newTermName("elements");
    val eq = newTermName("eq");
    val equals_ = newTermName("equals");
    val fail = newTermName("fail");
    val report = newTermName("report");
    val false_ = newTermName("false");
    val filter = newTermName("filter");
    val finalize_ = newTermName("finalize");
    val flatMap = newTermName("flatMap");
    val foreach = newTermName("foreach");
    val getClass_ = newTermName("getClass");
    val hasAsInstance = newTermName("hasAsInstance");
    val hashCode_ = newTermName("hashCode");
    val hasNext = newTermName("hasNext");
    val head = newTermName("head");
    val isInstanceOf = newTermName("isInstanceOf");
    val isInstanceOfErased = newTermName("isInstanceOf$erased");
    val isDefinedAt = newTermName("isDefinedAt");
    val isEmpty = newTermName("isEmpty");
    val java = newTermName("java");
    val lang = newTermName("lang");
    val length = newTermName("length");
    val map = newTermName("map");
    val n = newTermName("n");
    val nobinding = newTermName("nobinding");
    val next = newTermName("next");
    val newArray = newTermName("newArray");
    val notify_ = newTermName("notify");
    val notifyAll_ = newTermName("notifyAll");
    val null_ = newTermName("null");
    val predef = newTermName("predef");
    val print = newTermName("print");
    val runtime = newTermName("runtime");
    val readResolve = newTermName("readResolve");
    val scala_ = newTermName("scala");
    val xml = newTermName("xml");
    val synchronized_ = newTermName("synchronized");
    val tail = newTermName("tail");
    val toString_ = newTermName("toString");
    val that = newTermName("that");
    val that1 = newTermName("that1");
    val this_ = newTermName("this");
    val throw_ = newTermName("throw");
    val true_ = newTermName("true");
    val update = newTermName("update");
    val view_ = newTermName("view");
    val tag = newTermName("$tag");
    val wait_ = newTermName("wait");

    val ZNOT = encode("!");
    val ZAND = encode("&&");
    val ZOR  = encode("||");
    val NOT  = encode("~");
    val ADD  = encode("+");
    val SUB  = encode("-");
    val MUL  = encode("*");
    val DIV  = encode("/");
    val MOD  = encode("%");
    val EQ   = encode("==");
    val NE   = encode("!=");
    val LT   = encode("<");
    val LE   = encode("<=");
    val GT   = encode(">");
    val GE   = encode(">=");
    val OR   = encode("|");
    val XOR  = encode("^");
    val AND  = encode("&");
    val LSL  = encode("<<");
    val LSR  = encode(">>>");
    val ASR  = encode(">>");

    val SourceFileATTR = newTermName("SourceFile");
    val SyntheticATTR = newTermName("Synthetic");
    val BridgeATTR = newTermName("Bridge");
    val DeprecatedATTR = newTermName("Deprecated");
    val CodeATTR = newTermName("Code");
    val ExceptionsATTR = newTermName("Exceptions");
    val ConstantValueATTR = newTermName("ConstantValue");
    val LineNumberTableATTR = newTermName("LineNumberTable");
    val LocalVariableTableATTR = newTermName("LocalVariableTable");
    val InnerClassesATTR = newTermName("InnerClasses");
    val JacoMetaATTR = newTermName("JacoMeta");
    val ScalaSignatureATTR = newTermName("ScalaSignature");
    val JavaInterfaceATTR = newTermName("JacoInterface");

    val LOCAL_SUFFIX = newTermName(" ");
    val SETTER_SUFFIX = _EQ;
  }

  def encode(str: String): Name = newTermName(NameTransformer.encode(str));
}
