/*     ____ ____  ____ ____  ______                                     *\
**    / __// __ \/ __// __ \/ ____/    SOcos COmpiles Scala             **
**  __\_ \/ /_/ / /__/ /_/ /\_ \       (c) 2002, LAMP/EPFL              **
** /_____/\____/\___/\____/____/                                        **
\*                                                                      */

// $Id$

package scalac.backend.msil;

import scalac.Global;
import scalac.ApplicationError;
import scalac.ast.Tree;
import scalac.util.Debug;
import scalac.util.Name;
import scalac.util.Names;
import scalac.symtab.Kinds;
import scalac.symtab.TypeTags;
import scalac.symtab.Symbol;
import scalac.symtab.Scope;
import scalac.symtab.Modifiers;
import scalac.symtab.Definitions;

import Tree.*;

import ch.epfl.lamp.compiler.msil.*;
import ch.epfl.lamp.compiler.msil.emit.*;
//import ch.epfl.lamp.compiler.msil.util.VJSAssembly;

import java.util.Map;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Set;
import java.util.LinkedHashSet;

/** 
 * Creates System.Reflection objects corresponding to
 * Scala symbols 
 *
 * @author Nikolay Mihaylov
 * @version 1.1
 */

final class TypeCreator {

    final private GenMSIL gen;
    final private Global global;
    final private Definitions defs;

    final private ArrayList typeBuilders = new ArrayList();

    final private Map types2symbols;
    final private Map symbols2types;
    final private Map symbols2fields;
    final private Map symbols2methods;
    final private Map symbols2moduleFields;

    static final String MODULE_S = "$MODULE";

    static final Type BYTE    = Type.GetType("System.SByte");
    static final Type CHAR    = Type.GetType("System.Char");
    static final Type SHORT   = Type.GetType("System.Int16");
    static final Type INT     = Type.GetType("System.Int32");
    static final Type LONG    = Type.GetType("System.Int64");
    static final Type FLOAT   = Type.GetType("System.Single");
    static final Type DOUBLE  = Type.GetType("System.Double");
    static final Type BOOLEAN = Type.GetType("System.Boolean");
    static final Type VOID    = Type.GetType("System.Void");

    static final Type SYSTEM_OBJECT = Type.GetType("System.Object");
    static final Type SYSTEM_STRING = Type.GetType("System.String");
    static final Type STRING_ARRAY = Type.GetType("System.String[]");

    static final Type MONITOR = Type.GetType("System.Threading.Monitor");

    static final MethodInfo CONCAT_OBJECT =
	SYSTEM_STRING.GetMethod("Concat", new Type[] {SYSTEM_OBJECT});
    static final MethodInfo CONCAT_STRING_STRING =
	SYSTEM_STRING.GetMethod("Concat", new Type[] {SYSTEM_STRING, SYSTEM_STRING});
    static final MethodInfo CONCAT_OBJECT_OBJECT =
	SYSTEM_STRING.GetMethod("Concat", new Type[] {SYSTEM_OBJECT, SYSTEM_OBJECT});

    static final MethodInfo OBJECT_EQUALS =
	SYSTEM_OBJECT.GetMethod("Equals", new Type[] {SYSTEM_OBJECT});

    static final MethodInfo MONITOR_PULSE =
	MONITOR.GetMethod("Pulse", new Type[] {SYSTEM_OBJECT});

    static final MethodInfo MONITOR_PULSE_ALL =
	MONITOR.GetMethod("PulseAll", new Type[] {SYSTEM_OBJECT});

    static final MethodInfo MONITOR_WAIT =
	MONITOR.GetMethod("Wait", new Type[] {SYSTEM_OBJECT});

    static final MethodInfo MONITOR_WAIT_TIMEOUT =
	MONITOR.GetMethod("Wait", new Type[] {SYSTEM_OBJECT, INT});

    
    final Type SCALA_BYTE;
    final Type SCALA_SHORT;
    final Type SCALA_INT;
    final Type SCALA_LONG;
    final Type SCALA_FLOAT;
    final Type SCALA_DOUBLE;
    final Type SCALA_CHAR;
    final Type SCALA_BOOLEAN;
    final Type SCALA_UNIT;

//     final Assembly VJSLIB;
//     final Assembly MSCORLIB;
//     final Assembly SCALA;

