/*     ____ ____  ____ ____  ______                                     *\
**    / __// __ \/ __// __ \/ ____/    SOcos COmpiles Scala             **
**  __\_ \/ /_/ / /__/ /_/ /\_ \       (c) 2002, LAMP/EPFL              **
** /_____/\____/\___/\____/____/                                        **
\*                                                                      */

// $Id$

package scalac.backend.jvm;

import ch.epfl.lamp.util.Position;

import scalac.*;
import scalac.backend.*;
import scalac.util.*;
import scalac.ast.*;
import scalac.symtab.*;
import scalac.symtab.classfile.ClassfileConstants;
import scalac.symtab.classfile.Pickle;
import scalac.transformer.*;

import ch.epfl.lamp.util.Pair;

import ch.epfl.lamp.fjbg.*;

import java.util.*;
import java.io.*;

/* Several things which are done here should in fact be done in
 * previous phases, namely:
 *
 *   - code linearisation (i.e. representation of code as a list of
 *     instructions),
 *
 *   - removal of implicit "this" selection,
 *
 *   - removal of implicit "unit" values,
 *
 *   - removal of implicit conversion,
 *
 *   - removal of "if"s without explicit "else" part,
 *
 *   - expansion of "==",
 *
 *   - introduction of a special primitive for string concatenation,
 *
 *   - initialisation of module instance variable.
 */

/**
 * Backend generating JVM byte-codes.
 *
 * @version 2.0
 * @author Michel Schinz
 */

class GenJVM {
    protected final static String JAVA_LANG_OBJECT = "java.lang.Object";
    protected final static String JAVA_LANG_STRING = "java.lang.String";
    protected final static String JAVA_LANG_STRINGBUFFER = "java.lang.StringBuffer";
    protected final static String SCALA_RUNTIME_RUNTIME = "scala.runtime.RunTime";
    protected final static String SCALA_UNIT = "scala.Unit";
    protected final static String SCALA_UNIT_VALUE = "UNIT_VAL";

    protected final static String SCALA_ATTR = ClassfileConstants.SCALA_N.toString();

    protected final static String MODULE_INSTANCE_FIELD_NAME = "MODULE$";

    protected final static String CONSTRUCTOR_STRING =
        "<init>";               // TODO get it from FJBG
    protected final static Name CONSTRUCTOR_NAME =
        Name.fromString(CONSTRUCTOR_STRING);

    protected final JObjectType JAVA_LANG_OBJECT_T =
        new JObjectType(JAVA_LANG_OBJECT);
    protected final JObjectType JAVA_LANG_STRING_T =
        new JObjectType(JAVA_LANG_STRING);
    protected final JObjectType JAVA_LANG_STRINGBUFFER_T =
        new JObjectType (JAVA_LANG_STRINGBUFFER);
    protected final JObjectType JAVA_LANG_THROWABLE_T =
        new JObjectType("java.lang.Throwable");
    protected final JObjectType SCALA_UNIT_T =
        new JObjectType(SCALA_UNIT);

    protected final Global global;
    protected final Definitions defs;
    protected final Primitives prims;

    static {
        JFactory.setInstance(new JFactory());
    }

    public GenJVM(Global global) {
        this.global = global;
        this.defs = global.definitions;
        this.prims = global.primitives;
        initTypeMap();
        initArithPrimMap();
    }

    /// Code generation
    //////////////////////////////////////////////////////////////////////

    /**
     * Generate code for the given unit.
     */
    public void translate(Unit unit) {
        for (int i = 0; i < unit.body.length; ++i)
            gen(Context.EMPTY.withSourceFileName(unit.source.toString()),
                unit.body[i]);
    }

    /**
     * Generate code to perform the side effects associated with the
     * given tree (i.e. no value should remain on the stack
     * afterwards).
     */
    protected void gen(Context ctx, Tree tree) {
        startCodeForTree(ctx, tree);

        Symbol sym = tree.symbol();

        switch (tree) {
        case PackageDef(_, Tree.Template impl):
            gen(ctx, impl);
            break;

        case ClassDef(_, _, _, _, _, Tree.Template impl) : {
            Tree.ClassDef classDef = (Tree.ClassDef)tree;

            Context ctx1 = enterClass(ctx, sym);

            addValueClassMembers(ctx1, classDef);
            if (ctx1.isModuleClass)
                addModuleInstanceField(ctx1);

            gen(ctx1, impl);
            leaveClass(ctx1, sym);
        } break;

        case Template(_, Tree[] body):
            gen(ctx, body);
            break;

        case ValDef(_, Name name, _, Tree rhs): {
            if (ctx.method == null)
                break;          // ignore ValDefs in classes, handled elsewhere

            JType valType = typeStoJ(sym.info());
            JLocalVariable var =
                ctx.method.addNewLocalVariable(valType, name.toString());
            ctx.locals.put(sym, new Integer(var.getIndex()));

            if (rhs != Tree.Empty)
                genLoad(ctx, rhs, valType);
            else {
                switch (valType.getTag()) {
                case JType.T_BOOLEAN:
                case JType.T_BYTE:
                case JType.T_CHAR:
                case JType.T_SHORT:
                case JType.T_INT:
                    ctx.code.emitPUSH(0); break;
                case JType.T_LONG:
                    ctx.code.emitPUSH(0L); break;
                case JType.T_FLOAT:
                    ctx.code.emitPUSH(0F); break;
                case JType.T_DOUBLE:
                    ctx.code.emitPUSH(0D); break;
                default:
                    ctx.code.emitACONST_NULL(); break;
                }
            }
            ctx.code.emitSTORE(var);
        } break;

        case DefDef(_, _, _, _, _, Tree rhs): {
            Tree.DefDef defDef = (Tree.DefDef)tree;
            Context ctx1 = enterMethod(ctx, defDef);
            if (! Modifiers.Helper.isAbstract(sym.flags)) {
                JType retType = ctx1.method.getReturnType();
                genLoad(ctx1, rhs, retType);
                ctx1.code.emitRETURN(retType);
            }
            leaveMethod(ctx1);
        } break;

        case Typed(Tree expr, _):
            gen(ctx, expr);
            break;

        case Empty:
        case TypeDef(_, _, _, _):
        case TypeApply(_, _):
        case FunType(_, _):
        case CompoundType(_, _):
        case AppliedType(_,_):
            break;

        default:
            genLoad(ctx, tree, JType.VOID);
        }

        endCodeForTree(ctx, tree);
    }

    protected void gen(Context ctx, Tree[] trees) {
        for (int i = 0; i < trees.length; ++i)
            gen(ctx, trees[i]);
    }

