/*     ____ ____  ____ ____  ______                                     *\
**    / __// __ \/ __// __ \/ ____/    SOcos COmpiles Scala             **
**  __\_ \/ /_/ / /__/ /_/ /\_ \       (c) 2003, LAMP/EPFL              **
** /_____/\____/\___/\____/____/                                        **
\*                                                                      */

// $Id$

package scalac.symtab.classfile;

import scalac.Global;
import scalac.atree.AConstant;
import scalac.symtab.Symbol;
import scalac.symtab.SymbolLoader;
import scalac.symtab.Scope;
import scalac.symtab.Modifiers;
import scalac.symtab.Type.*;
import scalac.util.Name;
import scalac.util.Names;
import scalac.util.Debug;

import scala.tools.util.Position;
import ch.epfl.lamp.compiler.msil.*;

public class CLRClassParser extends SymbolLoader {

    protected JavaTypeFactory make;

    protected final CLRPackageParser importer;

    public CLRClassParser(Global global, CLRPackageParser importer) {
	super(global);
	this.importer = importer;
    }

    private static Name[] ENUM_CMP_NAMES = new Name[]
	{ Names.EQ, Names.NE, Names.LT, Names.LE, Names.GT, Names.GE };

    private static Name[] ENUM_BIT_LOG_NAMES = new Name[]
	{ Names.OR, Names.AND, Names.XOR };

    protected String doComplete(Symbol clazz) {
	try { return doComplete0(clazz); }
	catch (Throwable e) {
	    System.err.println("\nWhile processing " + Debug.show(clazz));
	    e.printStackTrace();
	    System.exit(1);
            return null; // !!!
	}
    }