    TypeCreator(GenMSIL gen, GenMSILPhase phase) {
	this.gen = gen;
	this.global = gen.global;
	this.defs = global.definitions;

	Assembly.LoadFrom("/home/mihaylov/proj/msil/test/vjslib.dll").GetModules();
	Assembly.LoadFrom("/home/mihaylov/proj/vs.net/scala/bin/Debug/scala.dll").GetModules();

	types2symbols = phase.types2symbols;
	symbols2types = phase.symbols2types;
	symbols2fields = phase.symbols2fields;
	symbols2methods = phase.symbols2methods;
	symbols2moduleFields = phase.symbols2moduleFields;

	map(defs.ANY_CLASS, SYSTEM_OBJECT);
	map(defs.ANYREF_CLASS, SYSTEM_OBJECT);
	map(defs.JAVA_OBJECT_CLASS, SYSTEM_OBJECT);
	map(defs.JAVA_STRING_CLASS, SYSTEM_STRING);

	translateMethod(defs.JAVA_OBJECT_CLASS, "equals",
			SYSTEM_OBJECT, "Equals");
	translateMethod(defs.JAVA_OBJECT_CLASS, "hashCode",
			SYSTEM_OBJECT, "GetHashCode");
	translateMethod(defs.JAVA_OBJECT_CLASS, "toString",
			SYSTEM_OBJECT, "ToString");
	translateMethod(defs.JAVA_OBJECT_CLASS, "finalize",
			SYSTEM_OBJECT, "Finalize");

	translateMethod(defs.JAVA_STRING_CLASS, "equals",
			SYSTEM_STRING, "Equals");
	translateMethod(defs.JAVA_STRING_CLASS, "toString",
			SYSTEM_STRING, "ToString");
	translateMethod(defs.JAVA_STRING_CLASS, "startsWith",
			SYSTEM_STRING, "StartsWith");
	translateMethod(defs.JAVA_STRING_CLASS, "length",
			SYSTEM_STRING, "get_Length");
	translateMethod(defs.JAVA_STRING_CLASS, "charAt",
			SYSTEM_STRING, "get_Chars");
	translateMethod(defs.JAVA_STRING_CLASS, "substring",
			SYSTEM_STRING, "Substring");

	//generate mappings for the methods of System.Threading.Monitor
	final Type[] OBJECT_1 = new Type[] {SYSTEM_OBJECT};
 	final scalac.symtab.Type UNBOXED_LONG =
 	    new scalac.symtab.Type.UnboxedType(TypeTags.LONG);

	translateMethod(defs.JAVA_OBJECT_CLASS, "wait",
			scalac.symtab.Type.EMPTY_ARRAY,
			MONITOR, "Wait", OBJECT_1);
	translateMethod(defs.JAVA_OBJECT_CLASS, "wait",
			new scalac.symtab.
			Type[] {UNBOXED_LONG/*defs.LONG_TYPE*/},
			MONITOR, "Wait",
			new Type[] {SYSTEM_OBJECT, INT});
	translateMethod(defs.JAVA_OBJECT_CLASS, "notify",
			scalac.symtab.Type.EMPTY_ARRAY,
			MONITOR, "Pulse", OBJECT_1);
	translateMethod(defs.JAVA_OBJECT_CLASS, "notifyAll",
			scalac.symtab.Type.EMPTY_ARRAY,
			MONITOR, "PulseAll", OBJECT_1);

	final Type ObjectImpl = Type.GetType("com.ms.vjsharp.lang.ObjectImpl");
	//final MethodInfo getClass = ObjectImpl.GetMethod("getClass", OBJECT_1);

	translateMethod(defs.JAVA_OBJECT_CLASS, "getClass",
			scalac.symtab.Type.EMPTY_ARRAY,
			ObjectImpl, "getClass", OBJECT_1);

	SCALA_BYTE    = getType("scala.Byte");
	SCALA_SHORT   = getType("scala.Short");
	SCALA_INT     = getType("scala.Int");
	SCALA_LONG    = getType("scala.Long");
	SCALA_FLOAT   = getType("scala.Float");
	SCALA_DOUBLE  = getType("scala.Double");
	SCALA_CHAR    = getType("scala.Char");
	SCALA_BOOLEAN = getType("scala.Boolean");
	SCALA_UNIT    = getType("scala.Unit");

    }