    /**
     * Generate code to load the value of the given tree on the
     * stack, and make sure it is of the given expected type.
     */
    protected JType genLoad(Context ctx, Tree tree, JType expectedType) {
        startCodeForTree(ctx, tree);

        JType generatedType = null;
        Symbol sym = tree.symbol();

        switch (tree) {
        case LabelDef(Tree.Ident[] params, Tree rhs): {
            JLabel label = new JLabel();
            ctx.code.anchorLabelToNext(label);
            ctx.labels.put(sym, new Pair(label, params));
            generatedType = genLoad(ctx, rhs, expectedType);
            ctx.labels.remove(sym);
        } break;

        case Block(Tree[] stats): {
            int statsNum = stats.length;
            for (int i = 0; i < statsNum - 1; ++i)
                gen(ctx, stats[i]);
            if (statsNum == 0)
                maybeGenLoadUnit(ctx, expectedType);
            else
                genLoad(ctx, stats[stats.length - 1], expectedType);
            generatedType = expectedType;
        } break;

        case Typed(Tree expr, _):
            genLoad(ctx, expr, expectedType);
            generatedType = expectedType;
            break;

        case New(Tree.Template templ): {
            assert templ.body.length == 0;
            assert templ.parents.length == 1;

            String className = javaName(tree.type.symbol());
            ctx.code.emitNEW(className);
            ctx.code.emitDUP();
            gen(ctx, templ.parents[0]);
            generatedType = new JObjectType(className);
        } break;

        case Apply(TypeApply(Tree fun, Tree[] args), _): {
            genLoadQualifier(ctx, fun);

            JType type = typeStoJ(args[0].type);
            if (fun.symbol() == defs.IS) {
                ctx.code.emitINSTANCEOF((JReferenceType)type);
                generatedType = JType.BOOLEAN;
            } else if (fun.symbol() == defs.AS) {
                ctx.code.emitCHECKCAST((JReferenceType)type);
                generatedType = type;
            } else
                global.fail("unexpected type application");
        } break;

        case Apply(Tree fun, Tree[] args): {
            Symbol funSym = fun.symbol();

            if (funSym.isLabel()) {
                Pair/*<JLabel, Tree[]>*/ labelAndIdents =
                    (Pair)ctx.labels.get(funSym);
                assert labelAndIdents != null : Debug.show(funSym);

                JLabel label = (JLabel)labelAndIdents.fst;
                Tree[] idents = (Tree[])labelAndIdents.snd;
                assert idents.length == args.length;

                for (int i = 0; i < args.length; ++i)
                    genLoad(ctx, args[i], typeStoJ(args[i].type));
                for (int i = idents.length; i > 0; --i)
                    genStoreEpilogue(ctx, idents[i-1]);
            } else if (isKnownPrimitive(funSym)) {
                Primitive prim = prims.getPrimitive(funSym);

                switch (prim) {
                case CONCAT:
                    genStringConcatenation(ctx, liftStringConcatenations(tree));
                    generatedType = JAVA_LANG_STRING_T;
                    break;

                case POS: case NEG:
                case ADD: case SUB: case MUL: case DIV: case MOD:
                case NOT: case OR : case XOR: case AND:
                case LSL: case LSR: case ASR:
                    Tree[] allArgs = extractPrimitiveArgs((Tree.Apply)tree);
                    JType resType = typeStoJ(tree.type);
                    genArithPrim(ctx, prim, allArgs, resType, expectedType);
                    generatedType = resType;
                    break;

                case EQ: case NE: case LT: case LE: case GE: case GT:
                case ZNOT: case ZOR: case ZAND:
                    JLabel falseLabel = new JLabel();
                    JLabel afterLabel = new JLabel();
                    genCond(ctx, tree, falseLabel, false);
                    ctx.code.emitICONST_1();
                    ctx.code.emitGOTO(afterLabel);
                    ctx.code.anchorLabelToNext(falseLabel);
                    ctx.code.emitICONST_0();
                    ctx.code.anchorLabelToNext(afterLabel);
                    generatedType = JType.BOOLEAN;
                    break;

                case THROW:
                    assert args.length == 0;
                    genThrow(ctx, ((Tree.Select)fun).qualifier);
                    // We pretend that we generated something of the
                    // expected type, to avoid trying to generate
                    // bogus conversions.
                    generatedType = expectedType;
                    break;

                case NEW_ZARRAY : case NEW_BARRAY : case NEW_SARRAY :
                case NEW_CARRAY : case NEW_IARRAY : case NEW_LARRAY :
                case NEW_FARRAY : case NEW_DARRAY :
                    assert args.length == 1;
                    genArrayCreate(ctx, prim, args[0]);
                    generatedType = JAVA_LANG_OBJECT_T; // TODO refine
                    break;

                case ZARRAY_SET : case BARRAY_SET : case SARRAY_SET :
                case CARRAY_SET : case IARRAY_SET : case LARRAY_SET :
                case FARRAY_SET : case DARRAY_SET : case OARRAY_SET :
                    assert args.length == 3;
                    genArrayUpdate(ctx, args[0], args[1], args[2]);
                    generatedType = JType.VOID;
                    break;

                case ZARRAY_GET : case BARRAY_GET : case SARRAY_GET :
                case CARRAY_GET : case IARRAY_GET : case LARRAY_GET :
                case FARRAY_GET : case DARRAY_GET : case OARRAY_GET :
                    assert args.length == 2 : "get - " + args.length;
                    genArrayAccess(ctx, args[0], args[1]);
                    generatedType = getArrayElementType(args[0]);
                    break;

                case ZARRAY_LENGTH : case BARRAY_LENGTH : case SARRAY_LENGTH :
                case CARRAY_LENGTH : case IARRAY_LENGTH : case LARRAY_LENGTH :
                case FARRAY_LENGTH : case DARRAY_LENGTH : case OARRAY_LENGTH :
                    assert args.length == 1 : args.length;
                    genArrayLength(ctx, args[0]);
                    generatedType = JType.INT;
                    break;

                case AS_UVALUE :
                    assert args.length == 0;
                    gen(ctx, ((Tree.Select)fun).qualifier);
                    generatedType = JType.VOID;
                    break;

                default:
                    throw Debug.abort("unknown primitive ", prim);
                }
            } else {
                JMethodType funType = (JMethodType)typeStoJ(funSym.info());
                JType[] argTypes = funType.getArgumentTypes();

                boolean isConstrCall = (funSym.name == CONSTRUCTOR_NAME);
                boolean isSuperCall;
                switch (fun) {
                case Select(Super(_), _): isSuperCall = true; break;
                default: isSuperCall = false; break;
                }

                boolean isStatic = isStaticMember(funSym);
                if (!(isStatic || (isConstrCall && !isSuperCall)))
                    genLoadQualifier(ctx, fun);
                for (int i = 0; i < args.length; ++i)
                    genLoad(ctx, args[i], argTypes[i]);

                String clsName = isSuperCall
                    ? ctx.clazz.getSuperclassName()
                    : javaName(funSym.owner());
                String mthName = funSym.name.toString();

                funSym.owner().info(); // [HACK] ensure that flags are
                                       // transformed.
                if (funSym.owner().isInterface())
                    ctx.code.emitINVOKEINTERFACE(clsName, mthName, funType);
                else {
                    if (isConstrCall || isSuperCall) {
                        ctx.code.emitINVOKESPECIAL(clsName, mthName, funType);
                        if (isConstrCall && isSuperCall && ctx.isModuleClass) {
                            // Initialise module instance field ASAP
                            ctx.code.emitALOAD_0();
                            ctx.code.emitPUTSTATIC(ctx.clazz.getName(),
                                                   MODULE_INSTANCE_FIELD_NAME,
                                                   ctx.clazz.getType());
                        }
                    } else if (isStatic)
                        ctx.code.emitINVOKESTATIC(clsName, mthName, funType);
                    else
                        ctx.code.emitINVOKEVIRTUAL(clsName, mthName, funType);
                }
                generatedType = funType.getReturnType();
            }
        } break;

        case Ident(Name name): {
            JType type = typeStoJ(sym.info());
            if (sym.isModule())
                generatedType = genLoadModule(ctx, sym);
            else if (sym == defs.NULL) {
                ctx.code.emitACONST_NULL();
                generatedType = expectedType;
            } else if (sym.owner().isClass()) {
                ctx.code.emitALOAD_0();
                ctx.code.emitGETFIELD(ctx.clazz.getName(), name.toString(), type);
                generatedType = type;
            } else {
                assert ctx.locals.containsKey(sym)
                    : Debug.show(sym) + " not in " + ctx.locals;
                int index = ((Integer)(ctx.locals.get(sym))).intValue();
                ctx.code.emitLOAD(index, type);
                generatedType = type;
            }
        } break;

        case Select(Tree qualifier, Name selector): {
            if (sym.isModule())
                generatedType = genLoadModule(ctx, sym);
            else {
                JType fieldType = typeStoJ(sym.info());
                String className = javaName(sym.owner());
                String fieldName = selector.toString();
                if (isStaticMember(sym))
                    ctx.code.emitGETSTATIC(className, fieldName, fieldType);
                else {
                    genLoadQualifier(ctx, tree);
                    ctx.code.emitGETFIELD(className, fieldName, fieldType);
                }
                generatedType = fieldType;
            }
        } break;

        case Assign(Tree lhs, Tree rhs): {
            genStorePrologue(ctx, lhs);
            genLoad(ctx, rhs, typeStoJ(lhs.symbol().info()));
            genStoreEpilogue(ctx, lhs);
            generatedType = JType.VOID;
        } break;

        case If(Tree cond, Tree thenp, Tree elsep): {
            JType finalType = typeStoJ(tree.type);

            JLabel elseLabel = new JLabel();
            genCond(ctx, cond, elseLabel, false);
            genLoad(ctx, thenp, finalType);
            JLabel afterLabel = new JLabel();
            ctx.code.emitGOTO(afterLabel);
            ctx.code.anchorLabelToNext(elseLabel);
            if (elsep == Tree.Empty)
                maybeGenLoadUnit(ctx, finalType);
            else
                genLoad(ctx, elsep, finalType);
            ctx.code.anchorLabelToNext(afterLabel);
            generatedType = finalType;
        } break;

        case This(_):
        case Super(_):
            ctx.code.emitALOAD_0();
            generatedType = JAVA_LANG_OBJECT_T;
            break;

        case Literal(Object value):
            if (value instanceof Integer) {
                generatedType = JType.INT;
                ctx.code.emitPUSH((Integer)value);
            } else if (value instanceof Long) {
                generatedType = JType.LONG;
                ctx.code.emitPUSH((Long)value);
            } else if (value instanceof Float) {
                generatedType = JType.FLOAT;
                ctx.code.emitPUSH((Float)value);
            } else if (value instanceof Double) {
                generatedType = JType.DOUBLE;
                ctx.code.emitPUSH((Double)value);
            } else if (value instanceof Character) {
                generatedType = JType.CHAR;
                ctx.code.emitPUSH((Character)value);
            } else if (value instanceof String) {
                generatedType = JAVA_LANG_STRING_T;
                ctx.code.emitPUSH((String)value);
            } else if (value instanceof Boolean) {
                generatedType = JType.BOOLEAN;
                ctx.code.emitPUSH((Boolean)value);
            } else
                throw global.fail("unknown literal " + value);
            break;

        case Empty:
        case TypeDef(_, _, _, _):
        case TypeApply(_, _):
        case FunType(_, _):
        case CompoundType(_, _):
        case AppliedType(_,_):
            generatedType = JType.VOID;
            break;

        case Sequence(_):
        case ModuleDef(_,_,_,_):
        case PatDef(_,_,_):
        case Import(_, _):
        case CaseDef(_, _, _):
        case Visitor(_):
        case Function(_, _):
            throw global.fail("unexpected node", tree);
        case Bad():
            throw global.fail("bad tree");
        default:
            throw global.fail("unknown node", tree);
        }

        // Pop unneeded result from stack, or widen it if needed.
        if (expectedType == JType.VOID && generatedType != JType.VOID) {
            if (generatedType == JType.LONG || generatedType == JType.DOUBLE)
                ctx.code.emitPOP2();
            else
                ctx.code.emitPOP();
        } else if (! (expectedType == JType.VOID
                      || generatedType == expectedType
                      || generatedType.isReferenceType()))
            genWidenConversion(ctx, generatedType, expectedType);

        endCodeForTree(ctx, tree);
        return expectedType;
    }

