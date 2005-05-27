/* NSC -- new scala compiler
 * Copyright 2005 LAMP/EPFL
 * @author  Martin Odersky
 */
// $Id$
package scala.tools.nsc.symtab;

import scala.tools.util.Position;
import Flags._;

abstract class Definitions: SymbolTable {
  object definitions {

    // root packages and classes
    var RootClass: Symbol = _;
    var EmptyPackage: Symbol = _;
    var EmptyPackageClass: Symbol = _;
    var emptypackagescope: Scope = null; //debug

    var JavaPackage: Symbol = _;
    var JavaLangPackage: Symbol = _;
    var ScalaPackage: Symbol = _;
    var ScalaPackageClass: Symbol = _;

    var AnyClass: Symbol = _;
    var AnyValClass: Symbol = _;
    var ObjectClass: Symbol = _;

    var AnyRefClass: Symbol = _;

    var AllRefClass: Symbol = _;
    var AllClass: Symbol = _;
    
    var StringClass: Symbol = _;
    var ThrowableClass: Symbol = _;

    // the scala value classes
    var UnitClass: Symbol = _;
    var BooleanClass: Symbol = _;
    var ByteClass: Symbol = _;
    var ShortClass: Symbol = _;
    var CharClass: Symbol = _;
    var IntClass: Symbol = _;
    var LongClass: Symbol = _;
    var FloatClass: Symbol = _;
    var DoubleClass: Symbol = _;

    // the scala reference classes
    var ScalaObjectClass: Symbol = _;
    var AttributeClass: Symbol = _;
    var RefClass: Symbol = _;
    var PartialFunctionClass: Symbol = _;
    var IterableClass: Symbol = _;
    var IteratorClass: Symbol = _;
    var SeqClass: Symbol = _;
    var ListClass: Symbol = _;
    var ArrayClass: Symbol = _;
    var TypeClass: Symbol = _;
    var PredefModule: Symbol = _;
    var ConsoleModule: Symbol = _;
    var MatchErrorModule: Symbol = _;
    var RepeatedParamClass: Symbol = _;
    var ByNameParamClass: Symbol = _;

    val MaxTupleArity = 9;
    val MaxFunctionArity = 9;
    def TupleClass(i: int): Symbol = getClass("scala.Tuple" + i);
    def FunctionClass(i: int): Symbol = getClass("scala.Function" + i);

    def tupleType(elems: List[Type]) = 
      if (elems.length <= MaxTupleArity) {
	val sym = TupleClass(elems.length);
	typeRef(sym.typeConstructor.prefix, sym, elems)
      } else NoType;
    
    def functionType(formals: List[Type], restpe: Type) =
      if (formals.length <= MaxFunctionArity) {
	val sym = FunctionClass(formals.length);
	typeRef(sym.typeConstructor.prefix, sym, formals ::: List(restpe))
      } else NoType;

    def isTupleType(tp: Type): boolean = tp match {
      case TypeRef(_, sym, elems) =>
        elems.length <= MaxTupleArity && sym == TupleClass(elems.length);
      case _ =>
        false
    }

    def isFunctionType(tp: Type): boolean = tp match {
      case TypeRef(_, sym, args) =>
        (args.length > 0) && (args.length - 1 <= MaxFunctionArity) && 
        (sym == FunctionClass(args.length - 1))
      case _ =>
        false
    }

    def seqType(arg: Type) =
      typeRef(SeqClass.typeConstructor.prefix, SeqClass, List(arg));

//    def NilModule: Symbol = getModule("scala.Nil");
//    def ConsClass: Symbol = getClass("scala.$colon$colon");

    // members of class scala.Any
    var Any_==          : Symbol = _;
    var Any_!=          : Symbol = _;
    var Any_equals      : Symbol = _;
    var Any_hashCode    : Symbol = _;
    var Any_toString    : Symbol = _;
    var Any_isInstanceOf: Symbol = _;
    var Any_asInstanceOf: Symbol = _;
    var Any_isInstanceOfErased: Symbol = _;
    var Any_asInstanceOfErased: Symbol = _;
    