    // looks up a method according to the signature
    Symbol lookupMethod(Symbol clazz, String name, 
			scalac.symtab.Type[] paramTypes)
    {
	Symbol[] methods = clazz.members().
	    lookup(Name.fromString(name)).alternativeSymbols();

// 	System.out.print("lookupMethod: Trying to match method " + name + "(");
// 	for (int i = 0; i < paramTypes.length; i++) {
// 	    if (i > 0) System.out.print(", ");
// 	    System.out.print(paramTypes[i]);
// 	}
// 	System.out.println(")");
// 	System.out.println("against members of class " + clazz + ":");

	search:
	for (int i = 0; i < methods.length; i++) {
//	    System.out.print(Debug.show(methods[i]));
	    switch (methods[i].info()) {
	    case MethodType(Symbol[] vparams, _):
// 		System.out.print("(");
// 		for (int j = 0; j < vparams.length; j++) {
// 		    if (j > 0) System.out.print(", ");
// 		    System.out.print(vparams[j] + ": "
// 				     + Debug.show(vparams[j].info()));
// 		}
// 		System.out.println("): " + Debug.show(methods[i].info()));

		if (paramTypes.length != vparams.length)
		    continue;
		for (int j = 0; j < vparams.length; j++) {
		    if (!paramTypes[j].equals(vparams[j].info()))
			continue search;
		}
		return methods[i];
	    default:
		continue;
	    }
	}
	return null;
    }

    // create mapping between the specified two methods only
    void translateMethod(Symbol clazz, String name,
			 scalac.symtab.Type[] paramTypes,
			 Type newClazz, String newName, Type[] newParamTypes)
    {
	Symbol sym = lookupMethod(clazz, name, paramTypes);
	assert sym != null : "Cannot find method: " + name + " in class " +
	    dumpSym(clazz);
	//System.out.println("translate2staticMethod:");
	//System.out.println("\told method: " + dumpSym(sym));
	MethodInfo method = newClazz.GetMethod(newName, newParamTypes);
	symbols2methods.put(sym, method);
	//System.out.println("\tnew method: " + method);
    }

    // look up the method and then create the mapping
    void translateMethod(Symbol clazz, String name,
		    Type newClazz, String newName)
    {
	Symbol sym = clazz.lookup(Name.fromString(name));
	assert sym != null : "Cannot find method: " + name;
	translateMethod(sym, newClazz, newName);
    }

    // create a mapping for the two methods
    void translateMethod(Symbol sym, Type newClazz, String newName) {
	switch (sym.info()) {
	case MethodType(Symbol[] vparams, scalac.symtab.Type result):
	    Type[] params = new Type[vparams.length];
	    for (int i = 0; i < params.length; i++)
		params[i] = getType(vparams[i]);
	    MethodInfo method = newClazz.GetMethod(newName, params);
	    symbols2methods.put(sym, method);
	    break;
	case OverloadedType(Symbol[] alts, _):
	    for (int i = 0; i < alts.length; i++)
		translateMethod(alts[i], newClazz, newName);
	    return;
	default:
	    global.fail("" + Debug.show(sym.info()));
	}
    }


    /** Finilizes ('bakes') the newly created types
     */
    public void createTypes() {
	Iterator iter = typeBuilders.iterator();
	while (iter.hasNext())
	    ((TypeBuilder)iter.next()).CreateType();
    }


    // creates bidirectional mapping from symbols to types
    public void map(Symbol sym, Type type) {
	symbols2types.put(sym, type);
	if (sym.isClass())
	    types2symbols.put(type, sym);
    }

    Symbol getSymbol(Type t) {
	return (Symbol) types2symbols.get(t);
    }

//     public Type getType(Symbol sym) {
// 	Type type = null;
// 	try { type = getType2(sym); }
// 	catch (Throwable e) {
// 	    log("getType: Exception cought for " + dumpSym(sym));
// 	    //log("-->symbol.type() = " + Debug.show(sym.type()));
// 	    //log("-->symbol.info()" + Debug.show(sym.info()));
// 	    if (global.debug) e.printStackTrace();
// 	}
// 	return type;
//     }


