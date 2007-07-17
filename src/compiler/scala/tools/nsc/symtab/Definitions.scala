/* NSC -- new Scala compiler
 * Copyright 2005-2007 LAMP/EPFL
 * @author  Martin Odersky
 */
// $Id$

package scala.tools.nsc.symtab

import scala.collection.mutable.{HashMap, HashSet}
import scala.tools.nsc.util.{Position, NoPosition}
import Flags._

trait Definitions {
  self: SymbolTable =>

  object definitions {
    def isDefinitionsInitialized = isInitialized

    // root packages and classes
    lazy val RootPackage: Symbol = NoSymbol.newValue(NoPosition, nme.ROOTPKG)
          .setFlag(FINAL | MODULE | PACKAGE | JAVA)
          .setInfo(PolyType(List(), RootClass.tpe))
    lazy val RootClass: Symbol = {
      NoSymbol.newClass(NoPosition, nme.ROOT.toTypeName)
        .setFlag(FINAL | MODULE | PACKAGE | JAVA).setInfo(rootLoader)
    }
    lazy val EmptyPackage: Symbol = RootClass.newPackage(NoPosition, nme.EMPTY_PACKAGE_NAME).setFlag(FINAL)

    lazy val EmptyPackageClass: Symbol = EmptyPackage.moduleClass

    var emptypackagescope: Scope = null //debug

    lazy val JavaLangPackage: Symbol = getModule(if (forMSIL) "System" else "java.lang")
    lazy val ScalaPackage: Symbol = getModule("scala")
    lazy val ScalaPackageClass: Symbol = ScalaPackage.tpe.typeSymbol

    var AnyClass: Symbol = _
    var AnyValClass: Symbol = _
    var AnyRefClass: Symbol = _
    lazy val ObjectClass: Symbol = getClass(if (forMSIL) "System.Object" else "java.lang.Object")

    lazy val anyrefparam = List(AnyRefClass.typeConstructor)

    var AllRefClass: Symbol = _
    var AllClass: Symbol = _
    var SingletonClass: Symbol = _

    lazy val ClassClass: Symbol = getClass(if (forMSIL) "System.Type" else "java.lang.Class")
    lazy val StringClass: Symbol = getClass(if (forMSIL) "System.String" else "java.lang.String")
    lazy val ThrowableClass: Symbol = getClass(if (forMSIL) "System.Exception" else "java.lang.Throwable")
    lazy val NullPointerExceptionClass: Symbol = 
      getClass(if (forMSIL) "System.NullReferenceException"
               else "java.lang.NullPointerException")
    lazy val NonLocalReturnExceptionClass: Symbol = 
      getClass("scala.runtime.NonLocalReturnException")

    // System.ValueType
    lazy val ValueTypeClass: Symbol = if (forMSIL) getClass("System.ValueType") else null
    // System.MulticastDelegate
    lazy val DelegateClass: Symbol = if (forMSIL) getClass("System.MulticastDelegate") else null
    var Delegate_scalaCallers: List[Symbol] = List()
    // Symbol -> (Symbol, Type): scalaCaller -> (scalaMethodSym, DelegateType)
    // var Delegate_scalaCallerInfos: HashMap[Symbol, (Symbol, Type)] = _
    var Delegate_scalaCallerTargets: HashMap[Symbol, Symbol] = _

    // the scala value classes
    var UnitClass: Symbol = _
    var BooleanClass: Symbol = _
      def Boolean_not = getMember(BooleanClass, nme.UNARY_!)
      def Boolean_and = getMember(BooleanClass, nme.ZAND)
      def Boolean_or  = getMember(BooleanClass, nme.ZOR)
    var ByteClass: Symbol = _
    var ShortClass: Symbol = _
    var CharClass: Symbol = _
    var IntClass: Symbol = _
      def Int_Or  = definitions.getMember(definitions.IntClass, nme.OR)
      def Int_And = definitions.getMember(definitions.IntClass, nme.AND)
      def Int_==  = definitions.getMember(definitions.IntClass, nme.EQEQ)

    var LongClass: Symbol = _
    var FloatClass: Symbol = _
    var DoubleClass: Symbol = _