    // members of class java.lang.{Object, String}
    var Object_eq          : Symbol = _;
    var Object_ne          : Symbol = _;
    var Object_synchronized: Symbol = _;
    var String_+           : Symbol = _;

    // pattern wildcard
    var PatternWildcard: Symbol = _;

    def getModule(fullname: Name): Symbol = 
      getModuleOrClass(fullname, true);
    def getClass(fullname: Name): Symbol = 
      getModuleOrClass(fullname, false);
    
    private def getModuleOrClass(fullname: Name, module: boolean): Symbol = {
      var sym = RootClass;
      var i = 0;
      var j = fullname.pos('.', i);
      while (j < fullname.length) {
        sym = sym.info.nonPrivateMember(fullname.subName(i, j));
        i = j + 1;
        j = fullname.pos('.', i)
      }
      val result = 
        if (module) sym.info.nonPrivateMember(fullname.subName(i, j)).suchThat(.hasFlag(MODULE));
        else sym.info.nonPrivateMember(fullname.subName(i, j).toTypeName);
      if (result == NoSymbol) 
	throw new FatalError((if (module) "object " else "class ") + fullname + " not found.");
      result
    }

    private def newClass(owner: Symbol, name: Name, parents: List[Type]): Symbol = {
      val clazz = owner.newClass(Position.NOPOS, name.toTypeName);
      clazz.setInfo(ClassInfoType(parents, new Scope(), clazz));
      owner.info.decls.enter(clazz);
      clazz
    }

    private def newCovariantPolyClass(owner: Symbol, name: Name, parent: Symbol => Type): Symbol = {
      val clazz = newClass(owner, name, List());
      val tparam = newTypeParam(clazz, 0) setFlag COVARIANT;
      clazz.setInfo(
	PolyType(
	  List(tparam),
	  ClassInfoType(List(parent(tparam)), new Scope(), clazz)))
    }

    private def newAlias(owner: Symbol, name: Name, alias: Type): Symbol = {
      val tpsym = owner.newAliasType(Position.NOPOS, name.toTypeName);
      tpsym.setInfo(alias);
      owner.info.decls.enter(tpsym);
      tpsym
    }

    private def newMethod(owner: Symbol, name: Name): Symbol = {
      val msym = owner.newMethod(Position.NOPOS, name.encode);
      owner.info.decls.enter(msym);
      msym
    }

    private def newMethod(owner: Symbol, name: Name, formals: List[Type], restpe: Type): Symbol = 
      newMethod(owner, name).setInfo(MethodType(formals, restpe));

    private def newPolyMethod(owner: Symbol, name: Name, tcon: Symbol => Type): Symbol = {
      val msym = newMethod(owner, name);
      val tparam = newTypeParam(msym, 0);
      msym.setInfo(PolyType(List(tparam), tcon(tparam)))
    }

    private def newTypeParam(owner: Symbol, index: int): Symbol = 
      owner.newTypeParameter(Position.NOPOS, "T" + index)
        .setInfo(TypeBounds(AllClass.typeConstructor, AnyClass.typeConstructor));