    /**
     * Return the System.Type object with the given name
     */
    Type getType(String name) {
	return Type.GetType(name);
    }

    /**
     * Return the System.Type object corresponding to the type of the symbol
     */
    Type getType(Symbol sym) {
	if (sym == null) return null;
	Type type = (Type) symbols2types.get(sym);
	if (type != null)
	    return type;
	if (sym.isJava())
	    type = getType(sym.fullNameString());
	else {
	    switch (sym.info()) {
	    case CompoundType(_, _):
		String fullname = sym.type().symbol().fullNameString() +
		    (sym.isModuleClass() ? "$" : "");;
		
		type = Type.GetType(fullname);
		if (type == null)
		    type = createType(sym);
		break;
		
 	    case UnboxedArrayType(scalac.symtab.Type elemtp):
// 		// force the creation of the type
// 		log("UnboxedArrayType: " + elemtp + "[]");
// 		getType(elemtp.symbol());
// 		Type t = Type.GetType(elemtp + "[]");
// 		log("Array type: " + t);
// 		return t;
		type = getTypeFromType(sym.info());
		break;

	    default:
		//log("getType: Going through the type: " + dumpSym(sym));
		type = getTypeFromType(sym.type());
	    }
	}
	assert type != null : "Unable to find type: " + dumpSym(sym);
	map(sym, type);
	return type;
    }


    /** Creates a  TypeBuilder object corresponding to the symbol
     */
    public TypeBuilder createType(Symbol sym) {
	TypeBuilder type = (TypeBuilder)symbols2types.get(sym);
	assert type == null : "Type " + type +
	    " already defined for symbol: " + dumpSym(sym);


	//log("TypeCreator.getType: creating type for " + dumpSym(sym));
	final Symbol owner = sym.owner();
	final String typeName =
	    (owner.isClass() ? sym.nameString() : sym.fullNameString()) +
	    (sym.isModuleClass() ? "$" : "");
	final ModuleBuilder module = gen.currModule;

	final scalac.symtab.Type classType = sym.info();

	//log("createType: " + dumpSym(sym) + "\n");
	switch (classType) {
	case CompoundType(scalac.symtab.Type[] baseTypes, _):
	    Type superType = null;
	    Type[] interfaces = null;
	    int inum = baseTypes.length;
	    if (sym.isInterface()) {
//  		log("interface " + sym.fullNameString() +
//  		    " extends " + dumpSym(baseTypes[0].symbol()));
		int baseIndex = 0;
		if (baseTypes[0].symbol() == defs.ANY_CLASS) {
		    --inum;
		    baseIndex = 1;
		}
		interfaces = new Type[inum];
		for (int i = 0; i < inum; i++) {
		    assert baseTypes[i + baseIndex].symbol().isInterface();
		    interfaces[i] = getType(baseTypes[i + baseIndex].symbol());
		}
	    } else {
		superType = getType(baseTypes[0].symbol());
		assert inum > 0;
		interfaces = new Type[inum - 1];
		for (int i = 1; i < inum; i++)
		    interfaces[i - 1] = getType(baseTypes[i].symbol());
	    }

	    // i.e. top level class
	    if (owner.isRoot() || owner.isPackage()) {
// 		log("createType():");
// 		log("\ttypeName = " + typeName);
// 		log("\tsuperType = " + superType);
// 		log("\tinterfaces: ");
// 		for (int i = 0; i < interfaces.length; i++)
// 		    log("\t\t" + interfaces[i]);
		type = module.DefineType
		    (typeName, translateTypeAttributes(sym.flags, false),
		     superType, interfaces);
	    } else {	
		final Type oT = getType(owner);
		//log("createType: owner type = " + oT);
		final TypeBuilder outerType = (TypeBuilder) getType(owner);
		type = outerType.DefineNestedType
		    (typeName, translateTypeAttributes(sym.flags, true),
		     superType, interfaces);
	    }
	    break;

	default:
	    global.fail("Symbol does not have a CompoundType: " +
			Debug.show(sym));
	}
	typeBuilders.add(type);
	map(sym, type);
	//log("createType: "  + dumpSym(sym) + " => " + type.getSignature());
	//log("createType: symbol type symbol = " +
	//getTypeFromType(sym.type()).getSignature());

	// create the members of the class
	for (Scope.SymbolIterator syms = sym.members().iterator();
	     syms.hasNext(); )
	    {
		Symbol[] members = syms.next().alternativeSymbols();
		for (int i = 0; i < members.length; i++) {
// 		    System.out.println("\t" + dumpSym(members[i]));
		    if (members[i].isClass()) {
			getType(members[i]);
		    } else if (members[i].isMethod() /*&& !members[i].isModule()*/) {
			createMethod(members[i]);
		    }
		    else if (!members[i].isClass() &&
			     !members[i].isModule()) {
			createField(members[i]);
		    }
		}
	    }

	if (!sym.isInterface()) {
	    Set m = collapseInterfaceMethods(classType);
	    for (Iterator i = m.iterator(); i.hasNext(); ) {
		Symbol s = (Symbol) i.next();
		Symbol method = lookupMethodImplementation(classType, s);
		if (method == Symbol.NONE) {
		    log("Creating method: " + 
		    createMethod(type, s.name, s.info(),
				 s.flags | Modifiers.DEFERRED));
		}
	    }
	}

	return type;
    } // createType()



//     Set collapseInheritedMethods(scalac.symtab.Type classType){
// 	LinkedHashSet methods = new LinkedHashSet();
// 	scalac.symtab.Type[] parents = classType.parents();
// 	if (parents.length > 0) {
// 	    methods.addAll(collapseInheritedMethods(parents[0]));
// 	}
// 	methods.addAll(expandScopeMethods(methods, classType.members()));
// 	return methods;
//     }