    // the scala reference classes
    lazy val ScalaObjectClass: Symbol = getClass("scala.ScalaObject")
      def ScalaObjectClass_tag = getMember(ScalaObjectClass, nme.tag)
    lazy val AnnotationClass: Symbol = getClass("scala.Annotation")
    lazy val ClassfileAnnotationClass: Symbol = getClass("scala.ClassfileAnnotation")
    lazy val StaticAnnotationClass: Symbol = getClass("scala.StaticAnnotation")
    //var ChannelClass: Symbol = _
    //  def Channel_send = getMember(ChannelClass, nme.send)
    //  def Channel_receive = getMember(ChannelClass, nme.receive)
    //var RemoteRefClass: Symbol = _
    var CodeClass: Symbol = _
    var CodeModule: Symbol = _
      def Code_lift = getMember(CodeModule, nme.lift_)
    lazy val PartialFunctionClass: Symbol = getClass("scala.PartialFunction")
    lazy val ByNameFunctionClass: Symbol = getClass("scala.ByNameFunction")
    lazy val IterableClass: Symbol = getClass("scala.Iterable")
      def Iterable_next = getMember(IterableClass, nme.next)
      def Iterable_hasNext = getMember(IterableClass, nme.hasNext)
    lazy val IteratorClass: Symbol = getClass("scala.Iterator")
    lazy val SeqClass: Symbol = getClass("scala.Seq")
      def Seq_length = getMember(SeqClass, nme.length)
    lazy val ListClass: Symbol = getClass("scala.List")
      def List_isEmpty = getMember(ListClass, nme.isEmpty)
      def List_head = getMember(ListClass, nme.head)
      def List_tail = getMember(ListClass, nme.tail)
    lazy val ListModule: Symbol = getModule("scala.List")
      def List_apply = getMember(ListModule, nme.apply)
    lazy val ArrayClass: Symbol = getClass("scala.Array")
      def Array_apply = getMember(ArrayClass, nme.apply)
    lazy val ArrayModule: Symbol = getModule("scala.Array")
    lazy val SerializableClass: Symbol = if (forMSIL || forCLDC) null else getClass("java.io.Serializable")
    lazy val PredefModule: Symbol = getModule("scala.Predef")
      def Predef_classOf = getMember(PredefModule, nme.classOf)
      def Predef_identity = getMember(PredefModule, nme.identity)
      def Predef_error    = getMember(PredefModule, nme.error)
    lazy val ConsoleModule: Symbol = getModule("scala.Console")
    lazy val MatchErrorClass: Symbol = getClass("scala.MatchError")
    //var MatchErrorModule: Symbol = _
    //  def MatchError_fail = getMember(MatchErrorModule, nme.fail)
    //  def MatchError_report = getMember(MatchErrorModule, nme.report)
    lazy val IndexOutOfBoundsExceptionClass: Symbol = 
        getClass(if (forMSIL) "System.IndexOutOfRangeException"
                 else "java.lang.IndexOutOfBoundsException")
    lazy val ScalaRunTimeModule: Symbol = getModule("scala.runtime.ScalaRunTime")
      def SeqFactory = getMember(ScalaRunTimeModule, nme.Seq);
      def checkDefinedMethod = getMember(ScalaRunTimeModule, "checkDefined")
      def isArrayMethod = getMember(ScalaRunTimeModule, "isArray")
    lazy val NotNullClass: Symbol = getClass("scala.NotNull")
    var RepeatedParamClass: Symbol = _
    var ByNameParamClass: Symbol = _
    //var UnsealedClass: Symbol = _
    lazy val UncheckedClass: Symbol = getClass("scala.unchecked")

    val MaxTupleArity = 22
    val TupleClass: Array[Symbol] = new Array(MaxTupleArity + 1)
      def tupleField(n: Int, j: Int) = getMember(TupleClass(n), "_" + j)
      def isTupleType(tp: Type): Boolean = tp.normalize match {
        case TypeRef(_, sym, elems) =>
          elems.length <= MaxTupleArity && sym == TupleClass(elems.length)
        case _ =>
          false
      }
      def tupleType(elems: List[Type]) =
        if (elems.length <= MaxTupleArity) {
          val sym = TupleClass(elems.length)
          typeRef(sym.typeConstructor.prefix, sym, elems)
        } else NoType;

    lazy val ProductRootClass: Symbol = getClass("scala.Product")
      def Product_productArity = getMember(ProductRootClass, nme.productArity)
      def Product_productElement = getMember(ProductRootClass, nme.productElement)
      def Product_productPrefix = getMember(ProductRootClass, nme.productPrefix)
    val MaxProductArity = 22
    /* <unapply> */
    val ProductClass: Array[Symbol] = new Array(MaxProductArity + 1)
      def productProj(z:Symbol, j: Int): Symbol = getMember(z, nme.Product_(j))
      def productProj(n: Int,   j: Int): Symbol = productProj(ProductClass(n), j)
    /** returns true if this type is exactly ProductN[T1,...,Tn], not some subclass */
      def isExactProductType(tp: Type): Boolean = tp.normalize match {
        case TypeRef(_, sym, elems) =>
          elems.length <= MaxProductArity && sym == ProductClass(elems.length)
        case _ =>
          false
      }
      def productType(elems: List[Type]) =
        if (elems.isEmpty)
          UnitClass.tpe
        else if (elems.length <= MaxProductArity) {
          val sym = ProductClass(elems.length)
          typeRef(sym.typeConstructor.prefix, sym, elems)
        } else NoType

    /** if tpe <: ProductN[T1,...,TN], returns Some(T1,...,TN) else None */
    def getProductArgs(tpe: Type): Option[List[Type]] = 
      tpe.baseClasses.find { x => definitions.isExactProductType(x.tpe) } match {
        case Some(p) => Some(tpe.baseType(p).typeArgs)
        case _       => None
      }

    lazy val OptionClass: Symbol = getClass("scala.Option")

    lazy val SomeClass : Symbol = getClass("scala.Some")
    lazy val NoneClass : Symbol = getModule("scala.None")

    def isOptionType(tp: Type) = tp.normalize match {
      case TypeRef(_, sym, List(_)) if sym == OptionClass => true
      case _ => false
    }
    def isOptionOrSomeType(tp: Type) = tp.normalize match {
      case TypeRef(_, sym, List(_)) => sym == OptionClass || sym == SomeClass
      case _ => false
    }
    def optionType(tp: Type) =
      typeRef(OptionClass.typeConstructor.prefix, OptionClass, List(tp))

    def isSomeType(tp: Type) = tp.normalize match {
      case TypeRef(_, sym, List(_)) if sym == SomeClass => true
      case _ => false
    }
    def someType(tp: Type) =
      typeRef(SomeClass.typeConstructor.prefix, SomeClass, List(tp))

    def isNoneType(tp: Type) = tp.normalize match {
      case TypeRef(_, sym, List(_)) if sym == NoneClass => true
      case _ => false
    }