    /**
     * Generate code to load the module represented by the given
     * symbol.
     */
    protected JType genLoadModule(Context ctx, Symbol sym) {
        String javaSymName = javaName(sym);
        JType type = typeStoJ(sym.info());
        if (javaSymName.equals(ctx.clazz.getName()))
            ctx.code.emitALOAD_0();
        else
            ctx.code.emitGETSTATIC(javaSymName,
                                   MODULE_INSTANCE_FIELD_NAME,
                                   type);

        return type;
    }

    /**
     * Generate code to load the qualifier of the given tree, which
     * can be implicitely "this".
     */
    protected void genLoadQualifier(Context ctx, Tree tree) {
        switch (tree) {
        case Ident(_):
            ctx.code.emitALOAD_0();
            break;
        case Select(Tree qualifier, _):
            genLoad(ctx, qualifier, JAVA_LANG_OBJECT_T);
            break;
        default:
            throw global.fail("unknown qualifier");
        }
    }

    /**
     * Generate code to load the Unit value, iff the given type is an
     * object type (i.e. something really has to be loaded on stack).
     */
    protected void maybeGenLoadUnit(Context ctx, JType type) {
        if (type != JType.VOID)
            ctx.code.emitGETSTATIC(SCALA_RUNTIME_RUNTIME,
                                   SCALA_UNIT_VALUE,
                                   SCALA_UNIT_T);
    }

    /**
     * Generate code to prepare the storage of a value in the location
     * represented by the tree.
     */
    protected void genStorePrologue(Context ctx, Tree tree) {
        Symbol sym = tree.symbol();
        switch (tree) {
        case Ident(_):
            if (sym.owner().isClass())
                ctx.code.emitALOAD_0();
            break;
        case Select(Tree qualifier, _):
            if (!isStaticMember(sym))
                genLoadQualifier(ctx, tree);
            break;
        default:
            throw global.fail("unexpected left-hand side", tree);
        }
    }