    /**
     */
    Symbol lookupMethodImplementation(scalac.symtab.Type classType, Symbol sym) {
	//log("In class " + classType + " with scope: " + classType.members());
	Symbol method = classType.members().lookup(sym.name);
	//log("Looked up method: " + dumpSym(method));
	//log("Matching method " + Debug.show(sym) + ":" + sym.info() + " against:");
	Symbol[] methods = method.alternativeSymbols();
	for (int i = 0; i < methods.length; i++) {
	    //log("\t" + Debug.show(methods[i]) + ": " + methods[i].info());
	    if (methods[i].info().equals(sym.info())) {
		//log("\t\t^ Found ^");
		return methods[i];
	    }
	}

	scalac.symtab.Type[] parents = classType.parents();
	if (parents.length > 0) {
	    // look up the inheritance chain
	    method = lookupMethodImplementation(parents[0], sym);
	} else {
	    return Symbol.NONE;
	}

	// if the method is private this guarantees that there isn't
	// public method with such name up the chain
	return method.isPrivate() ? Symbol.NONE : method;
    }


    /** returns a set with the symbols of all the methods from
     *  all interfaces implemented by the class
     */
    Set collapseInterfaceMethods(scalac.symtab.Type classType) {
	LinkedHashSet methods = new LinkedHashSet();
	scalac.symtab.Type[] parents = classType.parents();
	for (int i = 1; i < parents.length; i++) {
	    methods.addAll(collapseInterfaceMethods(parents[i]));
	}
	methods.addAll(expandScopeMethods(methods, classType.members()));
	return methods;
    }


    /**
     */
    Set expandScopeMethods(Set symbols, Scope scope) {
	for(Scope.SymbolIterator syms = scope.iterator(); syms.hasNext(); ) {
	    Symbol[] members = syms.next().alternativeSymbols();
	    for (int i = 0; i < members.length; i++) {
		if (members[i].isMethod())
		    symbols.add(members[i]);
	    }
	}
	return symbols;
    }