    def unapplyUnwrap(tpe:Type) = (tpe match {
      case PolyType(_,MethodType(_, res)) => res
      case MethodType(_, res)             => res
      case tpe                            => tpe
    }).normalize
    
    /** returns type list for return type of the extraction */
    def unapplyTypeList(ufn: Symbol, ufntpe: Type) = {
      assert(ufn.isMethod)
      //Console.println("utl "+ufntpe+" "+ufntpe.typeSymbol)
      ufn.name match {
        case nme.unapply    => unapplyTypeListFromReturnType(ufntpe)
        case nme.unapplySeq => unapplyTypeListFromReturnTypeSeq(ufntpe)
        case _ => throw new IllegalArgumentException("expected function symbol of extraction")
      }
    }
    /** (the inverse of unapplyReturnTypeSeq)
     *  for type Boolean, returns Nil
     *  for type Option[T] or Some[T]:
     *   - returns T0...Tn if n>0 and T <: Product[T0...Tn]]
     *   - returns T otherwise
     */
    def unapplyTypeListFromReturnType(tp1: Type): List[Type] =  { // rename: unapplyTypeListFromReturnType
      val tp = unapplyUnwrap(tp1)
      val B = BooleanClass
      val O = OptionClass 
      val S = SomeClass 
      tp.typeSymbol match { // unapplySeqResultToMethodSig
        case  B => Nil
        case  O | S =>
          val prod = tp.typeArgs.head
          getProductArgs(prod)  match {
            case Some(all @ (x1::x2::xs)) => all       // n >= 2
            case _                        => prod::Nil // special n == 0 ||  n == 1 
          }
        case _ => throw new IllegalArgumentException(tp.typeSymbol + " in not in {boolean, option, some}")
      }
    }

    /** let type be the result type of the (possibly polymorphic) unapply method
     *  for type Option[T] or Some[T] 
     *  -returns T0...Tn-1,Tn* if n>0 and T <: Product[T0...Tn-1,Seq[Tn]]], 
     *  -returns R* if T = Seq[R]
     */
    def unapplyTypeListFromReturnTypeSeq(tp1: Type): List[Type] = {
      val tp = unapplyUnwrap(tp1)
      val   O = OptionClass; val S = SomeClass; tp.typeSymbol match { 
      case  O                  | S =>
        val ts = unapplyTypeListFromReturnType(tp1)
        val last1 = ts.last.baseType(SeqClass) match {
          case TypeRef(pre, seqClass, args) => typeRef(pre, RepeatedParamClass, args)
          case _ => throw new IllegalArgumentException("last not seq")
        }
        ts.init ::: List(last1)
        case _ => throw new IllegalArgumentException(tp.typeSymbol + " in not in {option, some}")
      }
    }

    /** returns type of the unapply method returning T_0...T_n
     *  for n == 0, boolean
     *  for n == 1, Some[T0]
     *  else Some[Product[Ti]]
    def unapplyReturnType(elems: List[Type], useWildCards: Boolean) = 
      if (elems.isEmpty)
        BooleanClass.tpe
      else if (elems.length == 1)
        optionType(if(useWildCards) WildcardType else elems(0))
      else 
        productType({val es = elems; if(useWildCards) elems map { x => WildcardType} else elems})
     */

    def unapplyReturnTypeExpected(argsLength: Int) = argsLength match {
      case 0 => BooleanClass.tpe
      case 1 => optionType(WildcardType)
      case n => optionType(productType(List.range(0,n).map (arg => WildcardType)))
    }

    /** returns unapply or unapplySeq if available */
    def unapplyMember(tp: Type): Symbol = {
      var unapp = tp.member(nme.unapply)
      if (unapp == NoSymbol) unapp = tp.member(nme.unapplySeq)
      unapp
    }
    /* </unapply> */
    val MaxFunctionArity = 9
    val FunctionClass: Array[Symbol] = new Array(MaxFunctionArity + 1)
      def functionApply(n: Int) = getMember(FunctionClass(n), nme.apply)
      def functionType(formals: List[Type], restpe: Type) =
        if (formals.length <= MaxFunctionArity) {
          val sym = FunctionClass(formals.length)
          typeRef(sym.typeConstructor.prefix, sym, formals ::: List(restpe))
        } else NoType;
      def isFunctionType(tp: Type): boolean = tp.normalize match {
        case TypeRef(_, sym, args) =>
          (args.length > 0) && (args.length - 1 <= MaxFunctionArity) &&
          (sym == FunctionClass(args.length - 1))
        case _ =>
          false
      }

    def isCorrespondingDelegate(delegateType: Type, functionType: Type): Boolean = {
      var isCD: Boolean = false
      if (DelegateClass != null && delegateType != null &&
	  isSubType(delegateType, DelegateClass.tpe))
	{
	  val meth: Symbol = delegateType.member(nme.apply)
	  meth.tpe match {
	    case MethodType(delegateParams, delegateReturn) =>
	      val delegateParamsO = delegateParams.map(pt => {if (pt == definitions.AnyClass.tpe) definitions.ObjectClass.tpe else pt})
	      if (isFunctionType(functionType))
		functionType.normalize match {
		  case TypeRef(_, _, args) =>
		    if (delegateParamsO == args.dropRight(1) &&
		        delegateReturn == args.last)
		      isCD = true;

		  case _ => ()
		}

            case _ => ()
          }
        }
      isCD
    }

    def seqType(arg: Type) =
      typeRef(SeqClass.typeConstructor.prefix, SeqClass, List(arg))

    def NilModule: Symbol = getModule("scala.Nil")
    def ConsClass: Symbol = getClass("scala.$colon$colon")