    /**
     * Generate code to perform the storage of the value on top of
     * stack in the location represented by the tree.
     */
    protected void genStoreEpilogue(Context ctx, Tree tree) {
        Symbol sym = tree.symbol();
        if (sym.owner().isClass()) {
            String ownerName = javaName(sym.owner());
            if (isStaticMember(sym))
                ctx.code.emitPUTSTATIC(ownerName,
                                       sym.name.toString(),
                                       typeStoJ(sym.info()));
            else
                ctx.code.emitPUTFIELD(ownerName,
                                      sym.name.toString(),
                                      typeStoJ(sym.info()));
        } else {
            assert ctx.locals.containsKey(sym)
                : Debug.show(sym) + " not in " + ctx.locals;
            int index = ((Integer)(ctx.locals.get(sym))).intValue();
            ctx.code.emitSTORE(index, typeStoJ(sym.info()));
        }
    }

    /**
     * Generate code to evaluate the condition associated with the
     * given tree and jump to the target when the condition is equal
     * to the given value.
     */
    protected void genCond(Context ctx,
                           Tree tree,
                           JLabel target,
                           boolean when) {
        switch (tree) {
        case Apply(Tree fun, Tree[] args):
            if (isKnownPrimitive(fun.symbol())) {
                Primitive prim = prims.getPrimitive(fun.symbol());
                Tree[] allArgs = extractPrimitiveArgs((Tree.Apply)tree);

                switch (prim) {
                case EQ: case NE: case LT: case LE: case GE: case GT:
                    assert allArgs.length == 2;
                    genCompPrim(ctx, prim, allArgs, target, when);
                    return;

                case ZNOT:
                    assert allArgs.length == 1;
                    genCond(ctx, allArgs[0], target, !when);
                    return;

                case ZOR:
                case ZAND:
                    JLabel afterLabel = new JLabel();
                    if (when ^ (prim == Primitive.ZAND)) {
                        // x || y jump if true  -or-  x && y jump if false
                        genCond(ctx, allArgs[0], target, when);
                        genCond(ctx, allArgs[1], target, when);
                    } else {
                        // x || y jump if false  -or-  x && y jump if true
                        genCond(ctx, allArgs[0], afterLabel, !when);
                        genCond(ctx, allArgs[1], target, when);
                    }
                    ctx.code.anchorLabelToNext(afterLabel);
                    return;
                }
            }
        }
        // Default case: the condition is not a comparison or logical
        // primitive.
        genLoad(ctx, tree, JType.BOOLEAN);
        if (when)
            ctx.code.emitIFNE(target);
        else
            ctx.code.emitIFEQ(target);
    }

    protected Map/*<Primitive, Instruction>*/ arithPrimMap;
    protected void addPrim(Primitive prim,
                           JOpcode z,
                           JOpcode i,
                           JOpcode l,
                           JOpcode f,
                           JOpcode d) {
        arithPrimMap.put(prim, new JOpcode[] { z, i, l, f, d });
    }

    protected void initArithPrimMap() {
        arithPrimMap = new HashMap();
        /* boolean, int & al., long, float, double */
        addPrim(Primitive.ADD,
                null, JOpcode.IADD, JOpcode.LADD, JOpcode.FADD, JOpcode.DADD);
        addPrim(Primitive.SUB,
                null, JOpcode.ISUB, JOpcode.LSUB, JOpcode.FSUB, JOpcode.DSUB);
        addPrim(Primitive.MUL,
                null, JOpcode.IMUL, JOpcode.LMUL, JOpcode.FMUL, JOpcode.DMUL);
        addPrim(Primitive.DIV,
                null, JOpcode.IDIV, JOpcode.LDIV, JOpcode.FDIV, JOpcode.DDIV);
        addPrim(Primitive.MOD,
                null, JOpcode.IREM, JOpcode.LREM, JOpcode.FREM, JOpcode.DREM);
        addPrim(Primitive.AND,
                JOpcode.IAND, JOpcode.IAND, JOpcode.LAND, null, null);
        addPrim(Primitive.OR,
                JOpcode.IOR, JOpcode.IOR, JOpcode.LOR, null, null);
        addPrim(Primitive.XOR,
                JOpcode.IXOR, JOpcode.IXOR, JOpcode.LXOR, null, null);
        addPrim(Primitive.LSL,
                null, JOpcode.ISHL, JOpcode.LSHL, null, null);
        addPrim(Primitive.LSR,
                null, JOpcode.IUSHR, JOpcode.LUSHR, null, null);
        addPrim(Primitive.ASR,
                null, JOpcode.ISHR, JOpcode.LSHR, null, null);
        addPrim(Primitive.POS,
                null, null, null, null, null);
        addPrim(Primitive.NEG,
                null, JOpcode.INEG, JOpcode.LNEG, JOpcode.FNEG, JOpcode.DNEG);
    }

    /**
     * Generate code for the given arithmetic primitive, applied on
     * the given arguments.
     */
    protected void genArithPrim(Context ctx,
                                Primitive prim,
                                Tree[] args,
                                JType resType,
                                JType expectedType) {
        int arity = args.length;
        int resTypeIdx = getTypeIndex(resType);

        for (int i = 0; i < arity; ++i)
            genLoad(ctx, args[i], resType);

        if (prim == Primitive.NOT) {
            if (resType == JType.LONG) {
                ctx.code.emitPUSH(-1L);
                ctx.code.emitLXOR();
            } else {
                assert resType == JType.INT;
                ctx.code.emitPUSH(-1);
                ctx.code.emitIXOR();
            }
        } else {
            assert arithPrimMap.containsKey(prim);
            JOpcode primInst = ((JOpcode[])arithPrimMap.get(prim))[resTypeIdx];
            if (primInst != null)
                ctx.code.emit(primInst);
        }
    }

    /** Variable in which temporary objects are stored by the
     * implementation of == */
    protected JLocalVariable eqEqTempVar;

