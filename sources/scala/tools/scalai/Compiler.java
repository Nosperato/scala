/*     ____ ____  ____ ____  ______                                     *\
**    / __// __ \/ __// __ \/ ____/    SOcos COmpiles Scala             **
**  __\_ \/ /_/ / /__/ /_/ /\_ \       (c) 2002, LAMP/EPFL              **
** /_____/\____/\___/\____/____/                                        **
\*                                                                      */

// $OldId: Compiler.java,v 1.19 2002/10/01 16:14:07 paltherr Exp $
// $Id$

package scalai;

import java.lang.reflect.Proxy;
import java.lang.reflect.InvocationHandler;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.HashSet;
import java.util.Iterator;

import ch.epfl.lamp.util.SourceFile;

import scalac.Unit;
import scalac.Global;
import scalac.ast.Tree;
import scalac.ast.Tree.ValDef;
import scalac.ast.TreeGen;
import scalac.symtab.Definitions;
import scalac.symtab.Type;
import scalac.symtab.Symbol;
import scalac.symtab.TermSymbol;
import scalac.util.Debug;
import scalac.util.Name;
import scalac.util.Names;

import scala.runtime.RunTime;

public class Compiler {

    //########################################################################

    private final Global global;
    private final Definitions definitions;
    private final Constants constants;
    private final ClassLoader loader;
    private final Environment environment;
    private final Evaluator evaluator; // !!! remove
    private final Map any_methods;
    private final Map sources;

    public Compiler(Global global, Evaluator evaluator) {
        this.global = global;
        this.definitions = global.definitions;
        this.constants = new Constants();
        this.loader = new PathClassLoader(global.classPath);
        scala.runtime.RunTime.setClassLoader(loader);
        JavaMirror mirror = new JavaMirror(definitions, loader);
        this.environment = new Environment(this, mirror);
        this.evaluator = evaluator;
        this.any_methods = new HashMap();
        this.sources = new HashMap();

        SourceFile compiled = new SourceFile("<<compiled code>>", new byte[0]);

        environment.insertFunction(definitions.STRING_PLUS_ANY, Function.StringPlus); // !!!
        // !!! ANY_PLUS_STRING is commented out in definitions
        // !!! environment.insertFunction(definitions.ANY_PLUS_STRING, Function.StringPlus); // !!!
        environment.insertFunction(definitions.THROW, Function.Throw);
        // !!! environment.insertFunction(definitions.MATCH, ...);
        // !!! environment.insertFunction(definitions.IS, ...);
        // !!! environment.insertFunction(definitions.AS, ...);

        java.lang.reflect.Method getInvocationHandler_method = null;
        try {
            getInvocationHandler_method = Proxy.class.getMethod(
                "getInvocationHandler",
                new Class[] {Object.class});
        } catch (Exception exception) {
            throw Debug.abort("getInvocationHandler", exception);
        }

        // !!! method java.lang.Object.clone()

        java.lang.reflect.Method equals_method = null;
        try {
            equals_method = Object.class.getMethod("equals",
                new Class[] {Object.class});
        } catch (Exception exception) {
            throw Debug.abort("equals", exception);
        }
        // !!! should we have a symbol Any.equals as for hashcode and toString?
        Symbol equals_symbol =
            definitions.JAVA_OBJECT_CLASS.lookup(Names.equals);
        assert equals_symbol != Symbol.NONE;
        CodePromise equals_code = new CodePromise(
            new CodeContainer(
                compiled,
                equals_symbol,
                Code.Invoke(
                    Code.Invoke(
                        Code.Null,
                        Function.JavaMethod(getInvocationHandler_method),
                        new Code[] {
                            Code.Self},
                        0),
                    Function.JavaMethod(equals_method),
                    new Code[] {
                        Code.Load(
                            Code.Null, Variable.Argument(0))},
                    0),
                0));
        any_methods.put(definitions.EQUALS, equals_code);
        any_methods.put(equals_method, equals_code);
        environment.insertFunction(definitions.EQUALS,
            Function.JavaMethod(equals_method));
        Override equals_override = Override.empty();
        equals_override.insert(equals_method).insert(definitions.EQUALS);
        environment.insertOverride(definitions.EQUALS, equals_override);

        // !!! method java.lang.Object.equals(Object)
        // !!! method java.lang.Object.finalize()
        // !!! method java.lang.Object.getClass()

        java.lang.reflect.Method hashCode_method = null;
        try {
            hashCode_method = Object.class.getMethod("hashCode", new Class[0]);
        } catch (Exception exception) {
            throw Debug.abort("hashCode", exception);
        }
        CodePromise hashCode_code = new CodePromise(
            new CodeContainer(
                compiled,
                definitions.HASHCODE,
                Code.Invoke(
                    Code.Self, Function.HashCode, new Code[0],
                    0),
                0));
        any_methods.put(definitions.HASHCODE, hashCode_code);
        any_methods.put(hashCode_method, hashCode_code);
        environment.insertFunction(definitions.HASHCODE,
            Function.JavaMethod(hashCode_method));
        Override hashCode_override = Override.empty();
        hashCode_override.insert(hashCode_method).insert(definitions.HASHCODE);
        environment.insertOverride(definitions.HASHCODE, hashCode_override);

        // !!! method java.lang.Object.notify()
        // !!! method java.lang.Object.notifyAll()

        java.lang.reflect.Method toString_method = null;
        try {
            toString_method = Object.class.getMethod("toString", new Class[0]);
        } catch (Exception exception) {
            throw Debug.abort("toString", exception);
        }
        CodePromise toString_code = new CodePromise(
            new CodeContainer(
                compiled,
                definitions.TOSTRING,
                Code.Invoke(
                    Code.Self, Function.ToString, new Code[0],
                    0),
                0));
        any_methods.put(definitions.TOSTRING, toString_code);
        any_methods.put(toString_method, toString_code);
        environment.insertFunction(definitions.TOSTRING,
            Function.JavaMethod(toString_method));
        Override toString_override = Override.empty();
        toString_override.insert(toString_method).insert(definitions.TOSTRING);
        environment.insertOverride(definitions.TOSTRING, toString_override);

        // !!! method java.lang.Object.wait()
        // !!! method java.lang.Object.wait(long)
        // !!! method java.lang.Object.wait(long, int)

        environment.insertFunction(definitions.EQEQ, Function.EqEq);
        Override eqeq_override = Override.empty().insert(definitions.EQEQ);
        environment.insertOverride(definitions.EQEQ, eqeq_override);

        environment.insertFunction(definitions.BANGEQ, Function.BangEq);
        Override bangeq_override = Override.empty().insert(definitions.BANGEQ);
        environment.insertOverride(definitions.BANGEQ, bangeq_override);
    }

