/*     ____ ____  ____ ____  ______                                     *\
**    / __// __ \/ __// __ \/ ____/    SOcos COmpiles Scala             **
**  __\_ \/ /_/ / /__/ /_/ /\_ \       (c) 2002, LAMP/EPFL              **
** /_____/\____/\___/\____/____/                                        **
\*                                                                      */

// $Id$

// TODO try to use setInfo instead of updateInfo for cloned symbols,
// to avoid the need to use nextPhase/nextInfo.

package scalac.transformer;

import scalac.*;
import scalac.symtab.*;
import scalac.checkers.*;
import scalac.util.Name;
import java.util.*;
import scalac.util.Debug;

public class AddInterfacesPhase extends PhaseDescriptor {
    public String name () {
        return "addinterfaces";
    }

    public String description () {
        return "add one interface per class";
    }

    public String taskDescription() {
        return "added interfaces";
    }

    public void apply(Global global) {
        new AddInterfaces(global, this).apply();
    }
    
    public void apply(Unit unit) {
        new AddInterfaces(unit.global, this).apply(unit);
    }
    
    public Type transformInfo(Symbol sym, Type tp) {
        if (sym.isConstructor()) {
            Symbol clazz =sym.primaryConstructorClass();
            if (clazz.isPackage() || !needInterface(clazz)) return tp;
            // The symbol is a constructor of a class which needs
            // an interface. All its value arguments have to be
            // removed.
            return removeValueParams(tp);
        } else if (sym.isClass() && !sym.isJava()) {
            Definitions definitions = Global.instance.definitions;
            Type[] oldParents = tp.parents();
            assert oldParents.length > 0 : Debug.show(sym);
            for (int i = 1; i < oldParents.length; ++i) {
                Symbol oldSym = oldParents[i].symbol();
                assert !oldSym.isJava() || oldSym.isInterface() :
                    Debug.show(sym) + " <: " + Debug.show(oldSym);
            }

            Type[] newParents;
            Scope newMembers;
            if (needInterface(sym)) {
                // Before this phase, the symbol is a class, but after
                // it will be an interface. Its type has to be changed
                // so that:
                //
                //   1. Java classes are removed from its parents,
                //
                //   2. only members which will end up in the
                //      interface are kept, and private ones are made
                //      public and renamed.
                sym.flags |= Modifiers.INTERFACE;

                Scope.SymbolIterator oldMembersIt =
                    new Scope.UnloadIterator(tp.members().iterator());
                newMembers = new Scope();
                while (oldMembersIt.hasNext()) {
                    Symbol member = oldMembersIt.next();

                    if (!memberGoesInInterface(member))
                        continue;

                    if (member.isPrivate()) {
                        member.name = uniqueName(member);
                        member.flags ^= Modifiers.PRIVATE;
                    } else if (member.isProtected())
                        member.flags ^= Modifiers.PROTECTED;

                    newMembers.enterOrOverload(member);
                }

                Symbol oldSym = oldParents[0].symbol();
                if (oldSym.isJava() && !oldSym.isInterface() &&
                    oldSym != definitions.ANY_CLASS &&
                    oldSym != definitions.ANYREF_CLASS)
                {
                    newParents = new Type[oldParents.length];
                    newParents[0] = definitions.ANYREF_TYPE;
                    for (int i = 1; i < oldParents.length; ++i)
                        newParents[i] = oldParents[i];
                } else {
                    newParents = oldParents;
                }
            } else {
                // The symbol is the one of a class which doesn't need
                // an interface. We need to fix its parents to use
                // class symbols instead of interface symbols.
                newMembers = tp.members();
                newParents = new Type[oldParents.length];
                for (int i = 0; i < oldParents.length; ++i) {
                    switch (oldParents[i]) {
                    case TypeRef(Type pre, Symbol oldSym, Type[] args):
                        newParents[i] = !needInterface(oldSym) ? oldParents[i]:
                            Type.typeRef(pre, getClassSymbol(oldSym), args);
                        break;
                    default:
                        throw Debug.abort("illegal case", oldParents[i]);
                    }
                }
            }

            return Type.compoundType(newParents, newMembers, sym);
        } else
            return tp;
    }

    public Checker[] postCheckers(Global global) {
        return new Checker[] {
            new CheckSymbols(global),
            new CheckTypes(global),
            new CheckOwners(global),
	    new CheckNames(global)
        };
    }