    /**
     * Generate code for the given comparison primitive, applied on
     * the given arguments.
     */
    protected void genCompPrim(Context ctx,
                               Primitive prim,
                               Tree[] args,
                               JLabel target,
                               boolean when) {
        JType maxType = getMaxType(args);
        int maxTypeIdx = getTypeIndex(maxType);
        int intTypeIdx = getTypeIndex(JType.INT);
        boolean intCompareWithZero = false;
        // Generate code for all arguments, while detecting
        // comparisons with 0, which can be optimised.
        for (int i = 0; i < args.length; ++i) {
            boolean isIntZero = false;
            if (maxTypeIdx <= intTypeIdx) {
                switch (args[i]) {
                case Literal(Object val):
                    int intVal;
                    if (val instanceof Number)
                        intVal = ((Number)val).intValue();
                    else if (val instanceof Character)
                        intVal = ((Character)val).charValue();
                    else if (val instanceof Boolean)
                        intVal = ((Boolean)val).booleanValue() ? 1 : 0;
                    else
                        throw Debug.abort("unknown literal", val);
                    if (intVal == 0) {
                        isIntZero = true;
                        if (i == 0) prim = prim.swap();
                    }
                }
            }
            if (intCompareWithZero || !isIntZero)
                genLoad(ctx, args[i], maxType);
            intCompareWithZero |= isIntZero;
        }

        if (maxType.isReferenceType()) {
            // Comparison between two references. We inline the code
            // for the predefined (and final) "=="/"!=" operators,
            // which check for null values and then forward the call
            // to "equals". "==" could be defined as follows, if
            // "null" was an object:
            //   final def ==(other: Any): boolean =
            //     if (this == null) other == null else this.equals(other)
            assert prim == Primitive.EQ || prim == Primitive.NE;

            if (eqEqTempVar == null || eqEqTempVar.getOwner() != ctx.method)
                eqEqTempVar =
                    ctx.method.addNewLocalVariable(JObjectType.JAVA_LANG_OBJECT,
                                                   "eqEqTemp$");

            ctx.code.emitSTORE(eqEqTempVar);
            ctx.code.emitDUP();
            JLabel ifNonNullLabel = new JLabel();
            ctx.code.emitIFNONNULL(ifNonNullLabel);
            ctx.code.emitPOP();
            ctx.code.emitLOAD(eqEqTempVar);
            if (when ^ (prim != Primitive.EQ))
                ctx.code.emitIFNULL(target);
            else
                ctx.code.emitIFNONNULL(target);
            JLabel afterLabel = new JLabel();
            ctx.code.emitGOTO(afterLabel);
            ctx.code.anchorLabelToNext(ifNonNullLabel);
            ctx.code.emitLOAD(eqEqTempVar);
            JMethodType equalsType =
                new JMethodType(JType.BOOLEAN,
                                new JType[] { JObjectType.JAVA_LANG_OBJECT });
            ctx.code.emitINVOKEVIRTUAL(JAVA_LANG_OBJECT, "equals", equalsType);
            if (when ^ (prim != Primitive.EQ))
                ctx.code.emitIFNE(target);
            else
                ctx.code.emitIFEQ(target);
            ctx.code.anchorLabelToNext(afterLabel);
        } else if (maxTypeIdx <= intTypeIdx && !intCompareWithZero) {
            // Comparison between ints, no zeros involved
            switch (maybeNegatedPrim(prim, !when)) {
            case LT: ctx.code.emitIF_ICMPLT(target); break;
            case LE: ctx.code.emitIF_ICMPLE(target); break;
            case EQ: ctx.code.emitIF_ICMPEQ(target); break;
            case NE: ctx.code.emitIF_ICMPNE(target); break;
            case GE: ctx.code.emitIF_ICMPGE(target); break;
            case GT: ctx.code.emitIF_ICMPGT(target); break;
            default: throw global.fail("unknown primitive " + prim);
            }
        } else {
            // Comparison between longs, floats or double, or between
            // one int and zero.
            switch (maxType.getTag()) {
            case JType.T_LONG:   ctx.code.emitLCMP();  break;
            case JType.T_FLOAT:  ctx.code.emitFCMPG(); break;
            case JType.T_DOUBLE: ctx.code.emitDCMPG(); break;
            default:
                ;               // do nothing (int comparison with 0)
            }
            switch (maybeNegatedPrim(prim, !when)) {
            case LT: ctx.code.emitIFLT(target); break;
            case LE: ctx.code.emitIFLE(target); break;
            case EQ: ctx.code.emitIFEQ(target); break;
            case NE: ctx.code.emitIFNE(target); break;
            case GE: ctx.code.emitIFGE(target); break;
            case GT: ctx.code.emitIFGT(target); break;
            default: throw global.fail("unknown primitive " + prim);
            }
        }
    }

    /**
     * Generate code to throw the value returned by the argument.
     */
    protected void genThrow(Context ctx, Tree arg) {
        genLoad(ctx, arg, JAVA_LANG_OBJECT_T);
        ctx.code.emitCHECKCAST(JAVA_LANG_THROWABLE_T);
        ctx.code.emitATHROW();
    }

    protected JOpcode[][] WIDENING_CONVERSION_TABLE = {
        /*               bool  int   long         float        double */
        /* boolean */  { null, null, null,        null,        null   },
        /* int ... */  { null, null, JOpcode.I2L, JOpcode.I2F, JOpcode.I2D },
        /* long */     { null, null, null,        JOpcode.L2F, JOpcode.L2D },
        /* float */    { null, null, null,        null,        JOpcode.F2D },
        /* double */   { null, null, null,        null,        null   }
    };

    protected void genWidenConversion(Context ctx, JType from, JType to) {
        int fromIdx = getTypeIndex(from);
        int toIdx = getTypeIndex(to);

        assert (fromIdx <= 4 && toIdx <= 4) : from + " -> " + to;
        JOpcode instr = WIDENING_CONVERSION_TABLE[fromIdx][toIdx];
        if (instr != null)
            ctx.code.emit(instr);
    }

    /// Arrays
    //////////////////////////////////////////////////////////////////////

    /**
     * Generate code to create an array, whose size will be
     * dynamically computed.
     */
    protected void genArrayCreate(Context ctx, Primitive prim, Tree size) {
        genLoad(ctx, size, JType.INT);
        JType type;
        switch (prim) {
        case NEW_ZARRAY : type = JType.BOOLEAN; break;
        case NEW_BARRAY : type = JType.BYTE;    break;
        case NEW_SARRAY : type = JType.SHORT;   break;
        case NEW_CARRAY : type = JType.CHAR;    break;
        case NEW_IARRAY : type = JType.INT;     break;
        case NEW_LARRAY : type = JType.LONG;    break;
        case NEW_FARRAY : type = JType.FLOAT;   break;
        case NEW_DARRAY : type = JType.DOUBLE;  break;
        default: throw Debug.abort("unexpected primitive", prim);
        }
        ctx.code.emitNEWARRAY(type);
    }

    /**
     * Generate code to update an array.
     */
    protected void genArrayUpdate(Context ctx, Tree array, Tree index, Tree value) {
        genLoad(ctx, array, JAVA_LANG_OBJECT_T);
        genLoad(ctx, index, JType.INT);
        JType elemType = getArrayElementType(array);
        if (elemType.isValueType())
            value = unbox(value);
        genLoad(ctx, value, elemType);
        ctx.code.emitASTORE(elemType);
    }

    /**
     * Generate code to load an element of an array.
     */
    protected void genArrayAccess(Context ctx, Tree array, Tree index) {
        genLoad(ctx, array, JAVA_LANG_OBJECT_T);
        genLoad(ctx, index, JType.INT);
        ctx.code.emitALOAD(getArrayElementType(array));
    }

    /**
     * Generate code to load the length of an array.
     */
    protected void genArrayLength(Context ctx, Tree array) {
        genLoad(ctx, array, JAVA_LANG_OBJECT_T);
        ctx.code.emitARRAYLENGTH();
    }

    /**
     * Return the Java type of the elements of the array represented
     * by the given tree.
     */
    protected JType getArrayElementType(Tree array) {
        JArrayType arrayType = (JArrayType)typeStoJ(array.type);
        return arrayType.getElementType();
    }

    /// String concatenation
    //////////////////////////////////////////////////////////////////////

    protected Tree[] liftStringConcatenations(Tree tree) {
        LinkedList accu = new LinkedList();
        liftStringConcatenations(tree, accu);
        return (Tree[])accu.toArray(new Tree[accu.size()]);
    }