    //########################################################################

    public ScalaTemplate load(Symbol symbol, Tree.ClassDef tree) {
        SourceFile source = (SourceFile)sources.remove(tree);
        assert tree != null : Debug.show(symbol);
        return load(source, symbol, tree);
    }

    public ScalaTemplate load(SourceFile source, Symbol symbol, Tree.ClassDef tree) {
        assert tree.tparams   .length == 0 : Debug.show(tree);
        assert tree.vparams   .length == 1 : Debug.show(tree);
        assert tree.vparams[0].length == 0 : Debug.show(tree);
        return compileTemplate(source, symbol, tree.impl.body);
    }

    public ScalaTemplate compileTemplate(SourceFile source, Symbol symbol, Tree[] body) {
        Set supertypes = new HashSet();
        List interfaces = new ArrayList();
        getTypes(supertypes, interfaces, symbol);
        Class[] proxytype = new Class[interfaces.size()];
        interfaces.toArray(proxytype);
        Class proxy = Proxy.getProxyClass(loader, proxytype);
        Function constructor = getConstructor(proxy);
        Type[] bases = symbol.parents();
        Map methods;
        List fields;
        if (symbol.isInterface() || bases.length == 0) {
            methods = new HashMap(any_methods);
            fields = new ArrayList();
        } else {
            Template template = environment.lookupTemplate(bases[0].symbol());
            switch (template) {

            case Global(ScalaTemplate template_): // !!!
                methods = template_.getMethods();
                fields = template_.getFields();
                break;

            case JavaClass(_):
                methods = new HashMap(any_methods);
                fields = new ArrayList();
                break;

            default:
                throw Debug.abort("illegal case", template);
            }
        }
        for (int i = 0; i < body.length; i++) {
            addTemplateMember(source, methods, fields, body[i]);
        }
        return new ScalaTemplate(evaluator, symbol, constructor, methods, fields.toArray());
    }

    //########################################################################

    public Variable getModule(Symbol symbol) {
        return environment.lookupVariable(symbol);
    }

    public Function getMethod(Symbol symbol) {
        return environment.lookupFunction(symbol);
    }

    public CodePromise compile(SourceFile source, Symbol symbol, Tree.DefDef tree) {
        assert tree.tparams.length == 0 : Debug.show(tree);
        assert tree.vparams.length == 1 : Debug.show(tree);
        return new CodePromise(new ScalaFunction(this, source, symbol, tree.vparams[0],tree.rhs));
    }

    //########################################################################
    // 

    public void compile(Unit[] units) {
        for (int i = 0; i < units.length; i++) declare(units[i].source, units[i].body);
    }

    public CodeContainer compile(SourceFile source, Symbol owner, Tree tree, Symbol[] params) {
        ExpressionContext context = new ExpressionContext(environment, source, owner);
        ExpressionCompiler worker = new ExpressionCompiler(definitions, constants, context, params);
        return worker.compile(tree);
    }