    // members of class scala.Any
    var Any_==          : Symbol = _
    var Any_!=          : Symbol = _
    var Any_equals      : Symbol = _
    var Any_hashCode    : Symbol = _
    var Any_toString    : Symbol = _
    var Any_isInstanceOf: Symbol = _
    var Any_asInstanceOf: Symbol = _
    var Any_isInstanceOfErased: Symbol = _
    var Any_asInstanceOfErased: Symbol = _

    // members of class java.lang.{Object, String}
    var Object_eq          : Symbol = _
    var Object_ne          : Symbol = _
    var Object_==          : Symbol = _
    var Object_!=          : Symbol = _
    var Object_synchronized: Symbol = _
    var Object_isInstanceOf: Symbol = _
    var Object_asInstanceOf: Symbol = _
    def Object_equals   = getMember(ObjectClass, nme.equals_)
    def Object_hashCode = getMember(ObjectClass, nme.hashCode_)
    def Object_toString = getMember(ObjectClass, nme.toString_)

    var String_+           : Symbol = _

    // members of class scala.Iterator
    var Iterator_next      : Symbol = _
    var Iterator_hasNext   : Symbol = _

    // pattern wildcard
    var PatternWildcard: Symbol = _

    // boxed classes
    lazy val BoxedArrayClass = getClass("scala.runtime.BoxedArray")
    lazy val BoxedAnyArrayClass = getClass("scala.runtime.BoxedAnyArray")
    lazy val BoxedObjectArrayClass = getClass("scala.runtime.BoxedObjectArray")
    lazy val BoxedUnitClass = getClass("scala.runtime.BoxedUnit")
    lazy val BoxedNumberClass = if (forMSIL)  getClass("System.IConvertible")
                           else getClass("java.lang.Number")
    lazy val BoxedUnitModule = getModule("scala.runtime.BoxedUnit")
      def BoxedUnit_UNIT = getMember(BoxedUnitModule, "UNIT")
    lazy val ObjectRefClass = getClass("scala.runtime.ObjectRef")

    // special attributes
    lazy val SerializableAttr: Symbol = getClass("scala.serializable")
    lazy val DeprecatedAttr: Symbol = getClass("scala.deprecated")
    lazy val BeanPropertyAttr: Symbol = if (forCLDC || forMSIL) null else getClass("scala.reflect.BeanProperty")
    var AnnotationDefaultAttr: Symbol = _
    lazy val NativeAttr: Symbol = getClass("scala.native")
    lazy val VolatileAttr: Symbol = getClass("scala.volatile")

    def getModule(fullname: Name): Symbol = getModuleOrClass(fullname, true)

    def getClass(fullname: Name): Symbol = getModuleOrClass(fullname, false)

    def getMember(owner: Symbol, name: Name) = {
      val result = owner.info.nonPrivateMember(name)
      if (result == NoSymbol) {
        Console.println(owner.infosString)
        Console.println(owner.info.decls)
        throw new FatalError(owner.toString() + " does not have a member " + name)
      }
      result
    }

    private def getModuleOrClass(fullname: Name, module: Boolean): Symbol = {
      var sym = RootClass
      var i = 0
      var j = fullname.pos('.', i)
      while (j < fullname.length) {
        sym = sym.info.member(fullname.subName(i, j))
        i = j + 1
        j = fullname.pos('.', i)
      }
      val result =
        if (module) sym.info.member(fullname.subName(i, j)).suchThat(_ hasFlag MODULE)
        else sym.info.member(fullname.subName(i, j).toTypeName)
      if (result == NoSymbol) {
        if (settings.debug.value)
          { Console.println(sym.info); Console.println(sym.info.members) }//debug
        throw new FatalError((if (module) "object " else "class ") + fullname + " not found.")
      }
      result
    }

    private def newClass(owner: Symbol, name: Name, parents: List[Type]): Symbol = {
      val clazz = owner.newClass(NoPosition, name.toTypeName)
      clazz.setInfo(ClassInfoType(parents, newScope, clazz))
      owner.info.decls.enter(clazz)
      clazz
    }

    private def newCovariantPolyClass(owner: Symbol, name: Name, parent: Symbol => Type): Symbol = {
      val clazz = newClass(owner, name, List())
      val tparam = newTypeParam(clazz, 0) setFlag COVARIANT
      clazz.setInfo(
        PolyType(
          List(tparam),
          ClassInfoType(List(parent(tparam)), newScope, clazz)))
    }

    private def newAlias(owner: Symbol, name: Name, alias: Type): Symbol = {
      val tpsym = owner.newAliasType(NoPosition, name.toTypeName)
      tpsym.setInfo(alias)
      owner.info.decls.enter(tpsym)
      tpsym
    }

    private def newMethod(owner: Symbol, name: Name): Symbol = {
      val msym = owner.newMethod(NoPosition, name.encode)
      owner.info.decls.enter(msym)
      msym
    }

    private def newMethod(owner: Symbol, name: Name, formals: List[Type], restpe: Type): Symbol =
      newMethod(owner, name).setInfo(MethodType(formals, restpe))

    private def newPolyMethod(owner: Symbol, name: Name, tcon: Symbol => Type): Symbol = {
      val msym = newMethod(owner, name)
      val tparam = newTypeParam(msym, 0)
      msym.setInfo(PolyType(List(tparam), tcon(tparam)))
    }

    private def newParameterlessMethod(owner: Symbol, name: Name, restpe: Type) =
      newMethod(owner, name).setInfo(PolyType(List(),restpe))