    def init = {
      RootClass = 
	NoSymbol.newClass(Position.NOPOS, nme.ROOT.toTypeName)
	  .setFlag(FINAL | MODULE | PACKAGE | JAVA).setInfo(rootLoader);

      EmptyPackage = 
	RootClass.newPackage(Position.NOPOS, nme.EMPTY_PACKAGE_NAME).setFlag(FINAL);
      EmptyPackageClass = EmptyPackage.moduleClass;
      EmptyPackageClass.setInfo(ClassInfoType(List(), new Scope(), EmptyPackageClass));


      EmptyPackage.setInfo(EmptyPackageClass.tpe);
      RootClass.info.decls.enter(EmptyPackage);

      JavaPackage = getModule("java");
      JavaLangPackage = getModule("java.lang");
      ScalaPackage = getModule("scala");
      ScalaPackageClass = ScalaPackage.tpe.symbol;

      AnyClass = newClass(ScalaPackageClass, "Any", List());
      AnyValClass = getClass("scala.AnyVal");
      ObjectClass = getClass("java.lang.Object");

      AnyRefClass = newAlias(ScalaPackageClass, "AnyRef", ObjectClass.typeConstructor);

      AllRefClass = newClass(ScalaPackageClass, "AllRef", List(AnyRefClass.typeConstructor));
      AllClass = newClass(ScalaPackageClass, "All", List(AnyClass.typeConstructor));

      StringClass = getClass("java.lang.String");
      ThrowableClass = getClass("java.lang.Throwable");

      // the scala value classes
      UnitClass = getClass("scala.Unit");
      BooleanClass = getClass("scala.Boolean");
      ByteClass = getClass("scala.Byte");
      ShortClass = getClass("scala.Short");
      CharClass = getClass("scala.Char");
      IntClass = getClass("scala.Int");
      LongClass = getClass("scala.Long");
      FloatClass = getClass("scala.Float");
      DoubleClass = getClass("scala.Double");

      // the scala reference classes
      ScalaObjectClass = getClass("scala.ScalaObject");
      AttributeClass = getClass("scala.Attribute");
      RefClass = getClass("scala.Ref");
      PartialFunctionClass = getClass("scala.PartialFunction");
      IterableClass = getClass("scala.Iterable");
      IteratorClass = getClass("scala.Iterator");
      SeqClass = getClass("scala.Seq");
      ListClass = getClass("scala.List");
      ArrayClass = getClass("scala.Array");
      TypeClass = getClass("scala.Type");
      PredefModule = getModule("scala.Predef");
      ConsoleModule = getModule("scala.Console");
      MatchErrorModule = getModule("scala.MatchError");
      RepeatedParamClass = newCovariantPolyClass(
        ScalaPackageClass, nme.REPEATED_PARAM_CLASS_NAME, 
        tparam => typeRef(SeqClass.typeConstructor.prefix, SeqClass, List(tparam.typeConstructor)));
      ByNameParamClass = newCovariantPolyClass(
        ScalaPackageClass, nme.BYNAME_PARAM_CLASS_NAME, tparam => AnyClass.typeConstructor);

      // members of class scala.Any
      Any_== = newMethod(
        AnyClass, "==", List(AnyClass.typeConstructor), BooleanClass.typeConstructor) setFlag FINAL;
      Any_!= = newMethod(
        AnyClass, "!=", List(AnyClass.typeConstructor), BooleanClass.typeConstructor) setFlag FINAL;
      Any_equals = newMethod(
        AnyClass, "equals", List(AnyClass.typeConstructor), BooleanClass.typeConstructor);
      Any_hashCode = newMethod(
        AnyClass, "hashCode", List(), IntClass.typeConstructor);
      Any_toString = newMethod(
        AnyClass, "toString", List(), StringClass.typeConstructor);

      Any_isInstanceOf = newPolyMethod(
        AnyClass, "isInstanceOf", tparam => BooleanClass.typeConstructor) setFlag FINAL;
      Any_asInstanceOf = newPolyMethod(
        AnyClass, "asInstanceOf", tparam => tparam.typeConstructor) setFlag FINAL;
      Any_isInstanceOfErased = newPolyMethod(
        AnyClass, "isInstanceOf$erased", tparam => BooleanClass.typeConstructor) setFlag FINAL;
      Any_asInstanceOfErased = newPolyMethod(
        AnyClass, "asInstanceOf$erased", tparam => tparam.typeConstructor) setFlag FINAL;

      // members of class java.lang.{Object, String}
      Object_eq = newMethod(
        ObjectClass, "eq", List(AnyRefClass.typeConstructor), BooleanClass.typeConstructor) setFlag FINAL;
      Object_ne = newMethod(
        ObjectClass, "ne", List(AnyRefClass.typeConstructor), BooleanClass.typeConstructor) setFlag FINAL;
      Object_synchronized = newPolyMethod(
        ObjectClass, "synchronized", tparam => MethodType(List(tparam.typeConstructor), tparam.typeConstructor)) setFlag FINAL;

      String_+ = newMethod(
        StringClass, "+", List(AnyClass.typeConstructor), StringClass.typeConstructor) setFlag FINAL;

      // pattern wildcard
      PatternWildcard = NoSymbol.newValue(Position.NOPOS, "_").setInfo(AllClass.typeConstructor)
    }
  }
}