    protected String doComplete0(Symbol clazz) {
	clazz.owner().initialize(); //???

	if (make == null)
	    make = new JavaTypeCreator(global.definitions);

	Type type = (Type)importer.getMember(clazz);
	if (type == null)
	    type = Type.GetType(global.primitives.getCLRClassName(clazz));
	assert type != null : Debug.show(clazz);

	clazz.flags = translateAttributes(type);
	Type[] ifaces = type.getInterfaces();
	scalac.symtab.Type[] baseTypes = new scalac.symtab.Type[ifaces.length+1];
	baseTypes[0] = type.BaseType == null ? global.definitions.ANYREF_TYPE()
	    : getCLRType(type.BaseType);
	for (int i = 0; i < ifaces.length; i++)
	    baseTypes[i + 1] = getCLRType(ifaces[i]);
	Scope members = new Scope();
	Scope statics = new Scope();
	scalac.symtab.Type classInfo =
	    scalac.symtab.Type.compoundType(baseTypes, members, clazz);
	clazz.setInfo(classInfo);
        Symbol staticsModule = clazz.linkedModule();
	Symbol staticsClass = staticsModule.moduleClass();
        assert staticsClass.isModuleClass(): Debug.show(staticsClass);
        scalac.symtab.Type staticsInfo = scalac.symtab.Type.compoundType
            (scalac.symtab.Type.EMPTY_ARRAY, statics, staticsClass);
        staticsClass.setInfo(staticsInfo);
        staticsModule.setInfo(make.classType(staticsClass));
        scalac.symtab.Type ctype = make.classType(clazz);

	// import nested types
	Type[] nestedTypes = type.getNestedTypes();
	for (int i = 0; i < nestedTypes.length; i++) {
	    Type ntype = nestedTypes[i];
	    if (ntype.IsNestedPrivate() || ntype.IsNestedAssembly()
		|| ntype.IsNestedFamANDAssem())
		continue;
	    int j = ntype.FullName.lastIndexOf('.');
	    String n = (j < 0 ? ntype.FullName : ntype.FullName.substring(j + 1))
		.replace('+', '#');
	    Name classname = Name.fromString(n).toTypeName();
	    Name aliasname = Name.fromString(ntype.Name).toTypeName();
	    // put the class at the level of its outermost class
	    // the owner of a class is always its most specific package
	    Symbol nclazz = clazz.owner().newLoadedClass(JAVA, classname, this, null);
	    importer.map(nclazz, ntype);
	    // create an alias in the module of the outer class
	    Symbol alias = staticsClass.newTypeAlias(Position.NOPOS,
                translateAttributes(ntype), aliasname, make.classType(nclazz));
	    statics.enterNoHide(alias);
	}

	FieldInfo[] fields = type.getFields();
	for (int i = 0; i < fields.length; i++) {
	    if (fields[i].IsPrivate() || fields[i].IsAssembly()
		|| fields[i].IsFamilyAndAssembly())
		continue;
	    int mods = translateAttributes(fields[i]);
	    Name name = Name.fromString(fields[i].Name);
	    scalac.symtab.Type fieldType = getCLRType(fields[i].FieldType);
	    if (fields[i].IsLiteral() && !fields[i].FieldType.IsEnum())
		fieldType = make.constantType(
                    getConstant(fieldType.symbol(), fields[i].getValue()));
	    Symbol owner = fields[i].IsStatic() ? staticsClass : clazz;
	    Symbol field = owner.newField(Position.NOPOS, mods, name);
	    field.setInfo(fieldType);
	    (fields[i].IsStatic() ? statics : members).enterOrOverload(field);
	    importer.map(field, fields[i]);
	}

	PropertyInfo[] props = type.getProperties();
	for (int i = 0; i < props.length; i++) {
	    MethodInfo getter = props[i].GetGetMethod(true);
	    MethodInfo setter = props[i].GetSetMethod(true);
	    if (getter == null || getter.GetParameters().length > 0)
		continue;
	    assert props[i].PropertyType == getter.ReturnType;
	    scalac.symtab.Type proptype = getCLSType(props[i].PropertyType);
	    if (proptype == null)
		continue;
	    Name n = Name.fromString(props[i].Name);
	    scalac.symtab.Type mtype =
		scalac.symtab.Type.PolyType(Symbol.EMPTY_ARRAY, proptype);
	    int mods = translateAttributes(getter);
	    Symbol owner = getter.IsStatic() ? staticsClass : clazz;
	    Symbol method = owner.newMethod(Position.NOPOS, mods, n);
	    setParamOwners(mtype, method);
	    method.setInfo(mtype);
	    (getter.IsStatic() ? statics : members).enterOrOverload(method);
	    importer.map(method, getter);

	    if (setter == null)
		continue;
	    assert getter.IsStatic() == setter.IsStatic();
	    assert setter.ReturnType == importer.VOID;
	    mtype = methodType(setter, getCLSType(importer.VOID));
	    if (mtype == null)
		continue;
	    n = Name.fromString(n.toString() + Names._EQ);
	    mods = translateAttributes(setter);
	    method = owner.newMethod(Position.NOPOS, mods, n);
	    setParamOwners(mtype, method);
	    method.setInfo(mtype);
	    (setter.IsStatic() ? statics : members).enterOrOverload(method);
	    importer.map(method, setter);
	}

	MethodInfo[] methods = type.getMethods();
	for (int i = 0; i < methods.length; i++) {
	    if ((importer.getSymbol(methods[i]) != null)
		|| methods[i].IsPrivate()
		|| methods[i].IsAssembly()
		|| methods[i].IsFamilyAndAssembly())
		continue;
	    scalac.symtab.Type rettype = getCLSType(methods[i].ReturnType);
	    if (rettype == null)
		continue;
	    scalac.symtab.Type mtype = methodType(methods[i], rettype);
	    if (mtype == null)
		continue;
	    String name = methods[i].Name;
	    Name n;
	    if (name.equals("GetHashCode")) n = Names.hashCode;
	    else if (name.equals("Equals")) n = Names.equals;
	    else if (name.equals("ToString")) n = Names.toString;
	    else n = Name.fromString(name);
	    int mods = translateAttributes(methods[i]);
	    Symbol owner = methods[i].IsStatic() ? staticsClass : clazz;
	    Symbol method = owner.newMethod(Position.NOPOS, mods, n);
	    setParamOwners(mtype, method);
	    method.setInfo(mtype);
	    (methods[i].IsStatic() ? statics : members).enterOrOverload(method);
	    importer.map(method, methods[i]);
	}

	// for enumerations introduce comparison and bitwise logical operations;
	// the backend should recognize and replace them with comparison or
	// bitwise logical operations on the primitive underlying type
	if (type.IsEnum()) {
	    scalac.symtab.Type[] argTypes = new scalac.symtab.Type[] {ctype};
	    int mods = Modifiers.JAVA | Modifiers.FINAL;
	    for (int i = 0; i < ENUM_CMP_NAMES.length; i++) {
		scalac.symtab.Type enumCmpType =
		    make.methodType(argTypes,
				    global.definitions.BOOLEAN_TYPE(),
				    scalac.symtab.Type.EMPTY_ARRAY);
		Symbol enumCmp = clazz.newMethod
		    (Position.NOPOS, mods, ENUM_CMP_NAMES[i]);
		setParamOwners(enumCmpType, enumCmp);
		enumCmp.setInfo(enumCmpType);
		members.enterOrOverload(enumCmp);
	    }
	    for (int i = 0; i < ENUM_BIT_LOG_NAMES.length; i++) {
		scalac.symtab.Type enumBitLogType = make.methodType
		    (argTypes, ctype, scalac.symtab.Type.EMPTY_ARRAY);
		Symbol enumBitLog = clazz.newMethod
		    (Position.NOPOS, mods, ENUM_BIT_LOG_NAMES[i]);
		setParamOwners(enumBitLogType, enumBitLog);
		enumBitLog.setInfo(enumBitLogType);
		members.enterOrOverload(enumBitLog);
	    }
	}

	ConstructorInfo[] constrs = type.getConstructors();
	for (int i = 0; i < constrs.length; i++) {
	    if (constrs[i].IsStatic() || constrs[i].IsPrivate()
		|| constrs[i].IsAssembly() || constrs[i].IsFamilyAndAssembly())
		continue;
	    scalac.symtab.Type mtype = methodType(constrs[i], ctype);
	    if (mtype == null)
		continue;
	    Symbol constr = clazz.primaryConstructor();
	    int mods = translateAttributes(constrs[i]);
	    if (constr.isInitialized()) {
                clazz.addConstructor(
                    constr = clazz.newConstructor(Position.NOPOS, mods));
            } else {
                constr.flags = mods;
            }
	    setParamOwners(mtype, constr);
	    constr.setInfo(mtype);
	    importer.map(constr, constrs[i]);
	}

	Symbol constr = clazz.primaryConstructor();
	if (!constr.isInitialized()) {
	    constr.setInfo(scalac.symtab.Type.MethodType
				(Symbol.EMPTY_ARRAY, ctype));
	    if ((clazz.flags & Modifiers.INTERFACE) == 0)
		constr.flags |= Modifiers.PRIVATE;
	}

	return type + " from assembly " + type.Assembly;
    }