    private def newTypeParam(owner: Symbol, index: Int): Symbol =
      owner.newTypeParameter(NoPosition, "T" + index)
        .setInfo(mkTypeBounds(AllClass.typeConstructor, AnyClass.typeConstructor))

    val boxedClass = new HashMap[Symbol, Symbol]
    val unboxMethod = new HashMap[Symbol, Symbol] // Type -> Method
    val boxMethod = new HashMap[Symbol, Symbol] // Type -> Method
    val boxedArrayClass = new HashMap[Symbol, Symbol]

    def isUnbox(m: Symbol) = m.name == nme.unbox && {
      m.tpe match {
        case MethodType(_, restpe) => (unboxMethod get restpe.typeSymbol) match {
          case Some(`m`) => true
          case _ => false
        }
        case _ => false
      }
    }

    /** Test whether a method symbol is that of a boxing method. */
    def isBox(m: Symbol) = (boxMethod.values contains m) && {
      m.tpe match {
        case MethodType(List(argtpe), _) => (boxMethod get argtpe.typeSymbol) match {
          case Some(`m`) => true
          case _ => false
        }
        case _ => false
      }
    }

    val refClass = new HashMap[Symbol, Symbol]
    private val abbrvTag = new HashMap[Symbol, Char]

    private def newValueClass(name: Name, tag: Char): Symbol = {
      val boxedName =
        if (!forMSIL) "java.lang." + (name match {
          case nme.Boolean => "Boolean"
          case nme.Byte => "Byte"
          case nme.Char => "Character"
          case nme.Short => "Short"
          case nme.Int => "Integer"
          case nme.Long => "Long"
          case nme.Float => "Float"
          case nme.Double => "Double"
        })
        else "System." + (name match {
          case nme.Boolean => "Boolean"
          case nme.Byte => "Byte"
          case nme.Char => "Char"
          case nme.Short => "Int16"
          case nme.Int => "Int32"
          case nme.Long => "Int64"
          case nme.Float => "Single"
          case nme.Double => "Double"
        })

      val clazz =
        newClass(ScalaPackageClass, name, List(AnyValClass.typeConstructor))
        .setFlag(ABSTRACT | FINAL)
      boxedClass(clazz) = getClass(boxedName)
      boxedArrayClass(clazz) = getClass("scala.runtime.Boxed" + name + "Array")
      refClass(clazz) = getClass("scala.runtime." + name + "Ref")
      abbrvTag(clazz) = tag

      val module = ScalaPackageClass.newModule(NoPosition, name)
      ScalaPackageClass.info.decls.enter(module)
      val mclass = module.moduleClass
      mclass.setInfo(ClassInfoType(List(), newScope, mclass))
      module.setInfo(mclass.tpe)

      val box = newMethod(mclass, nme.box, List(clazz.typeConstructor),
                          ObjectClass.typeConstructor)
      boxMethod(clazz) = box
      val unbox = newMethod(mclass, nme.unbox, List(ObjectClass.typeConstructor),
                            clazz.typeConstructor)
      unboxMethod(clazz) = unbox

      clazz
    }