    protected void liftStringConcatenations(Tree tree, LinkedList accu) {
        switch (tree) {
        case Apply(Select(Tree qualifier, Name selector), Tree[] args): {
            Symbol funSym = ((Tree.Apply)tree).fun.symbol();
            if  (prims.isPrimitive(funSym)
                 && prims.getPrimitive(funSym) == Primitive.CONCAT) {
                liftStringConcatenations(qualifier, accu);
                liftStringConcatenations(args[0], accu);
            } else
                accu.addLast(tree);
            } break;
        default:
            accu.addLast(tree);
        }
    }

    /**
     * Generate code to concatenate a list of expressions which return
     * strings.
     */
    protected void genStringConcatenation(Context ctx, Tree[] elements) {
        // Create string buffer
        ctx.code.emitNEW(JAVA_LANG_STRINGBUFFER);
        ctx.code.emitDUP();
        ctx.code.emitINVOKESPECIAL(JAVA_LANG_STRINGBUFFER,
                                   "<init>",
                                   JMethodType.ARGLESS_VOID_FUNCTION);

        // Append all strings
        for (int i = 0; i < elements.length; ++i) {
            JType elemType = typeStoJ(elements[i].type);
            if (!elemType.equals(JObjectType.JAVA_LANG_STRING)
                && elemType.isObjectType())
                elemType = JObjectType.JAVA_LANG_OBJECT;
            genLoad(ctx, elements[i], elemType);
            ctx.code.emitINVOKEVIRTUAL(JAVA_LANG_STRINGBUFFER,
                                       "append",
                                       new JMethodType(JAVA_LANG_STRINGBUFFER_T,
                                                       new JType[] { elemType }));
        }

        // Get resulting string
        ctx.code.emitINVOKEVIRTUAL(JAVA_LANG_STRINGBUFFER,
                                   "toString",
                                   new JMethodType(JObjectType.JAVA_LANG_STRING,
                                                   JType.EMPTY_ARRAY));
    }

    /// Primitives
    //////////////////////////////////////////////////////////////////////

    /**
     * Return true iff the given symbol is a primitive, AND that
     * primitive is recognized by this back-end.
     */
    protected boolean isKnownPrimitive(Symbol sym) {
        if (prims.isPrimitive(sym)) {
            switch (prims.getPrimitive(sym)) {
            case POS : case NEG :
            case ADD : case SUB : case MUL : case DIV : case MOD :
            case NOT : case OR : case XOR : case AND :
            case LSL : case LSR : case ASR :
            case EQ : case NE : case LT : case LE : case GE : case GT :
            case ZNOT : case ZOR : case ZAND :
            case NEW_ZARRAY : case NEW_BARRAY : case NEW_SARRAY :
            case NEW_CARRAY : case NEW_IARRAY : case NEW_LARRAY :
            case NEW_FARRAY : case NEW_DARRAY :
            case ZARRAY_GET : case BARRAY_GET : case SARRAY_GET :
            case CARRAY_GET : case IARRAY_GET : case LARRAY_GET :
            case FARRAY_GET : case DARRAY_GET : case OARRAY_GET :
            case ZARRAY_SET : case BARRAY_SET : case SARRAY_SET :
            case CARRAY_SET : case IARRAY_SET : case LARRAY_SET :
            case FARRAY_SET : case DARRAY_SET : case OARRAY_SET :
            case ZARRAY_LENGTH : case BARRAY_LENGTH : case SARRAY_LENGTH :
            case CARRAY_LENGTH : case IARRAY_LENGTH : case LARRAY_LENGTH :
            case FARRAY_LENGTH : case DARRAY_LENGTH : case OARRAY_LENGTH :
            case IS : case AS :
            case CONCAT :
            case THROW :
            case AS_UVALUE :
                return true;

            case AS_ZVALUE : case AS_BVALUE : case AS_SVALUE :
            case AS_CVALUE : case AS_IVALUE : case AS_LVALUE :
            case AS_FVALUE : case AS_DVALUE :
            case AS_ZARRAY : case AS_BARRAY : case AS_SARRAY :
            case AS_CARRAY : case AS_IARRAY : case AS_LARRAY :
            case AS_FARRAY : case AS_DARRAY : case AS_OARRAY :
            case NEW_OARRAY :
            case EQUALS  :
            case HASHCODE :
            case TOSTRING :
            case BOX :
            case APPLY : case UPDATE : case LENGTH :
                return false;
            default:
                throw Debug.abort("unknown primitive", sym);
            }
        } else
            return false;
    }

    /**
     * Negate the given primitive only if the second argument is
     * true, otherwise return it as is.
     */
    protected Primitive maybeNegatedPrim(Primitive prim, boolean negate) {
        return negate ? prim.negate() : prim;
    }

    /**
     * Return all the arguments associated with the primitive
     * represented by the given function call.
     */
    protected Tree[] extractPrimitiveArgs(Tree.Apply call) {
        Tree[] allArgs = new Tree[call.args.length + 1];
        allArgs[0] = unbox(((Tree.Select)(call.fun)).qualifier);
        System.arraycopy(call.args, 0, allArgs, 1, call.args.length);
        return allArgs;
    }

    /**
     * Return the unboxed version of the given tree.
     */
    protected Tree unbox(Tree tree) {
        switch (tree) {
        case Apply(Tree fun, Tree[] args):
            if (prims.getPrimitive(fun.symbol()) == Primitive.BOX) {
                assert args.length == 1;
                return args[0];
            } else
                return tree;
        case Block(Tree[] stats):
            if (stats.length == 2
                && prims.getPrimitive(stats[1].symbol()) == Primitive.BOX) {
                return stats[0];
            } else
                return tree;
        default:
            return tree;
        }
    }

    /// Modules
    //////////////////////////////////////////////////////////////////////

    /**
     * Add field containing module instance, and code to initialize
     * it, to current class.
     */
    protected void addModuleInstanceField(Context ctx) {
        ctx.clazz.addNewField(JAccessFlags.ACC_PUBLIC
                              | JAccessFlags.ACC_FINAL
                              | JAccessFlags.ACC_STATIC,
                              MODULE_INSTANCE_FIELD_NAME,
                              ctx.clazz.getType());

        JMethod initMethod =
            ctx.clazz.addNewMethod(JAccessFlags.ACC_PUBLIC
                                   | JAccessFlags.ACC_STATIC,
                                   "<clinit>",
                                   JType.VOID,
                                   JType.EMPTY_ARRAY,
                                   Strings.EMPTY_ARRAY);
        JExtendedCode code = (JExtendedCode)initMethod.getCode();

        code.emitNEW(ctx.clazz.getName());
        code.emitINVOKESPECIAL(ctx.clazz.getName(),
                               CONSTRUCTOR_STRING,
                               JMethodType.ARGLESS_VOID_FUNCTION);
        // The field is initialised by the constructor, so we don't
        // need to do it here, creating the instance is sufficient.
        code.emitRETURN();
    }