    /** Return a method type for */
    protected scalac.symtab.Type methodType(MethodBase method,
					    scalac.symtab.Type rettype) {
	ParameterInfo[] params = method.GetParameters();
	scalac.symtab.Type[] ptypes = new scalac.symtab.Type[params.length];
	for (int j = 0; j < params.length; j++) {
	    ptypes[j] = getCLSType(params[j].ParameterType);
	    if (ptypes[j] == null)
		return null;
	}
	return make.methodType(ptypes, rettype, scalac.symtab.Type.EMPTY_ARRAY);
    }

    protected void setParamOwners(scalac.symtab.Type type, Symbol owner) {
	switch (type) {
	case PolyType(Symbol[] params, scalac.symtab.Type restype):
	    for (int i = 0; i < params.length; i++) params[i].setOwner(owner);
	    setParamOwners(restype, owner);
	    return;
	case MethodType(Symbol[] params, scalac.symtab.Type restype):
	    for (int i = 0; i < params.length; i++) params[i].setOwner(owner);
	    setParamOwners(restype, owner);
	    return;
	}
    }

    protected scalac.symtab.Type getClassType(Type type) {
	assert type != null;
	scalac.symtab.Type res =
	    make.classType(type.FullName.replace('+', '.'));
	if (res.isError())
	    global.error("unknown class reference " + type.FullName);
	return res;
    }

    protected scalac.symtab.Type getCLSType(Type type) {
	if (type == importer.BYTE || type == importer.USHORT
	    || type == importer.UINT || type == importer.ULONG
	    || type.IsPointer()
	    || (type.IsArray() && getCLSType(type.GetElementType()) == null))
	    return null;
	//Symbol s = importer.getSymbol(type);
	//scalac.symtab.Type t = s != null ? make.classType(s) : getCLRType(type);
	return getCLRType(type);
    }