    //########################################################################
    // Private Methods -

    private void declare(SourceFile source, Tree[] trees) {
        for (int i = 0; i < trees.length; i++) declare(source, trees[i]);
    }

    private void declare(SourceFile source, Tree tree) {
        Symbol symbol = tree.symbol();
        switch (tree) {

        case Empty:
            return;

        case ClassDef(_, _, _, _, _, _):
            sources.put(tree, source);
            environment.insertClassDef(symbol, (Tree.ClassDef)tree);
            return;

        // !!! these could be removed
        case PackageDef(Tree packaged, Tree.Template(Tree[] bases, Tree[] body)):
            assert packaged.symbol().isPackage() : Debug.show(tree); // !!! was isJavaPackage
            assert bases.length == 0 : Debug.show(tree);
            declare(source, body);
            return;

        case ValDef(_, _, _, Tree body):
            assert symbol.isModule() : Debug.show(symbol);
            environment.insertVariable(symbol, Variable.Module(new CodePromise(new ModuleBuilder(this, source, symbol, body)), null));
            return;

        default:
            throw Debug.abort("illegal case", tree);
        }
    }

    //########################################################################
    // Private Methods -

    private Symbol newGlobalVariable(Type type, Object value) {
        Symbol symbol = new TermSymbol(
            0, Name.fromString(value.toString()), definitions.ROOT, 0);
        symbol.setInfo(type);
        environment.insertVariable(symbol, Variable.Global(value));
        return symbol;
    }

    //########################################################################
    // Private Methods - template types

    private void getTypes(Set supertypes, List interfaces, Symbol type) {
        if (supertypes.contains(type)) return; else supertypes.add(type);
        if (!type.isPreloaded()) {
            Type[] basetypes = type.parents();
            for (int i = 0; i < basetypes.length; i++) {
                getTypes(supertypes, interfaces, basetypes[i].symbol());
            }
            return;
        } else {
        Template template = environment.lookupTemplate(type);
        switch (template) {
        case JavaClass(Class clasz):
            getTypes(supertypes, interfaces, clasz);
            return;
        default:
            Debug.abort("illegal template", template);
        }
        }
    }

    private void getTypes(Set supertypes, List interfaces, Class type) {
        if (supertypes.contains(type)) return; else supertypes.add(type);
        if (type.isInterface()) {
            interfaces.add(type);
        } else {
            Class supertype = type.getSuperclass();
            if (supertype != null) getTypes(supertypes, interfaces, supertype);
            Class[] basetypes = type.getInterfaces();
            for (int i = 0; i < basetypes.length; i++) {
                getTypes(supertypes, interfaces, basetypes[i]);
            }
        }
    }

    //########################################################################
    // Private Methods - template constructors

    private static final Class[] CSTR_PARAMS =
        new Class[] { InvocationHandler.class }; // !!! remove ?

    private Function getConstructor(Class proxy) {
        try {
            return Function.JavaConstructor(proxy.getConstructor(CSTR_PARAMS));
        } catch (NoSuchMethodException exception) {
            throw Debug.abort(exception);
        }
    }

    //########################################################################
    // Private Methods - template members

    private void addTemplateMember(SourceFile source, Map methods, List fields, Tree tree) {
        Symbol symbol = tree.symbol();
        switch (tree) {

        case Empty:
            return;

        case ClassDef(_, _, _, _, _, _):
            sources.put(tree, source);
            environment.insertClassDef(symbol, (Tree.ClassDef)tree);
            return;

        case ValDef(_, _, _, Tree body):
            assert body == Tree.Empty : Debug.show(tree);
            assert !symbol.isModule() : Debug.show(tree);
            int index = fields.size();
            fields.add(constants.zero(symbol.type()));
            environment.insertVariable(symbol, Variable.Member(index));
            return;

        case DefDef(_, _, _, _, _, _):
            assert !methods.containsKey(symbol) : Debug.show(symbol);
            CodePromise function = compile(source, symbol, (Tree.DefDef)tree);
            Override override = environment.lookupOverride(symbol);
            for (Iterator i = override.iterator(); i.hasNext(); ) {
                methods.put(i.next(), function);
            }
            environment.insertFunction(symbol, Function.Member(symbol));
            return;

        default:
            throw Debug.abort("illegal tree", tree);
        }
    }

    //########################################################################
    // !!!

    public static class ModuleBuilder extends CodeGenerator {

        private final Compiler compiler;
        private final SourceFile source;
        private final Symbol symbol;
        private final Tree body;

        public ModuleBuilder(Compiler compiler, SourceFile source, Symbol symbol, Tree body) {
            this.compiler = compiler;
            this.source = source;
            this.symbol = symbol;
            this.body = body;
        }

        public CodeContainer generate() {
            return compiler.compile(source, symbol, body, Symbol.EMPTY_ARRAY);
        }
    }

    //########################################################################
}