    /** Sets-up symbols etc. for value classes, and their boxed versions. This
      * method is called once from within the body of init. */
    private def initValueClasses: Unit = {
      val booltype = BooleanClass.typeConstructor
      val boolparam = List(booltype)
      val bytetype = ByteClass.typeConstructor
      val byteparam = List(bytetype)
      val chartype = CharClass.typeConstructor
      val charparam = List(chartype)
      val shorttype = ShortClass.typeConstructor
      val shortparam = List(shorttype)
      val inttype = IntClass.typeConstructor
      val intparam = List(inttype)
      val longtype = LongClass.typeConstructor
      val longparam = List(longtype)

      val floattype = if (forCLDC) null else FloatClass.typeConstructor
      val floatparam = if (forCLDC) null else List(floattype)
      val doubletype = if (forCLDC) null else DoubleClass.typeConstructor
      val doubleparam = if (forCLDC) null else List(doubletype)

      val stringtype = StringClass.typeConstructor

      // init scala.Boolean
      newParameterlessMethod(BooleanClass, nme.UNARY_!, booltype)
      newMethod(BooleanClass, nme.EQ,   boolparam, booltype)
      newMethod(BooleanClass, nme.NE,   boolparam, booltype)
      newMethod(BooleanClass, nme.ZOR,  boolparam, booltype)
      newMethod(BooleanClass, nme.ZAND, boolparam, booltype)
      newMethod(BooleanClass, nme.OR,   boolparam, booltype)
      newMethod(BooleanClass, nme.AND,  boolparam, booltype)
      newMethod(BooleanClass, nme.XOR,  boolparam, booltype)

      def initValueClass(clazz: Symbol, isCardinal: Boolean): Unit = {
        assert (clazz ne null)

        def addBinops(params: List[Type], restype: Type, isCardinal: Boolean) = {
          newMethod(clazz, nme.EQ,  params, booltype)
          newMethod(clazz, nme.NE,  params, booltype)
          newMethod(clazz, nme.LT,  params, booltype)
          newMethod(clazz, nme.LE,  params, booltype)
          newMethod(clazz, nme.GT,  params, booltype)
          newMethod(clazz, nme.GE,  params, booltype)
          newMethod(clazz, nme.ADD, params, restype)
          newMethod(clazz, nme.SUB, params, restype)
          newMethod(clazz, nme.MUL, params, restype)
          newMethod(clazz, nme.DIV, params, restype)
          newMethod(clazz, nme.MOD, params, restype)
          if (isCardinal) {
            newMethod(clazz, nme.OR, params, restype)
            newMethod(clazz, nme.AND, params, restype)
            newMethod(clazz, nme.XOR, params, restype)
          }
        }

        // conversion methods
        newParameterlessMethod(clazz, nme.toByte,   bytetype)
        newParameterlessMethod(clazz, nme.toShort,  shorttype)
        newParameterlessMethod(clazz, nme.toChar,   chartype)
        newParameterlessMethod(clazz, nme.toInt,    inttype)
        newParameterlessMethod(clazz, nme.toLong,   longtype)

        if (!forCLDC) {
          newParameterlessMethod(clazz, nme.toFloat,  floattype)
          newParameterlessMethod(clazz, nme.toDouble, doubletype)
        }

        // def +(s: String): String
        newMethod(clazz, nme.ADD, List(stringtype), stringtype)

        val restype =
          if ((clazz eq LongClass) ||
              (clazz eq FloatClass) ||
              (clazz eq DoubleClass))
            clazz.typeConstructor
          else inttype

        // shift operations
        if (isCardinal) {
          newMethod(clazz, nme.LSL, intparam,  restype)
          newMethod(clazz, nme.LSL, longparam, restype)
          newMethod(clazz, nme.LSR, intparam,  restype)
          newMethod(clazz, nme.LSR, longparam, restype)
          newMethod(clazz, nme.ASR, intparam,  restype)
          newMethod(clazz, nme.ASR, longparam, restype)
        }

        // unary operations
        newParameterlessMethod(clazz, nme.UNARY_+, restype)
        newParameterlessMethod(clazz, nme.UNARY_-, restype)

        if (isCardinal) {
          newParameterlessMethod(clazz, nme.UNARY_~, restype)
        }

        // binary operations
        val restype2 = if (isCardinal) longtype else restype
        addBinops(byteparam,   restype,    isCardinal)
        addBinops(shortparam,  restype,    isCardinal)
        addBinops(charparam,   restype,    isCardinal)
        addBinops(intparam,    restype,    isCardinal)
        addBinops(longparam,   restype2,   isCardinal)
        if (!forCLDC) {
          val restype3 = if (clazz eq DoubleClass) doubletype else floattype
          addBinops(floatparam,  restype3,   false)
          addBinops(doubleparam, doubletype, false)
        }
      }

      initValueClass(ByteClass,   true)
      initValueClass(ShortClass,  true)
      initValueClass(CharClass,   true)
      initValueClass(IntClass,    true)
      initValueClass(LongClass,   true)
      if (!forCLDC) {
        initValueClass(FloatClass,  false)
        initValueClass(DoubleClass, false)
      }
      def addModuleMethod(clazz: Symbol, name: Name, value: Any) {
        val owner = clazz.linkedClassOfClass
        newParameterlessMethod(owner, name, mkConstantType(Constant(value)))
      }
      addModuleMethod(ByteClass,  "MinValue",  java.lang.Byte.MIN_VALUE)
      addModuleMethod(ByteClass,  "MaxValue",  java.lang.Byte.MAX_VALUE)
      addModuleMethod(ShortClass, "MinValue",  java.lang.Short.MIN_VALUE)
      addModuleMethod(ShortClass, "MaxValue",  java.lang.Short.MAX_VALUE)
      addModuleMethod(CharClass,  "MinValue",  java.lang.Character.MIN_VALUE)
      addModuleMethod(CharClass,  "MaxValue",  java.lang.Character.MAX_VALUE)
      addModuleMethod(IntClass,   "MinValue",  java.lang.Integer.MIN_VALUE)
      addModuleMethod(IntClass,   "MaxValue",  java.lang.Integer.MAX_VALUE)
      addModuleMethod(LongClass,  "MinValue",  java.lang.Long.MIN_VALUE)
      addModuleMethod(LongClass,  "MaxValue",  java.lang.Long.MAX_VALUE)

      if (!forCLDC) {
        addModuleMethod(FloatClass, "MinValue", -java.lang.Float.MAX_VALUE)
        addModuleMethod(FloatClass, "MaxValue",  java.lang.Float.MAX_VALUE)
        addModuleMethod(FloatClass, "Epsilon",   java.lang.Float.MIN_VALUE)
        addModuleMethod(FloatClass, "NaN",       java.lang.Float.NaN)
        addModuleMethod(FloatClass, "PositiveInfinity", java.lang.Float.POSITIVE_INFINITY)
        addModuleMethod(FloatClass, "NegativeInfinity", java.lang.Float.NEGATIVE_INFINITY)

        addModuleMethod(DoubleClass, "MinValue", -java.lang.Double.MAX_VALUE)
        addModuleMethod(DoubleClass, "MaxValue",  java.lang.Double.MAX_VALUE)
        addModuleMethod(DoubleClass, "Epsilon",   java.lang.Double.MIN_VALUE)
        addModuleMethod(DoubleClass, "NaN",       java.lang.Double.NaN)
        addModuleMethod(DoubleClass, "PositiveInfinity", java.lang.Double.POSITIVE_INFINITY)
        addModuleMethod(DoubleClass, "NegativeInfinity", java.lang.Double.NEGATIVE_INFINITY)
      }
    }

    /** Is symbol a value class? */
    def isValueClass(sym: Symbol): Boolean =
      (sym eq UnitClass) || (boxedClass contains sym)

    /** Is symbol a value class? */
    def isNumericValueClass(sym: Symbol): Boolean =
      (sym ne BooleanClass) && (boxedClass contains sym)

    def isValueType(sym: Symbol) =
      isValueClass(sym) || unboxMethod.contains(sym)