    protected boolean memberGoesInInterface(Symbol member) {
        return member.isType()
            || (member.isMethod() && !member.isPrimaryConstructor());
    }

    protected boolean memberMovesToInterface(Symbol member) {
        return member.isType();
    }

    protected Type removeValueParams(Type tp) {
        switch (tp) {
        case MethodType(Symbol[] vparams, Type result):
            return new Type.MethodType(Symbol.EMPTY_ARRAY, result);
        case PolyType(Symbol[] tps, Type result):
            return new Type.PolyType(tps, removeValueParams(result));
        default:
            return tp;
        }
    }

    protected void uniqueName(Symbol sym, StringBuffer buf) {
        Symbol owner = sym.owner();

        if (owner != Symbol.NONE) {
            uniqueName(owner, buf);
            buf.append('$');
        }

        buf.append(sym.name.toString());
    }

    protected Name uniqueName(Symbol sym) {
        StringBuffer buf = new StringBuffer();
        uniqueName(sym, buf);
        Name newName = Name.fromString(buf.toString());
        if (sym.name.isTypeName()) return newName.toTypeName();
        else if (sym.name.isConstrName()) return newName.toConstrName();
        else return newName;
    }

    // Terminology: in the following code, the symbol which was used
    // until this phase for the class, and will now be used for the
    // interface is called the "interface symbol". The symbol created
    // by this phase for the class is called the "class symbol".

    /** True iff the given class symbol needs an interface. */
    protected boolean needInterface(Symbol classSym) {
        assert classSym.isClass()
            : Debug.toString(classSym) + " is not a class (kind " + classSym.kind + ")";
        return !(classSym.isJava()
                 || classSym.isModuleClass()
                 || classSym.isAnonymousClass()
                 || hasInterfaceSymbol(classSym)
                 || classSym == Global.instance.definitions.ANY_CLASS
                 || classSym == Global.instance.definitions.ANYREF_CLASS
                 || classSym == Global.instance.definitions.ALL_CLASS
                 || classSym == Global.instance.definitions.ALLREF_CLASS);
    }

    protected final static String CLASS_SUFFIX = "$class";
    protected Name className(Name ifaceName) {
        Name className = Name.fromString(ifaceName.toString() + CLASS_SUFFIX);
        if (ifaceName.isTypeName()) return className.toTypeName();
        else if (ifaceName.isConstrName()) return className.toConstrName();
        else return className;
    }

    protected HashMap ifaceToClass = new HashMap();
    protected HashMap classToIFace = new HashMap();