    protected scalac.symtab.Type getCLRType(Type type) {
	if (type == importer.OBJECT)
	    return make.objectType();
	if (type == importer.STRING)
	    return make.stringType();
	if (type == importer.VOID)
	    return make.voidType();
	if (type == importer.BOOLEAN)
	    return make.booleanType();
	if (type == importer.CHAR)
	    return make.charType();
	if (type == importer.BYTE || type == importer.UBYTE)
	    return make.byteType();
	if (type == importer.SHORT || type == importer.USHORT)
	    return make.shortType();
	if (type == importer.INT || type == importer.UINT)
	    return make.intType();
	if (type == importer.LONG || type == importer.ULONG)
	    return make.longType();
	if (type == importer.FLOAT)
	    return make.floatType();
	if (type == importer.DOUBLE)
	    return make.doubleType();
	if (type.IsArray())
	    return make.arrayType(getCLRType(type.GetElementType()));
	Symbol s = importer.getSymbol(type);
	return s != null ? make.classType(s) : getClassType(type);
    }

    public AConstant getConstant(Symbol base, Object value) {
        if (base == global.definitions.BOOLEAN_CLASS)
            return AConstant.BOOLEAN(((Number)value).intValue() != 0);
        if (base == global.definitions.BYTE_CLASS)
            return AConstant.BYTE(((Number)value).byteValue());
        if (base == global.definitions.SHORT_CLASS)
            return AConstant.SHORT(((Number)value).shortValue());
        if (base == global.definitions.CHAR_CLASS)
            return AConstant.CHAR((char)((Number)value).intValue());
        if (base == global.definitions.INT_CLASS)
            return AConstant.INT(((Number)value).intValue());
        if (base == global.definitions.LONG_CLASS)
            return AConstant.LONG(((Number)value).longValue());
        if (base == global.definitions.FLOAT_CLASS)
            return AConstant.FLOAT(((Number)value).floatValue());
        if (base == global.definitions.DOUBLE_CLASS)
            return AConstant.DOUBLE(((Number)value).doubleValue());
        if (base == global.definitions.STRING_CLASS)
            return AConstant.STRING((String)value);
    	throw Debug.abort("illegal value", Debug.show(value, " - ", base));
    }

    protected static int translateAttributes(Type type) {
	int mods = Modifiers.JAVA;
	if (type.IsNotPublic() || type.IsNestedPrivate()
	    || type.IsNestedAssembly() || type.IsNestedFamANDAssem())
	    mods |= Modifiers.PRIVATE;
	else if (type.IsNestedFamily() || type.IsNestedFamORAssem())
	    mods |= Modifiers.PROTECTED;
	if (type.IsAbstract())
	    mods |= Modifiers.ABSTRACT;
	if (type.IsSealed())
	    mods |= Modifiers.FINAL;
	if (type.IsInterface())
	    mods |= Modifiers.INTERFACE | Modifiers.TRAIT | Modifiers.ABSTRACT;

	return mods;
    }

    protected static int translateAttributes(FieldInfo field) {
	int mods = Modifiers.JAVA;
	if (field.IsPrivate() || field.IsAssembly() || field.IsFamilyAndAssembly())
	    mods |= Modifiers.PRIVATE;
	else if (field.IsFamily() || field.IsFamilyOrAssembly())
	    mods |= Modifiers.PROTECTED;
	if (field.IsInitOnly())
	    mods |= Modifiers.FINAL;
	else
	    mods |= Modifiers.MUTABLE;

	return mods;
    }

    protected static int translateAttributes(MethodBase method) {
	int mods = Modifiers.JAVA;
	if (method.IsPrivate() || method.IsAssembly() || method.IsFamilyAndAssembly())
	    mods |= Modifiers.PRIVATE;
	else if (method.IsFamily() || method.IsFamilyOrAssembly())
	    mods |= Modifiers.PROTECTED;
	if (method.IsAbstract())
	    mods |= Modifiers.DEFERRED;

	return mods;
    }
}
