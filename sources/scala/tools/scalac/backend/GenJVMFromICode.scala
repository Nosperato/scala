/*     ____ ____  ____ ____  ______                                     *\
**    / __// __ \/ __// __ \/ ____/    SOcos COmpiles Scala             **
**  __\_ \/ /_/ / /__/ /_/ /\_ \       (c) 2002, LAMP/EPFL              **
** /_____/\____/\___/\____/____/                                        **
\*                                                                      */

// $Id:

import scalac.symtab._;
import scalac.{Global => scalac_Global};
import scalac.atree._;
import scalac.Unit;
import scalac.util.Debug;
import scala.tools.scalac.icode._;
import ch.epfl.lamp.fjbg._;
import scala.collection.mutable.HashMap;

import java.util.StringTokenizer;
import java.io.File;

package scala.tools.scalac.backend {

/* This class implements the backend which create
 * Java Virtual Machine's bytecode with
 * The Intermediate Code of the compiler */
class GenJVMFromICode(global: scalac_Global) {

  // ##################################################
  // Private fields - Utils
  
  private val defs = global.definitions;
  
  private val fjbgContext = new FJBGContext();
  
  private val typer = new ATreeTyper(global);
  
  // ##################################################
  // Private fields - Data
  private var currentSrcFileName: String = null;
  
  private val clasz = new HashMap[Symbol, JVMClass];
  
  private val typeMap = new HashMap[Symbol, JType];

  // ##################################################
  // Constructor code
  initTypeMap;
  
  // ##################################################
  // Public methods

  /* This method generates byte code for a single unit */
  def translate(unit: Unit) = {
    global.log("Jvm.translate() called");
    currentSrcFileName = unit.source.toString();
    // Generate the structure
    val classes_it = new IterableArray(unit.repository.classes()).elements;
    classes_it.foreach(genClass(null));
    dumpStructure;
    // Generate the code & Save the classes
    var pairs_it = clasz.elements;
    pairs_it.foreach ((p: Pair[Symbol, JVMClass]) => {
      val sym = p._1;
      val jvmClass = p._2;
      val methods_it = jvmClass.methods.values;
      global.log("Jvm.translate: translating class "+sym);
      methods_it.foreach ((m: JVMMethod) => if(!m.aMethod.isAbstract()) genCode(m));
      //jvmClass.jClass.writeTo("/tmp/"+javaName(sym)); // tmp
    });
    pairs_it = clasz.elements; // Remettre joli quand ca marche !!!
    pairs_it.foreach ((p: Pair[Symbol, JVMClass]) => {
      val sym = p._1;
      val jvmClass = p._2;
      val fileName = javaFileName(javaName(sym));
      global.log("Jvm.translate: writing class "+sym);
      jvmClass.jClass.writeTo(fileName);
    });
    global.operation("Generation of "+currentSrcFileName+" succeded.");
   
    currentSrcFileName = null;
  }

  // ##################################################
  // Private methods - Generate code

  /* Generate a class */
  private def genClass (parent: JVMClass)(aClass: AClass) : unit = {
    // 1. ##### Create JClass object
    val sym : Symbol = aClass.symbol();
    val thisClassName : String = javaName(sym); 
    val flags : int = JAccessFlags.ACC_SUPER | // The way it has to be
		      (if (aClass.isPublic()) JAccessFlags.ACC_PUBLIC else 0) |
		      (if (aClass.isPrivate()) JAccessFlags.ACC_PRIVATE else 0) |
		      (if (aClass.isProtected()) JAccessFlags.ACC_PROTECTED else 0) |
		      (if (aClass.isFinal()) JAccessFlags.ACC_FINAL else 0) |
		      (if (aClass.isAbstract()) JAccessFlags.ACC_ABSTRACT else 0) |
		      (if (aClass.isInterface()) JAccessFlags.ACC_INTERFACE else 0);
    
    global.log("genClass: parents(): ");
    val prnt_it = new IterableArray(aClass.parents()).elements; // debug
    prnt_it.foreach((t: Type) => {global.log("  "+t.toString())}); // debug
  
    val baseTps = new IterableArray(aClass.parents()).elements;
    assert (baseTps.hasNext, "Jvm::genClass: Invalid number of parents. "+Debug.show(sym));
    
    var superClassName : String = null;
    
    val firstParent : Type = aClass.parents()(0);
    //var aParentType : Type = baseTps.next;
    global.log("first parent: "+firstParent); // Debug
    if (aClass.isInterface()) {
      if (firstParent.isSameAs(defs.ANY_TYPE())) {
	global.log("test ANY succeded for interface. l112");
	baseTps.drop(1);
      }
      superClassName = JAVA_LANG_OBJECT;
    } else {
      superClassName = javaName(baseTps.next.symbol());
    }

    var interfaceNames_l : List[String] = Nil;
    
    baseTps.foreach((aParentType : Type) => 
      interfaceNames_l = javaName(aParentType.symbol())::interfaceNames_l);
    val interfaceNames_a = new Array[String](interfaceNames_l.length);
    interfaceNames_l.reverse.copyToArray(interfaceNames_a,0);
    
    val jclass : JClass = fjbgContext.JClass(flags,
					     thisClassName,
					     superClassName,
					     interfaceNames_a,
					     currentSrcFileName);
    
    // 2. ##### Modify context:: Enter class
    //currentClass = aClass;
    val jvmClass = new JVMClass(parent);
    clasz += aClass.symbol() -> jvmClass;
    jvmClass.jClass = jclass;
    //classFields = new HashMap[Symbol, JField]();

    // 3. ##### Access the inner classes
    // !!! Acces aux champs extérieurs
    val classes_it = new IterableArray(aClass.classes()).elements;
    classes_it.foreach(genClass(jvmClass));

    // 4. ##### Add fields of the class
    //genFields(aClass, jClass);
    
    val fields_it = new IterableArray(aClass.fields()).elements;
    fields_it.foreach(genField(jvmClass));
    
    // ICI -> Faut-il s'occuper du truc module ? (case ClassDef)
    
    // 5. ##### Enregistre les methodes
    val methods_it = new IterableArray(aClass.methods()).elements;
    global.log("  number of methods: "+aClass.methods().length); // Debug
    methods_it.foreach(genMethod(jvmClass));

    // ##### Modify context:: Leave class
    //currentClass = null;
    //classFields = null;
  }
  
  /* Add a field to a class */
  private def genField(jvmClass : JVMClass)(aField : AField) : unit = {
    //val fields_it = new IterableArray(aClass.fields()).elements;
  
    //fileds_it.foreach(aField: AField => {
      //aField = fields_it.next;
    val flags = (if (aField.isPublic()) JAccessFlags.ACC_PUBLIC else 0) |
		(if (aField.isPrivate()) JAccessFlags.ACC_PRIVATE else 0) |
		(if (aField.isProtected()) JAccessFlags.ACC_PROTECTED else 0) |
		(if (aField.isStatic()) JAccessFlags.ACC_STATIC else 0) |
		(if (aField.isFinal()) JAccessFlags.ACC_FINAL else 0) |
		(if (aField.isVolatile()) JAccessFlags.ACC_VOLATILE else 0) |
		(if (aField.isTransient()) JAccessFlags.ACC_TRANSIENT else 0);
    jvmClass.fields += aField.symbol() -> 
    jvmClass.jClass.addNewField(flags,
				aField.symbol().name.toString(),
				typeStoJ(aField.symbol().info())); // Vérifier si name n'est pas plus simple
  }
    
  /* Generate a method */
  private def genMethod(jvmClass: JVMClass)(aMethod: AMethod): unit = {
    // 1. ##### Create JMethod object

    val flags = (if (aMethod.isPublic()) JAccessFlags.ACC_PUBLIC else 0) |
		(if (aMethod.isPrivate()) JAccessFlags.ACC_PRIVATE else 0) |
		(if (aMethod.isProtected()) JAccessFlags.ACC_PROTECTED else 0) |
		(if (aMethod.isStatic()) JAccessFlags.ACC_STATIC else 0) |
		(if (aMethod.isFinal()) JAccessFlags.ACC_FINAL else 0) |
		(if (aMethod.isNative()) JAccessFlags.ACC_NATIVE else 0) |
		(if (aMethod.isAbstract()) JAccessFlags.ACC_ABSTRACT else 0) |
		(if (aMethod.isStrictFP()) JAccessFlags.ACC_STRICT else 0);
    
    var argTypes_l : List[JType] = Nil;
    var argNames_l : List[String] = Nil;
    
    val vparams_it = new IterableArray(aMethod.vparams()).elements;
    vparams_it.foreach((sym: Symbol) => {
      argTypes_l = typeStoJ(sym.info())::argTypes_l;
      argNames_l = sym.name.toString()::argNames_l;
    });
    
    val argTypes_a = new Array[JType](argTypes_l.length);
    val argNames_a = new Array[String](argNames_l.length);
    argTypes_l.reverse.copyToArray(argTypes_a,0);
    argNames_l.reverse.copyToArray(argNames_a,0);
    val jMethod : JMethod = jvmClass.jClass.addNewMethod(flags,
							 aMethod.symbol().name.toString(),
							 typeStoJ(aMethod.result()),
							 argTypes_a,
							 argNames_a);
   
  
    // 2. ##### Modify context:: Enter method
    //currentMethod = aMethod;
    //methodLocals = new HashMap[Symbol, JLocalVariable];
    //methodArgs   = new HashMap[Symbol, int];
    val jvmMethod = new JVMMethod(aMethod, jvmClass);
    jvmClass.methods += aMethod.symbol() -> jvmMethod;
    jvmMethod.jMethod = jMethod;
   
    var index : int = 1;
    vparams_it.foreach((sym: Symbol) => {
      jvmMethod.args += sym -> index;
      index = index + 1;
    });

     if (! jvmMethod.aMethod.isAbstract()) {
      jvmMethod.jCode = jMethod.getCode().asInstanceOf[JExtendedCode];
       
      
       
       // 3. ##### Generate labels

       jvmMethod.aMethod.icode.asInstanceOf[ICode].icTraverse((bb : IBasicBlock) => {
	 val blockLabel : JCode$Label = jvmMethod.jCode.newLabel();
	 jvmMethod.labels += bb -> blockLabel;
       });
     }
    //if (!aMethod.isAbstract()) {
    //  genCode(aMethod.icode, jMethod.getCode());
    //}
		       
    // 3. ##### Modify context:: Leave method
    //currentMethod = null;
    //methodLocals = null;
  
  } 
  
  /* Translate code */
  private def genCode(jvmMethod : JVMMethod) = {
    val icode : ICode = jvmMethod.aMethod.icode.asInstanceOf[ICode];
    var stack : ICTypeStack = new ICTypeStack();
    val jCode = jvmMethod.jCode;
    icode.icTraverse((bb: IBasicBlock) => {
      val blockLabel = jvmMethod.labels.apply(bb);
      blockLabel.anchorToNext();
      bb.bbTraverse((ic : ICInstruction) => stack = emitICInstruction(jvmMethod, stack)(ic));
    });
  }
    
  /* Translate an ICInstruction to a JVM instruction */
  private def emitICInstruction(jvmMethod: JVMMethod, stack: ICTypeStack)(instruction: ICInstruction) : ICTypeStack = {
    val jcode = jvmMethod.jCode;
    instruction match {
      case THIS(_) => 
	jcode.emitALOAD_0();
      

      case CONSTANT(AConstant$BOOLEAN(v)) =>
	jcode.emitPUSH(v);
      case CONSTANT(AConstant$BYTE(v)) =>
	jcode.emitPUSH(v);
      case CONSTANT(AConstant$SHORT(v)) =>
	jcode.emitPUSH(v);
      case CONSTANT(AConstant$CHAR(v)) =>
	jcode.emitPUSH(v);
      case CONSTANT(AConstant$INT(v)) =>
	jcode.emitPUSH(v);
      case CONSTANT(AConstant$LONG(v)) =>
	jcode.emitPUSH(v);
      case CONSTANT(AConstant$FLOAT(v)) =>
	jcode.emitPUSH(v);
      case CONSTANT(AConstant$DOUBLE(v)) =>
	jcode.emitPUSH(v);
      case CONSTANT(AConstant$STRING(v)) =>
	jcode.emitPUSH(v);
      case CONSTANT(AConstant.NULL) =>
	jcode.emitACONST_NULL();
      case CONSTANT(AConstant.UNIT) =>
	; // ??
      case CONSTANT(AConstant.ZERO) =>
	; // ??
      
      case LOAD_ARRAY_ITEM() => {
	// depend the type of the elements of the array
	val elementType = typer.getArrayElementType(stack.tail.head);
	jcode.emitALOAD(typeStoJ(elementType));
      }

      case LOAD_LOCAL(local, false) =>
	jcode.emitLOAD(jvmMethod.locals.apply(local));

      case LOAD_LOCAL(local, true) =>
	jcode.emitLOAD(jvmMethod.args.apply(local), typeStoJ(local.getType()));

      case LOAD_FIELD(field, static) => {
	val className = javaName(field.owner());
	val fieldName = javaName(field);
	if (static)
	  jcode.emitGETSTATIC(className, fieldName, typeStoJ(field.getType()));
	else
	  jcode.emitGETFIELD(className, fieldName, typeStoJ(field.getType()));
      }

      case STORE_ARRAY_ITEM() =>
	jcode.emitASTORE(typeStoJ(stack.head));

      case STORE_LOCAL(local, false) => {
	val jLocal : JLocalVariable = 
	  if (jvmMethod.locals.contains(local))
	    jvmMethod.locals.apply(local);
	  else {
	    val newLocal = jvmMethod.jMethod.addNewLocalVariable(typeStoJ(local.getType()), javaName(local));
	    jvmMethod.locals += local -> newLocal;
	    newLocal;
	  }
	jcode.emitSTORE(jLocal);
      }

      case STORE_FIELD(field, static) => {
	val className = javaName(field.owner());
	val fieldName = javaName(field);
	if (static)
	  jcode.emitPUTSTATIC(className, fieldName, typeStoJ(field.getType()));
	else
	  jcode.emitPUTFIELD(className, fieldName, typeStoJ(field.getType()));
      }

      case CALL_PRIMITIVE(APrimitive$Negation(ATypeKind.I4)) => jcode.emitINEG();
      case CALL_PRIMITIVE(APrimitive$Negation(ATypeKind.I8)) => jcode.emitLNEG();
      case CALL_PRIMITIVE(APrimitive$Negation(ATypeKind.R4)) => jcode.emitFNEG();
      case CALL_PRIMITIVE(APrimitive$Negation(ATypeKind.R8)) => jcode.emitDNEG();

      //case CALL_PRIMITIVE(APrimitive$Test(*)) 
      // !! Regarder les Test

      //case CALL_PRIMITIVE(AComparisonOp(*))
      // !! Regarder les comparison

      case CALL_PRIMITIVE(APrimitive$Arithmetic(AArithmeticOp.ADD, ATypeKind.I4)) => jcode.emitIADD();
      case CALL_PRIMITIVE(APrimitive$Arithmetic(AArithmeticOp.ADD, ATypeKind.I8)) => jcode.emitLADD();
      case CALL_PRIMITIVE(APrimitive$Arithmetic(AArithmeticOp.ADD, ATypeKind.R4)) => jcode.emitFADD();
      case CALL_PRIMITIVE(APrimitive$Arithmetic(AArithmeticOp.ADD, ATypeKind.R8)) => jcode.emitDADD();

      case CALL_PRIMITIVE(APrimitive$Arithmetic(AArithmeticOp.SUB, ATypeKind.I4)) => jcode.emitISUB();
      case CALL_PRIMITIVE(APrimitive$Arithmetic(AArithmeticOp.SUB, ATypeKind.I8)) => jcode.emitLSUB();
      case CALL_PRIMITIVE(APrimitive$Arithmetic(AArithmeticOp.SUB, ATypeKind.R4)) => jcode.emitFSUB();
      case CALL_PRIMITIVE(APrimitive$Arithmetic(AArithmeticOp.SUB, ATypeKind.R8)) => jcode.emitDSUB();

      case CALL_PRIMITIVE(APrimitive$Arithmetic(AArithmeticOp.MUL, ATypeKind.I4)) => jcode.emitIMUL();
      case CALL_PRIMITIVE(APrimitive$Arithmetic(AArithmeticOp.MUL, ATypeKind.I8)) => jcode.emitLMUL();
      case CALL_PRIMITIVE(APrimitive$Arithmetic(AArithmeticOp.MUL, ATypeKind.R4)) => jcode.emitFMUL();
      case CALL_PRIMITIVE(APrimitive$Arithmetic(AArithmeticOp.MUL, ATypeKind.R8)) => jcode.emitDMUL();

      case CALL_PRIMITIVE(APrimitive$Arithmetic(AArithmeticOp.DIV, ATypeKind.I4)) => jcode.emitIDIV();
      case CALL_PRIMITIVE(APrimitive$Arithmetic(AArithmeticOp.DIV, ATypeKind.I8)) => jcode.emitLDIV();
      case CALL_PRIMITIVE(APrimitive$Arithmetic(AArithmeticOp.DIV, ATypeKind.R4)) => jcode.emitFDIV();
      case CALL_PRIMITIVE(APrimitive$Arithmetic(AArithmeticOp.DIV, ATypeKind.R8)) => jcode.emitDDIV();

      case CALL_PRIMITIVE(APrimitive$Arithmetic(AArithmeticOp.REM, ATypeKind.I4)) => jcode.emitIREM();
      case CALL_PRIMITIVE(APrimitive$Arithmetic(AArithmeticOp.REM, ATypeKind.I8)) => jcode.emitLREM();
      case CALL_PRIMITIVE(APrimitive$Arithmetic(AArithmeticOp.REM, ATypeKind.R4)) => jcode.emitFREM();
      case CALL_PRIMITIVE(APrimitive$Arithmetic(AArithmeticOp.REM, ATypeKind.R8)) => jcode.emitDREM();

      case CALL_PRIMITIVE(APrimitive$Logical(ALogicalOp.AND, ATypeKind.I4)) => jcode.emitIAND();
      case CALL_PRIMITIVE(APrimitive$Logical(ALogicalOp.AND, ATypeKind.BOOL)) => jcode.emitIAND(); // ??? is that true
      case CALL_PRIMITIVE(APrimitive$Logical(ALogicalOp.AND, ATypeKind.I8)) => jcode.emitLAND();

      case CALL_PRIMITIVE(APrimitive$Logical(ALogicalOp.OR, ATypeKind.I4)) => jcode.emitIOR();
      case CALL_PRIMITIVE(APrimitive$Logical(ALogicalOp.OR, ATypeKind.BOOL)) => jcode.emitIOR(); // ??? is that true
      case CALL_PRIMITIVE(APrimitive$Logical(ALogicalOp.OR, ATypeKind.I8)) => jcode.emitLOR();

      case CALL_PRIMITIVE(APrimitive$Logical(ALogicalOp.XOR, ATypeKind.I4)) => jcode.emitIXOR();
      case CALL_PRIMITIVE(APrimitive$Logical(ALogicalOp.XOR, ATypeKind.BOOL)) => jcode.emitIXOR(); // ??? is that true
      case CALL_PRIMITIVE(APrimitive$Logical(ALogicalOp.XOR, ATypeKind.I8)) => jcode.emitLXOR();

      case CALL_PRIMITIVE(APrimitive$Shift(AShiftOp.ASL, ATypeKind.I4)) => jcode.emitISHL();
      case CALL_PRIMITIVE(APrimitive$Shift(AShiftOp.ASL, ATypeKind.I8)) => jcode.emitLSHL();
      
      case CALL_PRIMITIVE(APrimitive$Shift(AShiftOp.ASR, ATypeKind.I4)) => jcode.emitISHR();
      case CALL_PRIMITIVE(APrimitive$Shift(AShiftOp.ASR, ATypeKind.I8)) => jcode.emitLSHR();
      
      case CALL_PRIMITIVE(APrimitive$Shift(AShiftOp.LSR, ATypeKind.I4)) => jcode.emitIUSHR();
      case CALL_PRIMITIVE(APrimitive$Shift(AShiftOp.LSR, ATypeKind.I8)) => jcode.emitLUSHR();

      case CALL_PRIMITIVE(APrimitive$Conversion(ATypeKind.I4, ATypeKind.I8)) => jcode.emitI2L();
      case CALL_PRIMITIVE(APrimitive$Conversion(ATypeKind.I4, ATypeKind.R4)) => jcode.emitI2F();
      case CALL_PRIMITIVE(APrimitive$Conversion(ATypeKind.I4, ATypeKind.R8)) => jcode.emitI2D();
      case CALL_PRIMITIVE(APrimitive$Conversion(ATypeKind.I8, ATypeKind.I4)) => jcode.emitL2I();
      case CALL_PRIMITIVE(APrimitive$Conversion(ATypeKind.I8, ATypeKind.R4)) => jcode.emitL2F();
      case CALL_PRIMITIVE(APrimitive$Conversion(ATypeKind.I8, ATypeKind.R8)) => jcode.emitL2D();
      case CALL_PRIMITIVE(APrimitive$Conversion(ATypeKind.R4, ATypeKind.I4)) => jcode.emitF2I();
      case CALL_PRIMITIVE(APrimitive$Conversion(ATypeKind.R4, ATypeKind.I8)) => jcode.emitF2L();
      case CALL_PRIMITIVE(APrimitive$Conversion(ATypeKind.R4, ATypeKind.R8)) => jcode.emitF2D();
      case CALL_PRIMITIVE(APrimitive$Conversion(ATypeKind.R8, ATypeKind.I4)) => jcode.emitD2I();
      case CALL_PRIMITIVE(APrimitive$Conversion(ATypeKind.R8, ATypeKind.I8)) => jcode.emitD2L();
      case CALL_PRIMITIVE(APrimitive$Conversion(ATypeKind.R8, ATypeKind.R4)) => jcode.emitD2F();
      case CALL_PRIMITIVE(APrimitive$Conversion(ATypeKind.I4, ATypeKind.I1)) => jcode.emitI2B();
      case CALL_PRIMITIVE(APrimitive$Conversion(ATypeKind.I4, ATypeKind.U2)) => jcode.emitI2C();
      case CALL_PRIMITIVE(APrimitive$Conversion(ATypeKind.I4, ATypeKind.I2)) => jcode.emitI2S();
      
      case CALL_PRIMITIVE(APrimitive$ArrayLength(_)) => jcode.emitARRAYLENGTH();

      case CALL_PRIMITIVE(APrimitive$StringConcat(_,_)) => 
	; // !!!

      case CALL_METHOD(method, style) => {
	var calledMethod : JMethod = null;
	/*
	var owner : JVMClass = method.owner;
	while (calledMethod == null && owner != null) do 
	  if (owner.methods.contains(method))
	    calledMethod = owner.methods.apply(method);
	  else
	    owner = owner.parent;
	*/ // Joli pour optimization ?
	val clasz_it = clasz.values;
	var aJvmClass : JVMClass = null;
	while (clasz_it.hasNext && calledMethod == null) {
	  aJvmClass = clasz_it.next;
	  if (aJvmClass.methods.contains(method))
	    calledMethod = aJvmClass.methods.apply(method).jMethod;
	}
	if (calledMethod != null) 
	  jcode.emitINVOKE(calledMethod); // et le style !!!
	else {
	  // cas normal
	  val methodName = method.name.toString();
	  val className = javaName(method.owner());
	  val methodType = typeStoJ(method.info()).asInstanceOf[JMethodType];
	  style match {
	    case AInvokeStyle.Dynamic =>
	      if (method.owner().isInterface())
		jcode.emitINVOKEINTERFACE(className, methodName, methodType);
	      else
		jcode.emitINVOKEVIRTUAL(className, methodName, methodType);
	    case AInvokeStyle.StaticInstance =>
	      jcode.emitINVOKESPECIAL(className, methodName, methodType);
	    case AInvokeStyle.StaticClass =>
	      jcode.emitINVOKESTATIC(className, methodName, methodType);
	  }
	}
      }

      case NEW(clasz) =>
	jcode.emitNEW(javaName(clasz));
      
      case CREATE_ARRAY(element) =>
	jcode.emitNEWARRAY(typeStoJ(element));
	
      case IS_INSTANCE(typ) =>
	jcode.emitINSTANCEOF(typeStoJ(typ).asInstanceOf[JReferenceType]);

      case CHECK_CAST(typ) =>
	jcode.emitCHECKCAST(typeStoJ(typ).asInstanceOf[JReferenceType]);

      case SWITCH(tags,blocks) => {
	val casesTags : List[Array[int]] = List.fromArray(tags,0,tags.length);
	val casesLabels = blocks.take(blocks.length-1);
	val defaultBranch = jvmMethod.labels(blocks.last);

	val tagsAndLabels = casesTags.zip(casesLabels);
	var keys_l : List[int] = Nil;
	var branches_l : List[JCode$Label] = Nil;

	tagsAndLabels.foreach ((p: Pair[Array[Int], IBasicBlock]) => {
	  val tags = p._1;
	  val label = jvmMethod.labels(p._2);
	  val tag_it = new IterableArray(tags).elements;
	  tag_it.foreach((tag: int) => {
	    keys_l = tag::keys_l;
	    branches_l = label::branches_l;
	  });
	});

	val keys_a = new Array[int](keys_l.length);
	val branches_a = new Array[JCode$Label](branches_l.length);
	keys_l.copyToArray(keys_a,0);
	branches_l.copyToArray(branches_a,0);

	jcode.emitSWITCH(keys_a, branches_a, defaultBranch, 15); // Min density = 15 ??? !!!
      }
      
      case JUMP(basicBlock) =>
	jcode.emitGOTO(jvmMethod.labels(basicBlock));

      case CJUMP(success, failure, cond) => {
	val condTag : int = condAtoJ(cond);
	val typ = typeStoJ(stack.head);
	if (typ.getTag() == JType.T_REFERENCE) 
	  jcode.emitIF_ACMP(condTag, jvmMethod.labels(success));
	else if (typ.getTag() == JType.T_INT)
	  jcode.emitIF_ICMP(condTag, jvmMethod.labels(success));
	// Tres tres bizarre !!! Et les autres cas ???
	jcode.emitGOTO(jvmMethod.labels.apply(failure)); // HA ha ha !
      }

      case CZJUMP(success, failure, cond) => {
	val condTag = condAtoJ(cond);
	jcode.emitIF(condTag, jvmMethod.labels.apply(success));
	jcode.emitGOTO(jvmMethod.labels.apply(failure)); // !!! Ha ha ha
      }

      case RETURN() => {
	if (stack.isEmpty)
	  //jcode.emitRETURN(); malheureusement ca ne marche pas
	  jcode.emitRETURN(JType.VOID); // mais ca oui
	else
	  jcode.emitRETURN(typeStoJ(stack.head));
      }
      
      case THROW() => jcode.emitATHROW;
      
      case DROP(typ) => { // A voir mieux
	// ??? On pourrait aussi regarder sur la pile
	val jtyp = typeStoJ(typ);
	val jtypTag : int = jtyp.getTag();
	if (jtyp.isObjectType() ||
	    jtyp.isReferenceType() ||
	    jtyp.isArrayType())
	  jcode.emitPOP();
	else
	  jtypTag match { // cf. VM spec 3.11.1
	    case JType.T_BOOLEAN => jcode.emitPOP();
	    case JType.T_CHAR => jcode.emitPOP();
	    case JType.T_BYTE => jcode.emitPOP();
	    case JType.T_SHORT => jcode.emitPOP();
	    case JType.T_INT => jcode.emitPOP();
	    case JType.T_FLOAT => jcode.emitPOP();
	    case JType.T_REFERENCE => jcode.emitPOP();
	    case JType.T_ADDRESS => jcode.emitPOP();
	    case JType.T_LONG => jcode.emitPOP2();
	    case JType.T_DOUBLE => jcode.emitPOP2();
	  }
      }

      case DUP(typ) => {
	val jtyp = typeStoJ(typ);
	val jtypTag : int = jtyp.getTag();
	if (jtyp.isObjectType() ||
	    jtyp.isReferenceType() ||
	    jtyp.isArrayType())
	  jcode.emitDUP();
	else
	  jtypTag match { // cf. VM spec 3.11.1
	    case JType.T_BOOLEAN => jcode.emitDUP();
	    case JType.T_CHAR => jcode.emitDUP();
	    case JType.T_BYTE => jcode.emitDUP();
	    case JType.T_SHORT => jcode.emitDUP();
	    case JType.T_INT => jcode.emitDUP();
	    case JType.T_FLOAT => jcode.emitDUP();
	    case JType.T_REFERENCE => jcode.emitDUP();
	    case JType.T_ADDRESS => jcode.emitDUP();
	    case JType.T_LONG => jcode.emitDUP2();
	    case JType.T_DOUBLE => jcode.emitDUP2();
	  }
      }

      case MONITOR_ENTER() => jcode.emitMONITORENTER();

      case MONITOR_EXIT()  => jcode.emitMONITOREXIT();
      
    }
    return stack.eval(instruction);
  }

  /* Translate an ATree Test operation to the FJBG type */
  private def condAtoJ(cond : ATestOp) : int = cond match {
    case ATestOp.EQ => JExtendedCode.COND_EQ;
    case ATestOp.NE => JExtendedCode.COND_NE;
    case ATestOp.GE => JExtendedCode.COND_GE;
    case ATestOp.LT => JExtendedCode.COND_LT;
    case ATestOp.LE => JExtendedCode.COND_LE;
    case ATestOp.GT => JExtendedCode.COND_GT;
  } 

  // ##################################################
  // Private methods - Debugging

  private def dumpStructure = {
    global.log("### Dumping structure ###");
    val sym_it = clasz.keys;
    sym_it.foreach((sym: Symbol) => {
      val jvmClass = clasz.apply(sym);
      global.log ("Classfile: "+javaName(sym));
      global.log ("/fileds:");
      val fields_it = jvmClass.fields.keys;
      fields_it.foreach((f: Symbol) => {
	global.log("  "+javaName(f));
      });
      global.log ("/methods:");
      val methods_it = jvmClass.methods.keys;
      methods_it.foreach((m: Symbol) => {
	global.log("  "+javaName(m));
      });
    });
    global.log("#########################");
  }

  //##################################################
  // Private methods & fields 
  // translated from GenJVM - M. Schinz

  private val JAVA_LANG_OBJECT = "java.lang.Object";
  
  private def initTypeMap = {
        typeMap += defs.ANY_CLASS     -> JObjectType.JAVA_LANG_OBJECT;
        typeMap += defs.ANYREF_CLASS  -> JObjectType.JAVA_LANG_OBJECT;
  }

  /**
  * Return a Java-compatible version of the name of the given
  * symbol. The returned name is mangled and includes the names of
  * the owners.
  */
  private def javaName(theSym: Symbol) = {
    var sym = theSym;
    sym match {
      case defs.ANY_CLASS    => JAVA_LANG_OBJECT;
      case defs.ANYREF_CLASS => JAVA_LANG_OBJECT;
      case _                 => {
	val buf = new StringBuffer(sym.name.toString());
	  if ((sym.isModule() || sym.isModuleClass()) && !sym.isJava())
	    buf.append('$');
	sym = sym.owner(); 
	while (!sym.isPackage()) {
	  buf.insert(0,'$');
	  buf.insert(0,sym.name);
	  sym = sym.owner();
	}
	if (!sym.isRoot()) {
	    buf.insert(0, '.');
	buf.insert(0, sym.fullName());
	}
	buf.toString();
      }
    }
  }

    /**
    * Return the name of the file in which to store the given class.
  */
  private def javaFileName(className : String) = {
    val tokens = new StringTokenizer(className, " ");
    var file = new File(global.outpath);
    while (tokens.hasMoreElements()) {
      file = new File(file, tokens.nextToken());
    }
    file.getPath()+".class";
  }

  /**
  * Return the Java type corresponding to the given Scala type.
  */
  private def typeStoJ(tp: Type) : JType = tp match {
    case Type$UnboxedType(TypeTags.BYTE)         => JType.BYTE;
    case Type$UnboxedType(TypeTags.CHAR)         => JType.CHAR;
    case Type$UnboxedType(TypeTags.SHORT)        => JType.SHORT;
    case Type$UnboxedType(TypeTags.INT)          => JType.INT;
    case Type$UnboxedType(TypeTags.LONG)         => JType.LONG;
    case Type$UnboxedType(TypeTags.FLOAT)        => JType.FLOAT;
    case Type$UnboxedType(TypeTags.DOUBLE)       => JType.DOUBLE;
    case Type$UnboxedType(TypeTags.BOOLEAN)      => JType.BOOLEAN;
    case Type$UnboxedType(TypeTags.UNIT)         => JType.VOID;
    case Type$UnboxedType(TypeTags.STRING)       => JObjectType.JAVA_LANG_STRING;
    case Type$UnboxedArrayType(elementType)      => new JArrayType(typeStoJ(elementType));
    
    case Type$MethodType(vparams: Array[Symbol], result: Type) => {
      val argTypes_a = new Array[JType](vparams.length);
      val vparams_it = new IterableArray(vparams).elements;
      //val argTypes_it = vparams_it.map((s: Symbol) => typeStoJ(s.info()));
      var argTypes_l : List[JType] = Nil;
      vparams_it.foreach((s: Symbol) => argTypes_l = typeStoJ(s.info())::argTypes_l);
      argTypes_l.reverse.copyToArray(argTypes_a,0);
      new JMethodType(typeStoJ(result), argTypes_a);
    }

    case _ => {
      val sym = tp.symbol();
      if (sym == Symbol.NONE)
	throw global.fail("invalid type ",tp);
      else if (typeMap.contains(sym))
	typeMap.apply(sym).asInstanceOf[JType];
      else {
	val jTp = new JObjectType(javaName(sym));
	typeMap += sym -> jTp;
	jTp;
      }
    }
  }
}


//##################################################
// Data structures

  /* This class represents a Class Context */
class JVMClass(theParent: JVMClass) {

  /* Methods of the class */
  val methods = new HashMap[Symbol, JVMMethod];

  /* Fields of the class */
  val fields = new HashMap[Symbol, JField];

  /* The JClass object corresponding of this class */
  var jClass : JClass = null;

  /* The inner classes of the class */
  var innerClasses : HashMap[Symbol, JVMClass] = null;

  /* The parent class of the class */
  val parent : JVMClass = theParent
}

  /* This class represents a Method context */
class JVMMethod(theAMethod : AMethod, theOwner : JVMClass){

  /* The ATree object representing this method */
  val aMethod : AMethod = theAMethod;

  /* The FJBG's object representing this method */
  var jMethod : JMethod = null;

  /* The locals variables of the method */
  val locals = new HashMap[Symbol, JLocalVariable];

  /* The arguments of the method */
  val args = new HashMap[Symbol, int];

  /* The JVM code of the method */
  var jCode : JExtendedCode = null;

  /* The owner class of the method */
  val owner : JVMClass = theOwner;

  /* The labels of the IBasicBlock's composing the ICode */
  val labels = new HashMap[IBasicBlock, JCode$Label];
}
  }