    /** Is symbol a value or array class? */
    def isUnboxedClass(sym: Symbol): Boolean =
      isValueType(sym) || sym == ArrayClass

    def signature(tp: Type): String = {
      def erasure(tp: Type): Type = tp match {
        case st: SubType => erasure(st.supertype)
        case RefinedType(parents, _) => erasure(parents.head)
        case _ => tp
      }
      def flatNameString(sym: Symbol, separator: Char): String =
        if (sym.owner.isPackageClass) sym.fullNameString('.')
        else flatNameString(sym.owner, separator) + "$" + sym.simpleName;
      def signature1(etp: Type): String = {
        if (etp.typeSymbol == ArrayClass) "[" + signature1(erasure(etp.normalize.typeArgs.head))
        else if (isValueClass(etp.typeSymbol)) abbrvTag(etp.typeSymbol).toString()
        else "L" + flatNameString(etp.typeSymbol, '/') + ";"
      }
      val etp = erasure(tp)
      if (etp.typeSymbol == ArrayClass) signature1(etp)
      else flatNameString(etp.typeSymbol, '.')
    }

    private var isInitialized = false

    def init {
      if (isInitialized) return
      isInitialized = true

      EmptyPackageClass.setInfo(ClassInfoType(List(), newScope, EmptyPackageClass))
      EmptyPackage.setInfo(EmptyPackageClass.tpe)
      RootClass.info.decls.enter(EmptyPackage)
      RootClass.info.decls.enter(RootPackage)

      AnyClass = newClass(ScalaPackageClass, nme.Any, List()).setFlag(ABSTRACT)
      val anyparam = List(AnyClass.typeConstructor)

      AnyValClass = newClass(ScalaPackageClass, nme.AnyVal, anyparam)
        .setFlag(FINAL | SEALED)
      AnyRefClass =
        newAlias(ScalaPackageClass, nme.AnyRef, ObjectClass.typeConstructor)

      AllRefClass = newClass(ScalaPackageClass, nme.Null, anyrefparam)
        .setFlag(ABSTRACT | TRAIT | FINAL)

      AllClass = newClass(ScalaPackageClass, nme.Nothing, anyparam)
        .setFlag(ABSTRACT | TRAIT | FINAL)

      SingletonClass = newClass(ScalaPackageClass, nme.Singleton, anyparam)
        .setFlag(ABSTRACT | TRAIT | FINAL)

      UnitClass =
        newClass(ScalaPackageClass, nme.Unit, List(AnyValClass.typeConstructor))
      abbrvTag(UnitClass) = 'V'

      BooleanClass = newValueClass(nme.Boolean, 'Z')
      ByteClass =    newValueClass(nme.Byte, 'B')
      ShortClass =   newValueClass(nme.Short, 'S')
      CharClass =    newValueClass(nme.Char, 'C')
      IntClass =     newValueClass(nme.Int, 'I')
      LongClass =    newValueClass(nme.Long, 'L')
      if (!forCLDC) {
        FloatClass =   newValueClass(nme.Float, 'F')
        DoubleClass =  newValueClass(nme.Double, 'D')
      }

      // the scala reference classes
      //ChannelClass = getClass("scala.distributed.Channel")
      //RemoteRefClass = getClass("scala.distributed.RemoteRef")
      if (!forCLDC && ! forMSIL) {
        CodeClass = getClass("scala.reflect.Code")
        CodeModule = getModule("scala.reflect.Code")
      }
      RepeatedParamClass = newCovariantPolyClass(
        ScalaPackageClass, nme.REPEATED_PARAM_CLASS_NAME,
        tparam => typeRef(SeqClass.typeConstructor.prefix, SeqClass, List(tparam.typeConstructor)))
      ByNameParamClass = newCovariantPolyClass(
        ScalaPackageClass, nme.BYNAME_PARAM_CLASS_NAME, tparam => AnyClass.typeConstructor)

      /* <unapply> */
      //UnsealedClass = getClass("scala.unsealed") //todo: remove once 2.4 is out.

      for (i <- 1 to MaxTupleArity) {
        TupleClass(i)   = getClass(  "scala.Tuple" + i)
      }
      for (i <- 1 to MaxProductArity) {
        ProductClass(i) = getClass("scala.Product" + i)
      }
      /* </unapply> */
      for (i <- 0 to MaxFunctionArity) {
        FunctionClass(i) = getClass("scala.Function" + i)
      }
      initValueClasses
      val booltype = BooleanClass.typeConstructor

      // members of class scala.Any
      Any_== = newMethod(AnyClass, nme.EQ, anyparam, booltype) setFlag FINAL
      Any_!= = newMethod(AnyClass, nme.NE, anyparam, booltype) setFlag FINAL
      Any_equals = newMethod(AnyClass, nme.equals_, anyparam, booltype)
      Any_hashCode = newMethod(
        AnyClass, nme.hashCode_, List(), IntClass.typeConstructor)
      Any_toString = newMethod(
        AnyClass, nme.toString_, List(), StringClass.typeConstructor)

      Any_isInstanceOf = newPolyMethod(
        AnyClass, nme.isInstanceOf_, tparam => booltype) setFlag FINAL
      Any_asInstanceOf = newPolyMethod(
        AnyClass, nme.asInstanceOf_, tparam => tparam.typeConstructor) setFlag FINAL
      Any_isInstanceOfErased = newPolyMethod(
        AnyClass, nme.isInstanceOfErased, tparam => booltype) setFlag FINAL
      //todo: do we need this?
      Any_asInstanceOfErased = newPolyMethod(
        AnyClass, nme.asInstanceOfErased, tparam => tparam.typeConstructor) setFlag FINAL

      // members of class java.lang.{Object, String}
      Object_== = newMethod(ObjectClass, nme.EQ, anyrefparam, booltype) setFlag FINAL
      Object_!= = newMethod(ObjectClass, nme.NE, anyrefparam, booltype) setFlag FINAL
      Object_eq = newMethod(ObjectClass, nme.eq, anyrefparam, booltype) setFlag FINAL
      Object_ne = newMethod(ObjectClass, "ne", anyrefparam, booltype) setFlag FINAL
      Object_synchronized = newPolyMethod(
        ObjectClass, nme.synchronized_,
        tparam => MethodType(List(tparam.typeConstructor), tparam.typeConstructor)) setFlag FINAL
      Object_isInstanceOf = newPolyMethod(
        ObjectClass, "$isInstanceOf",
        tparam => MethodType(List(), booltype)) setFlag FINAL
      Object_asInstanceOf = newPolyMethod(
        ObjectClass, "$asInstanceOf",
        tparam => MethodType(List(), tparam.typeConstructor)) setFlag FINAL
      String_+ = newMethod(
        StringClass, "+", anyparam, StringClass.typeConstructor) setFlag FINAL

      PatternWildcard = NoSymbol.newValue(NoPosition, "_").setInfo(AllClass.typeConstructor)

      if (forMSIL) {
        val intType = IntClass.typeConstructor;
        val intParam = List(intType);
        val longType = LongClass.typeConstructor;
        val charType = CharClass.typeConstructor;
        val unitType = UnitClass.typeConstructor;
        val stringType = StringClass.typeConstructor;
        val stringParam = List(stringType);

        // additional methods of Object
        newMethod(ObjectClass, "clone", List(), AnyRefClass.typeConstructor);
        newMethod(ObjectClass, "wait", List(), unitType);
        newMethod(ObjectClass, "wait", List(longType), unitType);
        newMethod(ObjectClass, "wait", List(longType, intType), unitType);
        newMethod(ObjectClass, "notify", List(), unitType);
        newMethod(ObjectClass, "notifyAll", List(), unitType);

        // additional methods of String
        newMethod(StringClass, "length", List(), intType);
        newMethod(StringClass, "compareTo", stringParam, intType);
        newMethod(StringClass, "charAt", intParam, charType);
        newMethod(StringClass, "concat", stringParam, stringType);
        newMethod(StringClass, "indexOf", intParam, intType);
        newMethod(StringClass, "indexOf", List(intType, intType), intType);
        newMethod(StringClass, "indexOf", stringParam, intType);
        newMethod(StringClass, "indexOf", List(stringType, intType), intType);
        newMethod(StringClass, "lastIndexOf", intParam, intType);
        newMethod(StringClass, "lastIndexOf", List(intType, intType), intType);
        newMethod(StringClass, "lastIndexOf", stringParam, intType);
        newMethod(StringClass, "lastIndexOf", List(stringType, intType), intType);
        newMethod(StringClass, "toLowerCase", List(), stringType);
        newMethod(StringClass, "toUpperCase", List(), stringType);
        newMethod(StringClass, "startsWith", stringParam, booltype);
        newMethod(StringClass, "endsWith", stringParam, booltype);
        newMethod(StringClass, "substring", intParam, stringType);
        newMethod(StringClass, "substring", List(intType, intType), stringType);
        newMethod(StringClass, "trim", List(), stringType);
        newMethod(StringClass, "intern", List(), stringType);
        newMethod(StringClass, "replace", List(charType, charType), stringType);
        newMethod(StringClass, "toCharArray", List(),
                  appliedType(ArrayClass.typeConstructor, List(charType)));

        // Delegate_scalaCallerInfos = new HashMap()
        Delegate_scalaCallerTargets = new HashMap()
      }

      AnnotationDefaultAttr = newClass(RootClass,
                                       nme.AnnotationDefaultATTR,
                                       List(AnnotationClass.typeConstructor))
    }