    /** Retrieve the MSIL Type from the scala type
     */
    public Type getTypeFromType(scalac.symtab.Type type) {
	//log("getTypeFromType: " + Debug.show(type));
	switch (type) {
	case CompoundType(_, _):
	    return getType(type.symbol());

	case UnboxedType(int kind):
	    return getTypeFromKind(kind);

 	case TypeRef(_, Symbol s, _):
	    return getType(s);

	case UnboxedArrayType(scalac.symtab.Type elemtp):
	    // force the creation of the type
	    //log("UnboxedArrayType: " + elemtp + "[]");
	    return Type.GetType(getTypeFromType(elemtp) + "[]");
	case NoType:
	    return VOID;

	default:
	    global.fail("getTypeFromType: " + Debug.show(type));
	}
	return null;
    }


    public MethodBase getMethod(Symbol sym) {
	MethodBase method = null;
	try {
	    method = getMethod2(sym);
	} catch (Exception e) {
	    logErr("getMethod: " + dumpSym(sym));
	    if (global.debug) e.printStackTrace();
	}
	return method;
    }


    /** Returns the MethodBase object corresponding to the symbol
     */
    public MethodBase getMethod2(Symbol sym) {
	// force the creation of the declaring type
	getType(sym.owner());
	MethodBase method = (MethodBase) symbols2methods.get(sym);
	if (method != null)
	    return method;

// 	System.err.println("getMethod: resolving " + dumpSym(sym));
// 	System.err.println("getMethod: sym.owner() = " + dumpSym(sym.owner()));
	switch (sym.info()) {
	case MethodType(Symbol[] vparams, scalac.symtab.Type result):
	    Type[] params = new Type[vparams.length];
	    for (int i = 0; i < params.length; i++)
		params[i] = getType(vparams[i]);
	    if (sym.isInitializer()) {
		// The owner of a constructor is the outer class
		// so get the result type of the constructor
		//log("Resolving constructor: " + dumpSym(sym));
		Type type = getType(sym.owner());
		method = type.GetConstructor(params);
	    } else {
		String name = sym.name.toString();
		if (sym.name == Names.toString) name = "ToString";
		else if (sym.name == Names.hashCode) name = "GetHashCode";
		else if (sym.name == Names.equals) name = "Equals";
		//log("Resolving method " + dumpSym(sym));
		Type type = getType(sym.owner());
		method = type.GetMethod(name, params);
	    }
	    break;
	default:
	    global.fail("Symbol doesn't have a method type: " + Debug.show(sym));
	}
	if (method == null) {
	    Type owner = getType(sym.owner());
	    log("Methods of class " + owner);
	    MethodInfo[] methods = owner.GetMethods();
	    for (int i = 0; i < methods.length; i++)
		log("\t" + methods[i]);
	}
	assert method != null : "Cannot find method: " + dumpSym(sym);
	symbols2methods.put(sym, method);
	//log("method found: " + method);
	return method;
    }


    /**
     */
    MethodBase createMethod(TypeBuilder type, Name name,
			    scalac.symtab.Type symType, int flags)
    {
	MethodBase method = null;
	switch (symType) {
	case MethodType(Symbol[] vparams, scalac.symtab.Type result):
	    Type[] params = new Type[vparams.length];
	    for (int i = 0; i < params.length; i++)
		params[i] = getType(vparams[i]);

	    if (name == Names.CONSTRUCTOR) {
		ConstructorBuilder constructor = type.DefineConstructor
		    (translateMethodAttributes(flags, true),
		     CallingConventions.Standard, params);

		for (int i = 0; i < vparams.length; i++)
		    constructor.DefineParameter
			(i, 0/*ParameterAttributes.In*/, vparams[i].name.toString());
		method = constructor;
	    } else {
		String sname;
		if (name == Names.toString) sname = "ToString";
		else if (name == Names.equals) sname = "Equals";
		else if (name == Names.hashCode) sname = "GetHashCode";
		else  sname = name.toString();

		MethodBuilder methodBuilder = type.DefineMethod
		    (sname, translateMethodAttributes(flags, false),
		     getTypeFromType(result), params);

		for (int i = 0; i < vparams.length; i++)
		    methodBuilder.DefineParameter
			(i, 0/*ParameterAttributes.In*/, vparams[i].name.toString());
		method =  methodBuilder;
	    }
	    break;
	default:
	    assert false : "Method type expected: " + Debug.show(symType);
	}

	return method;
    }