    /**
     * Create a class which mirrors all public methods of the given
     * module class as static methods, to enable the use of the module
     * from Java.
     */
    protected void dumpModuleMirrorClass(Context ctx, JClass modClass) {
        String mirrorName = modClass.getName();
        String mainClassName = mirrorName.substring(0, mirrorName.length() - 1);

        JClass mainClass = new JClass(JAccessFlags.ACC_SUPER
                                      | JAccessFlags.ACC_PUBLIC
                                      | JAccessFlags.ACC_FINAL,
                                      mainClassName,
                                      JAVA_LANG_OBJECT,
                                      JClass.NO_INTERFACES,
                                      ctx.sourceFileName);

        JMethod[] methods = modClass.getMethods();
        for (int i = 0; i < methods.length; ++i) {
            JMethod m = methods[i];
            if (m.isProtected() || m.isPrivate() || m.isStatic()
                || m.getName().equals("<init>"))
                continue;

            JType retType = m.getReturnType();
            JType[] argTypes = m.getArgumentTypes();
            JMethod mirror =
                mainClass.addNewMethod(m.getAccessFlags()
                                       | JAccessFlags.ACC_STATIC,
                                       m.getName(),
                                       retType,
                                       argTypes,
                                       m.getArgumentNames());
            JExtendedCode mirrorCode = (JExtendedCode)mirror.getCode();

            mirrorCode.emitGETSTATIC(mirrorName,
                                     MODULE_INSTANCE_FIELD_NAME,
                                     new JObjectType(mirrorName));
            int index = 0;
            for (int j = 0; j < argTypes.length; ++j) {
                mirrorCode.emitLOAD(index, argTypes[j]);
                index += argTypes[j].getSize();
            }
            mirrorCode.emitINVOKE(m);
            mirrorCode.emitRETURN(retType);
        }

        addScalaAttr(mainClass);
        try {
            String fileName = javaFileName(mainClassName);
            mainClass.writeTo(fileName);
            global.operation("wrote " + fileName);
        } catch (java.io.IOException e) {
            throw global.fail(e.getMessage());
        }
    }

    /**
     * Add the "Scala" attribute to the given class, in which the
     * symbol table is saved.
     */
    protected void addScalaAttr(JClass cls) {
        Name className = Name.fromString(cls.getName());

        if (global.symdata.containsKey(className)) {
            Pickle pickle = (Pickle)global.symdata.get(className);
            JOtherAttribute scalaAttr = new JOtherAttribute(cls,
                                                            SCALA_ATTR,
                                                            pickle.bytes,
                                                            pickle.size());
            cls.addAttribute(scalaAttr);
        }
    }

    /// Names
    //////////////////////////////////////////////////////////////////////

    /**
     * Return a Java-compatible version of the name of the given
     * symbol. The returned name is mangled and includes the names of
     * the owners.
     */
    protected String javaName(Symbol sym) {
        assert sym.isClass() || sym.isModule() : Debug.show(sym);
        if (sym == defs.ANY_CLASS || sym == defs.ANYREF_CLASS)
            return JAVA_LANG_OBJECT;
        else {
            StringBuffer buf = new StringBuffer(sym.name.toString());
            if ((sym.isModule() || sym.isModuleClass()) && !sym.isJava())
                buf.append('$');
            for (sym = sym.owner(); !sym.isPackage(); sym = sym.owner()) {
                buf.insert(0, '$');
                buf.insert(0, sym.name);
            }
            if (!sym.isRoot()) {
                buf.insert(0, '.');
                buf.insert(0, sym.fullName());
            }
            return buf.toString();
        }
    }

    /**
     * Return the name of the file in which to store the given class.
     */
    protected String javaFileName(String className) {
        StringTokenizer tokens = new StringTokenizer(className, ".");
        File file = new File(global.outpath);
        while (tokens.hasMoreElements())
            file = new File(file, tokens.nextToken());

        return file.getPath() + ".class";
    }

    /// Types
    //////////////////////////////////////////////////////////////////////

    /**
     * Return the Java modifiers corresponding to the given Scala
     * modifiers.
     */
    protected int modifiersStoJ(int flags) {
        int jFlags = 0;

        if (Modifiers.Helper.isPrivate(flags))
            jFlags |= JAccessFlags.ACC_PRIVATE;
        else
            jFlags |= JAccessFlags.ACC_PUBLIC;

        if (Modifiers.Helper.isAbstract(flags))
            jFlags |= JAccessFlags.ACC_ABSTRACT;
        if (Modifiers.Helper.isInterface(flags))
            jFlags |= JAccessFlags.ACC_INTERFACE;

        if (Modifiers.Helper.isFinal(flags)
            && !(Modifiers.Helper.isAbstract(flags)
                 || Modifiers.Helper.isInterface(flags)))
            jFlags |= JAccessFlags.ACC_FINAL;

        return jFlags;
    }

    protected HashMap typeMap/*<Symbol,Type>*/ = new HashMap();
    protected void initTypeMap() {
        typeMap.put(defs.ANY_CLASS,    JObjectType.JAVA_LANG_OBJECT);
        typeMap.put(defs.ANYREF_CLASS, JObjectType.JAVA_LANG_OBJECT);
    }

    /**
     * Return the Java type corresponding to the given Scala type.
     */
    protected JType typeStoJ(Type tp) {
        switch (tp) {
        case UnboxedType(TypeTags.BYTE):
            return JType.BYTE;
        case UnboxedType(TypeTags.CHAR):
            return JType.CHAR;
        case UnboxedType(TypeTags.SHORT):
            return JType.SHORT;
        case UnboxedType(TypeTags.INT):
            return JType.INT;
        case UnboxedType(TypeTags.LONG):
            return JType.LONG;
        case UnboxedType(TypeTags.FLOAT):
            return JType.FLOAT;
        case UnboxedType(TypeTags.DOUBLE):
            return JType.DOUBLE;
        case UnboxedType(TypeTags.BOOLEAN):
            return JType.BOOLEAN;
        case UnboxedType(TypeTags.UNIT):
            return JType.VOID;
        case UnboxedType(TypeTags.STRING):
            return JObjectType.JAVA_LANG_STRING;
        case UnboxedArrayType(Type elementType):
            return new JArrayType(typeStoJ(elementType));
        case MethodType(Symbol[] vparams, Type result): {
            JType[] argTypes = new JType[vparams.length];
            for (int i = 0; i < vparams.length; ++i)
                argTypes[i] = typeStoJ(vparams[i].info());
            return new JMethodType(typeStoJ(result), argTypes);
        }
        default: {
            Symbol sym = tp.symbol();
            if (sym == Symbol.NONE)
                throw global.fail("invalid type ", tp);
            else if (typeMap.containsKey(sym))
                return (JType)typeMap.get(sym);
            else {
                JType jTp = new JObjectType(javaName(sym));
                typeMap.put(sym, jTp);
                return jTp;
            }
        }
        }
    }

    /**
     * Return the "index" of the given type. This index encodes the
     * level in the hierarchy of basic types, with arrays and objects
     * on top of everything.
     */
    protected int getTypeIndex(JType tp) {
        return getTypeIndex(tp.getTag());
    }

    /**
     * Return the "index" of the given type.
     */
    protected int getTypeIndex(int tp) {
        switch (tp) {
        case JType.T_BOOLEAN: return 0;
        case JType.T_BYTE:
        case JType.T_CHAR:
        case JType.T_SHORT:
        case JType.T_INT:     return 1;
        case JType.T_LONG:    return 2;
        case JType.T_FLOAT:   return 3;
        case JType.T_DOUBLE:  return 4;
        case JType.T_ARRAY:
        case JType.T_OBJECT:  return 5;
        default: return -1;
        }
    }