    var nbScalaCallers: Int = 0
    def newScalaCaller(delegateType: Type): Symbol = {
      assert(forMSIL, "scalaCallers can only be created if target is .NET")
      // object: reference to object on which to call (scala-)metod
      val paramTypes: List[Type] = List(ObjectClass.tpe)
      val name: String =  "$scalaCaller$$" + nbScalaCallers
      // tparam => resultType, which is the resultType of PolyType, i.e. the result type after applying the
      // type parameter =-> a MethodType in this case
      // TODO: set type bounds manually (-> MulticastDelegate), see newTypeParam
      val newCaller = newMethod(DelegateClass, name, paramTypes, delegateType) setFlag (FINAL | STATIC)
      // val newCaller = newPolyMethod(DelegateClass, name,
      // tparam => MethodType(paramTypes, tparam.typeConstructor)) setFlag (FINAL | STATIC)
      Delegate_scalaCallers = Delegate_scalaCallers ::: List(newCaller)
      nbScalaCallers += 1
      newCaller
    }

    // def addScalaCallerInfo(scalaCaller: Symbol, methSym: Symbol, delType: Type) = {
    // assert(Delegate_scalaCallers contains scalaCaller)
    // Delegate_scalaCallerInfos += scalaCaller -> (methSym, delType)
    // }

    def addScalaCallerInfo(scalaCaller: Symbol, methSym: Symbol) = {
      assert(Delegate_scalaCallers contains scalaCaller)
      Delegate_scalaCallerTargets += scalaCaller -> methSym
    }
  }
}