    /** create the method corresponding to the symbol
     */
    MethodBase createMethod(Symbol sym) {
	final Symbol owner = sym.owner();
	MethodBase method = null;
	//log("createMethod: resolving " + dumpSym(sym));
	//log("createMethod: sym.owner() = " + dumpSym(sym.owner()));
	switch (sym.info()) {
	case MethodType(Symbol[] vparams, scalac.symtab.Type result):
	    if (sym.isInitializer()) {
		TypeBuilder type = (TypeBuilder) getTypeFromType(result);
		method = createMethod(type, sym.name, sym.info(), sym.flags);
	    } else {
		int flags = ( owner.isJava() && owner.isModuleClass() ) ?
		    sym.flags | Modifiers.STATIC :
		    sym.flags;

		TypeBuilder type = (TypeBuilder)getType(owner);
		method = createMethod(type, sym.name, sym.info(), flags);
	    }
	    break;
	default:
	    assert false : "Symbol doesn't have a method type: " + dumpSym(sym);
	}
	symbols2methods.put(sym, method);
	return method;
    }


    /** Returns teh FieldInfo object corresponing to the symbol
     */
    public FieldInfo getField(Symbol sym) {
	FieldInfo field = (FieldInfo) symbols2fields.get(sym);
	if (field == null) {
	    //log("getField: resolving symbol: " + dumpSym(sym));
	    //log("-->symbol.type() = " + Debug.show(sym.type()));
	    //log("-->symbol.info()" + Debug.show(sym.info()));
	    field = getType(sym.owner()).GetField(sym.name.toString());
	    symbols2fields.put(sym, field);
	}
	assert field != null : "Cannot find field: " + dumpSym(sym);
	return field;
    }

    public FieldInfo getStaticField(Symbol sym) { return null; }

    /**
     */
    public FieldInfo createField(Symbol sym) {
	FieldBuilder field;
	//log("createField: resolving symbol: " + dumpSym(sym));
	//log("-->symbol.type() = " + Debug.show(sym.type()));
	//log("-->symbol.info()" + Debug.show(sym.info()));
	TypeBuilder owner = (TypeBuilder) getType(sym.owner());
	int flags = ( sym.owner().isJava() && sym.owner().isModuleClass() ) ?
	    sym.flags | Modifiers.STATIC : sym.flags;
	field = owner.DefineField(sym.name.toString(),
				  getTypeFromType(sym.type()),
				  translateFieldAttributes(flags));
	Object o = symbols2fields.put(sym, field);
	assert o == null : "Cannot re-define field: " + dumpSym(sym);
	return field;
    }


    /**
     */
    FieldInfo getModuleField(Type type) {
	Symbol sym = (Symbol) types2symbols.get(type);
	assert sym != null;
	return getModuleField(sym);
    }

    private Symbol getTypeSymbol(scalac.symtab.Type type) {
	switch (type) {
	case TypeRef(_, Symbol s, _):
	    return s;
	default:
	    logErr("Strange type: " + Debug.show(type));
	}
	return null;
    }


    /**
     */
    FieldInfo getModuleField(Symbol sym) {
	FieldInfo moduleField = null;
	if (sym.isModule() || sym.isModuleClass()) {
	    moduleField = (FieldInfo) symbols2moduleFields.get(sym);
	    if (moduleField == null) {
		//log("TypeCreator.getModuleField - " + dumpSym(sym));
		//log("\t-->type = " + Debug.show(sym.type()));
		//log("\t-->info = " + Debug.show(sym.info()));
		Symbol s = getTypeSymbol(sym.type());
		if (sym != s) {
		    //log("getModuleField: going through: " + dumpSym(s));
		    moduleField = getModuleField(s);
		} else {
		    Type t = getType(sym);
		    assert t instanceof TypeBuilder :
			"Type of " + dumpSym(sym) + " is " + t;
		    TypeBuilder module = (TypeBuilder) t;//getType(sym);
		    moduleField = module.DefineField
			(MODULE_S,
			 module,
			 (short)(FieldAttributes.Public
				 | FieldAttributes.InitOnly
				 | FieldAttributes.Static));
		}
		symbols2moduleFields.put(sym, moduleField);
	    }
	}
	else {
	    return null;
	    //throw new ApplicationError("getModuleField: not a module: " +
	    //dumpSym(sym));
	}
	return moduleField;
    }