    /**
     * Return the maximum of the types of all the given trees. All
     * reference types are considered to be equivalent, and if several
     * reference types are present in the trees, any one of them is
     * returned.
     */
    protected JType getMaxType(Tree[] trees) {
        JType maxType = JType.BOOLEAN;
        int maxTypeIdx = getTypeIndex(maxType);

        for (int i = 0; i < trees.length; ++i) {
            JType argType = typeStoJ(trees[i].type);
            if (getTypeIndex(argType) > maxTypeIdx) {
                maxType = argType;
                maxTypeIdx = getTypeIndex(maxType);
            }
        }
        return maxType;
    }

    /// Line numbers
    //////////////////////////////////////////////////////////////////////

    int[] pcStack = new int[32];
    int pcStackDepth = 0;
    void startCodeForTree(Context ctx, Tree tree) {
        if (pcStackDepth == pcStack.length) {
            int[] newPCStack = new int[pcStack.length * 2];
            System.arraycopy(pcStack, 0, newPCStack, 0, pcStack.length);
            pcStack = newPCStack;
        }
        pcStack[pcStackDepth++] = (ctx.code == null ? 0 : ctx.code.getPC());
    }

    void endCodeForTree(Context ctx, Tree tree) {
        assert pcStackDepth > 0;
        int startPC = pcStack[--pcStackDepth];
        if (ctx.code != null)
            ctx.code.completeLineNumber(startPC,
                                        ctx.code.getPC(),
                                        Position.line(tree.pos));
    }

    /// Context
    //////////////////////////////////////////////////////////////////////

    /**
     * Record the entry into a class, and return the appropriate
     * context.
     */
    protected Context enterClass(Context ctx, Symbol cSym) {
        String javaName = javaName(cSym);

        scalac.symtab.Type[] baseTps = cSym.info().parents();
        assert baseTps.length > 0 : Debug.show(cSym);

        int offset;
        String superClassName;
        if (cSym.isInterface()) {
            offset = baseTps[0].isSameAs(defs.ANY_TYPE) ? 1 : 0;
            superClassName = JAVA_LANG_OBJECT;
        } else {
            offset = 1;
            superClassName = javaName(baseTps[0].symbol());
        }
        String[] interfaceNames = new String[baseTps.length - offset];
        for (int i = offset; i < baseTps.length; ++i) {
            Symbol baseSym = baseTps[i].symbol();
            assert baseSym.isInterface() : cSym + " implements " + baseSym;
            interfaceNames[i - offset] = javaName(baseSym);
        }

        JClass cls = new JClass(modifiersStoJ(cSym.flags)
                                | JAccessFlags.ACC_SUPER,
                                javaName,
                                superClassName,
                                interfaceNames,
                                ctx.sourceFileName);

        return ctx.withClass(cls, cSym.isModuleClass());
    }

    protected HashSet seenClasses = new HashSet();
    protected void leaveClass(Context ctx, Symbol cSym) {
        if (ctx.isModuleClass && !seenClasses.contains(cSym.fullName()))
            dumpModuleMirrorClass(ctx, ctx.clazz);
        seenClasses.add(cSym.fullName());

        addScalaAttr(ctx.clazz);
        try {
            String fileName = javaFileName(ctx.clazz.getName());
            ctx.clazz.writeTo(fileName);
            global.operation("wrote " + fileName);
        } catch (java.io.IOException e) {
            throw global.fail(e.getMessage());
        }
    }

    /**
     * Record the entry into a method, and return the appropriate
     * context.
     */
    protected Context enterMethod(Context ctx, Tree.DefDef mDef) {
        Symbol mSym = mDef.symbol();

        global.log("entering method " + Debug.toString(mSym)
                   + " (type: " + Debug.toString(mSym.info()) + ")");

        Map locals = new HashMap();

        Tree.ValDef[] args = mDef.vparams[0];
        int argsNum = args.length;

        JType[] argTypes = new JType[argsNum];
        String[] argNames = new String[argsNum];
        for (int i = 0, pos = 1; i < argsNum; ++i) {
            argTypes[i] = typeStoJ(args[i].symbol().info());
            argNames[i] = args[i].name.toString();
            locals.put(args[i].symbol(), new Integer(pos));
            pos += argTypes[i].getSize();
        }

        JMethod method =
            ctx.clazz.addNewMethod(modifiersStoJ(mSym.flags),
                                   mSym.name.toString(),
                                   typeStoJ(mSym.info().resultType()),
                                   argTypes,
                                   argNames);

        return ctx.withMethod(method, locals);
    }

    protected void leaveMethod(Context ctx) {
        global.log(" leaving method ");
    }

    /// Misc.
    //////////////////////////////////////////////////////////////////////

    /**
     * Add value members (i.e. fields) to current class.
     */
    protected void addValueClassMembers(Context ctx, Tree.ClassDef cDef) {
        Symbol cSym = cDef.symbol();
        Scope.SymbolIterator memberIt =
            new Scope.UnloadIterator(cSym.members().iterator());
        while (memberIt.hasNext()) {
            Symbol member = memberIt.next();
            if (member.isTerm() && !member.isMethod())
                ctx.clazz.addNewField(modifiersStoJ(member.flags),
                                      member.name.toString(),
                                      typeStoJ(member.info()));
        }
    }

    /**
     * Return true iff the given symbol is a static (in the Java
     * sense) member of its owner.
     */
    protected boolean isStaticMember(Symbol sym) {
        return (sym.name != CONSTRUCTOR_NAME)
            && sym.owner().isModuleClass()
            && sym.owner().isJava();
    }
}

/**
 * A compilation context, which records information about the class
 * and the method currently being generated.
 */
class Context {
    public final String sourceFileName;
    public final JClass clazz;
    public final JMethod method;
    public final JExtendedCode code;
    public final Map/*<Symbol,JLocalVariable>*/ locals;
    public final Map/*<Symbol,Pair<JLabel,Tree[]>>*/ labels;
    public final boolean isModuleClass;

    public final static Context EMPTY =
        new Context(null, null, null, null, null, false);

    private Context(String sourceFileName,
                    JClass clazz,
                    JMethod method,
                    Map locals,
                    Map labels,
                    boolean isModuleClass) {
        this.sourceFileName = sourceFileName;
        this.clazz = clazz;
        this.method = method;
        if (method == null || method.isAbstract())
            this.code = null;
        else
            this.code = (JExtendedCode)method.getCode();
        this.locals = locals;
        this.labels = labels;
        this.isModuleClass = isModuleClass;
    }

    public Context withSourceFileName(String sourceFileName) {
        return new Context(sourceFileName, null, null, null, null, false);
    }

    public Context withClass(JClass clazz, boolean isModuleClass) {
        return new Context(this.sourceFileName, clazz, null, null, null, isModuleClass);
    }

    public Context withMethod(JMethod method, Map locals) {
        assert this.clazz == method.getOwner();
        return new Context(this.sourceFileName,
                           this.clazz,
                           method,
                           locals,
                           new HashMap(),
                           this.isModuleClass);
    }
}