    /** Return the class symbol corresponding to the given interface
     * symbol. If the class does not need an interface, return the
     * given symbol.
     */
    protected Symbol getClassSymbol(Symbol ifaceSym) {
        if (ifaceSym.isPrimaryConstructor())
            return getClassSymbol(ifaceSym.primaryConstructorClass())
                .constructor();

        if (!needInterface(ifaceSym))
            return ifaceSym;

        Symbol classSym = (Symbol)ifaceToClass.get(ifaceSym);
        if (classSym == null) {
            classSym = ifaceSym.cloneSymbol(ifaceSym.owner());
            classSym.name = className(ifaceSym.name);
            classSym.flags &= ~Modifiers.INTERFACE;

            Symbol ifaceConstrSym = ifaceSym.constructor();
            Symbol classConstrSym = classSym.constructor();
            classConstrSym.name = className(ifaceConstrSym.name);

            Scope ifaceOwnerMembers = ifaceSym.owner().members();
            ifaceOwnerMembers.enter(classSym);
            ifaceOwnerMembers.enter(classConstrSym);

            Type.SubstThisMap thisTypeMap =
                new Type.SubstThisMap(ifaceSym, classSym);

            // Create class substitution map.
            SymbolSubstTypeMap classSubst = newClassSubst(classSym);
            classSubst.insertSymbol(
                ifaceConstrSym.typeParams(), classConstrSym.typeParams());
            classSubst.insertSymbol(
                ifaceConstrSym.valueParams(), classConstrSym.valueParams());

            // Clone all members, entering them in the class scope.
            Map classMembersMap = newClassMemberMap(classSym);
            Scope classMembers = new Scope();
            Scope.SymbolIterator ifaceMembersIt =
                new Scope.UnloadIterator(ifaceSym.members().iterator());
            while (ifaceMembersIt.hasNext()) {
                Symbol ifaceMemberSym = ifaceMembersIt.next();

                if (memberMovesToInterface(ifaceMemberSym)
                    || ifaceMemberSym.isPrimaryConstructor())
                    continue;

                Symbol classMemberSym;
                if (memberGoesInInterface(ifaceMemberSym)) {
                    if (ifaceMemberSym.isPrivate()) {
                        ifaceMemberSym.name = uniqueName(ifaceMemberSym);
                        ifaceMemberSym.flags ^= Modifiers.PRIVATE;
                    } else if (ifaceMemberSym.isProtected())
                        ifaceMemberSym.flags ^= Modifiers.PROTECTED;

                    classMemberSym = ifaceMemberSym.cloneSymbol(classSym);
                    classMemberSym.setInfo(
                        thisTypeMap.applyParams(
                            classSubst.applyParams(
                                classMemberSym.info().cloneType(
                                    ifaceMemberSym, classMemberSym))));
                    classMembersMap.put(ifaceMemberSym, classMemberSym);

                    ifaceMemberSym.flags |= Modifiers.DEFERRED;
                } else {
                    // Member doesn't go in interface, we just make it
                    // owned by the class.
                    classMemberSym = ifaceMemberSym;
                    classMemberSym.setOwner(classSym);
                    classMemberSym.updateInfo(
                        thisTypeMap.apply(
                            classSubst.apply(
                                classMemberSym.info())));
                }

                classMembers.enterOrOverload(classMemberSym);
                if (classMemberSym.isClass())
                    classMembers.enterOrOverload(classMemberSym.constructor());
            }

            // Give correct type to the class symbol by using class
            // symbols for its parents, and by adding the interface
            // among them.
            Type[] oldClassParents = classSym.parents();
            int oldParentsCount = oldClassParents.length;
            Type[] newClassParents = new Type[oldParentsCount + 1];
            for (int i = 0; i < oldParentsCount; ++i) {
                switch (oldClassParents[i]) {
                case TypeRef(Type pre, Symbol sym, Type[] args):
                    Type newTp = Type.typeRef(pre, getClassSymbol(sym), args);
                    newClassParents[i] = classSubst.apply(newTp);
                    break;
                default:
                    throw Debug.abort("unexpected type for parent", oldClassParents[i]);
                }
            }
            newClassParents[oldParentsCount] = classSubst.apply(ifaceSym.type());
            // TODO setInfo cannot be used here because the type then
            // goes through transformInfo. Maybe setInfo should behave
            // like updateInfo.
            classSym.updateInfo(Type.compoundType(newClassParents,
                                                  classMembers,
                                                  classSym));
            ifaceToClass.put(ifaceSym, classSym);
            classToIFace.put(classSym, ifaceSym);
        }
        return classSym;
    }

    public boolean hasInterfaceSymbol(Symbol classSym) {
        return classToIFace.containsKey(classSym);
    }

    public Symbol getInterfaceSymbol(Symbol classSym) {
        return (Symbol)classToIFace.get(classSym);
    }

    HashMap/*<Symbol,SymbolSubstTypeMap>*/ classSubstitutions = new HashMap();
    protected SymbolSubstTypeMap newClassSubst(Symbol classSym) {
        SymbolSubstTypeMap subst = new SymbolSubstTypeMap();
        classSubstitutions.put(classSym, subst);
        return subst;
    }

    /** Return symbol substitution for the class (a mapping from the
     * interface's type and value parameters to the class' equivalent)
     */
    public SymbolSubstTypeMap getClassSubst(Symbol classSym) {
        SymbolSubstTypeMap classSubst = (SymbolSubstTypeMap)classSubstitutions.get(classSym);
        assert classSubst != null;
        return classSubst;
    }

    HashMap/*<Symbol, HashMap>*/ classMemberMaps = new HashMap();
    protected HashMap newClassMemberMap(Symbol classSym) {
        HashMap map = new HashMap();
        classMemberMaps.put(classSym, map);
        return map;
    }

    /** Return symbol substitution for the class (a mapping from the
     * interface's type and value parameters to the class' equivalent)
     */
    public Map getClassMemberMap(Symbol classSym) {
        return (Map)classMemberMaps.get(classSym);
    }

}