    /** Translates Scala modifiers into TypeAttributes
     */
    public static int translateTypeAttributes(int mods, boolean nested) {
	int attr = TypeAttributes.AutoLayout | TypeAttributes.AnsiClass;

	if (Modifiers.Helper.isInterface(mods))
	    attr |= TypeAttributes.Interface;
	else
	    attr |= TypeAttributes.Class;

	if (Modifiers.Helper.isAbstract(mods))
	    attr |= TypeAttributes.Abstract;
	if (Modifiers.Helper.isFinal(mods))
	    attr |= TypeAttributes.Sealed;

	if (nested) {
	    if (Modifiers.Helper.isPrivate(mods))
		attr |= TypeAttributes.NestedPrivate;
	    else if (Modifiers.Helper.isProtected(mods))
		attr |= TypeAttributes.NestedFamORAssem;
	    else
		attr |= TypeAttributes.NestedPublic;
	} else {
	    if (Modifiers.Helper.isPrivate(mods))
		attr |= TypeAttributes.NotPublic;
	    else
		attr |= TypeAttributes.Public;
	}

	return attr;
    }


    /** Translates Scala modifiers into FieldAttributes
     */
    public static short translateFieldAttributes(int mods) {
	int attr = 0;

	if (Modifiers.Helper.isFinal(mods))
	    attr |= FieldAttributes.InitOnly;
	if (Modifiers.Helper.isPrivate(mods))
	    attr |= FieldAttributes.Private;
	else if (Modifiers.Helper.isProtected(mods))
	    attr |= FieldAttributes.FamORAssem;
	else
	    attr |= FieldAttributes.Public;

	if (Modifiers.Helper.isStatic(mods))
	    attr |= FieldAttributes.Static;

	return (short)attr;
    }


    /** Translates Scala modifiers into MethodAttributes
     */
    public static short translateMethodAttributes(int mods, boolean constructor)
    {
	int attr = MethodAttributes.HideBySig;
	if (!constructor) {
	    attr |= MethodAttributes.Virtual;
 	    if (Modifiers.Helper.isFinal(mods))
 		attr |= MethodAttributes.Final;
	    if (Modifiers.Helper.isAbstract(mods))
		attr |= MethodAttributes.Abstract;
	}

	if (Modifiers.Helper.isPrivate(mods))
	    attr |= MethodAttributes.Private;
	//else if (Modifiers.Helper.isProtected(mods))
	//  attr |= MethodAttributes.FamORAssem;
	else 
	    attr |= MethodAttributes.Public;

	return (short)attr;
    }

    /** Retrieves the primitive datatypes given their kind
     */
    public static Type getTypeFromKind(int kind) {
	switch (kind) {
	case TypeTags.CHAR:    return CHAR;
	case TypeTags.BYTE:    return BYTE;
	case TypeTags.SHORT:   return SHORT;
	case TypeTags.INT:     return INT;
	case TypeTags.LONG:    return LONG;
	case TypeTags.FLOAT:   return FLOAT;
	case TypeTags.DOUBLE:  return DOUBLE;
	case TypeTags.BOOLEAN: return BOOLEAN;
	case TypeTags.UNIT:    return VOID;
	case TypeTags.STRING:  return SYSTEM_STRING;
	default:
	    throw new ApplicationError("Unknown kind: " + kind);
	}
    }

    static String dumpSym(Symbol sym) {
	if (sym == null) return "<null>";
	if (sym == Symbol.NONE) return "NoSymbol";
	return "symbol = " + Debug.show(sym) +
	    " symbol.name = " + sym.name +
	    "; owner = " + Debug.show(sym.owner()) +
	    //"; type = " + Debug.show(sym.type()) +
	    "; info = " + Debug.show(sym.info()) +
	    "; kind = " + sym.kind + 
	    "; flags = " + Integer.toHexString(sym.flags);
    }

    void log(String message) {
	System.out.println(message);
	//log(1, message);
        //global.reporter.printMessage(message);
    }

    void logErr(String message) {
	System.err.println(message);
    }

} // class TypeCreator
