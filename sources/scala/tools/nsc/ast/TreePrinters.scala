/*     ____ ____  ____ ____  ______                                     *\
**    / __// __ \/ __// __ \/ ____/    SOcos COmpiles Scala             **
**  __\_ \/ /_/ / /__/ /_/ /\_ \       (c) 2002, LAMP/EPFL              **
** /_____/\____/\___/\____/____/                                        **
**                                                                      **
\*                                                                      */

// $Id: TreePrinter.scala

package scala.tools.nsc.ast;

import java.io._;
import symtab.Flags._;

abstract class TreePrinters {

  val global: Global;
  import global._;

  class TreePrinter(out: PrintWriter) {
    protected var indentMargin = 0;
    protected val indentStep = 2;
    protected var indentString = "                                        ";

    def flush = out.flush();

    def indent = indentMargin = indentMargin + indentStep;
    def undent = indentMargin = indentMargin - indentStep;

    def println = {
      out.println();
      while (indentMargin > indentString.length()) 
        indentString = indentString + indentString;
      if (indentMargin > 0) 
        out.write(indentString, 0, indentMargin);
    }

    def printSeq[a](ls: List[a])(printelem: a => unit)(printsep: => unit): unit = ls match {
      case List() =>
      case List(x) => printelem(x)
      case x :: rest => printelem(x); printsep; printSeq(rest)(printelem)(printsep)
    }

    def printColumn(ts: List[Tree], start: String, sep: String, end: String): unit = {
      print(start); indent; println;
      printSeq(ts){print}{print(sep); println}; undent; println; print(end)
    }

    def printRow(ts: List[Tree], start: String, sep: String, end: String): unit = {
      print(start); printSeq(ts){print}{print(sep)}; print(end)
    }

    def printRow(ts: List[Tree], sep: String): unit = printRow(ts, "", sep, "");

    def printTypeParams(ts: List[AbsTypeDef]): unit = 
      if (!ts.isEmpty) {
        print("["); printSeq(ts){printParam}{print(", ")}; print("]")
      }

    def printValueParams(ts: List[ValDef]): unit = 
      if (!ts.isEmpty) {
        print("("); printSeq(ts){printParam}{print(", ")}; print(")")
      }

    def printParam(tree: Tree): unit = tree match {
      case ValDef(mods, name, tp, rhs) => 
        if ((mods & PARAMACCESSOR) != 0) {
          print(tree)
        } else {
          print(symName(tree, name)); printOpt(": ", tp); 
        }
      case AbsTypeDef(mods, name, lo, hi) =>
        print(symName(tree, name)); 
        printOpt(">: ", lo); printOpt("<: ", hi); 
    }

    def symName(tree: Tree, name: Name): String = 
      if (tree.symbol != null && tree.symbol != NoSymbol) tree.symbol.nameString 
      else name.toString();

    def printOpt(prefix: String, tree: Tree): unit =
      if (tree != EmptyTree) { print(prefix); print(tree) }

    def printModifiers(flags: int): unit = {
      val s = flagsToString(flags);
      if (s.length() != 0) print(s + " ")
    }

    def print(str: String): unit = out.print(str);
    def print(name: Name): unit = print(name.toString());

    def print(tree: Tree): unit = {
      tree match {
        case EmptyTree =>
          print("<empty>");

        case ClassDef(mods, name, tparams, tp, impl) => 
          printModifiers(mods); print("class " + symName(tree, name));
          printTypeParams(tparams); 
          printOpt(": ", tp); print(" extends "); print(impl);

        case PackageDef(packaged, stats) =>
          print("package "); print(packaged); printColumn(stats, " {", ";", "}")

        case ModuleDef(mods, name, tpe, impl) =>
          printModifiers(mods); print("object " + symName(tree, name));
          printOpt(": ", tpe); print(" extends "); print(impl);

        case ValDef(mods, name, tp, rhs) => 
          printModifiers(mods);
          print(if ((mods & MUTABLE) != 0) "var " else "val ");
          print(symName(tree, name));
          printOpt(": ", tp); 
          if ((mods & DEFERRED) == 0) {
            print(" = ");
            if (rhs == EmptyTree) print("_") else print(rhs)
          }

        case DefDef(mods, name, tparams, vparams, tp, rhs) =>
          printModifiers(mods);
          print("def " + (if (name == nme.CONSTRUCTOR) "this" else symName(tree, name)));
          printTypeParams(tparams); vparams foreach printValueParams;
          printOpt(": ", tp); printOpt(" = ", rhs);

        case AbsTypeDef(mods, name, lo, hi) =>
          printModifiers(mods); print("type "); printParam(tree);

        case AliasTypeDef(mods, name, tparams, rhs) =>
          printModifiers(mods); print("type " + symName(tree, name));
          printTypeParams(tparams); printOpt(" = ", rhs);

        case LabelDef(name, params, rhs) =>
          print(symName(tree, name)); printRow(params, "(", ",", ")"); print(rhs);

        case Import(expr, selectors) =>
          def printSelectors(ss: List[Pair[Name, Name]]): unit = ss match {
            case List(Pair(nme.WILDCARD, _)) => print("_");
            case Pair(s1, s2) :: ss1 => 
              print(s1);
              if (s1 != s2) print("=>" + s2);
              if (!ss1.isEmpty) {
                print(", "); printSelectors(ss1);
              }
          }
          print("import "); print(expr); print(".{"); printSelectors(selectors); print("}");

        case PatDef(mods, pat, rhs) =>
          print(flagsToString(mods) + " val "); print(pat); printOpt(" = ", rhs);

        case Attributed(attr, definition) =>
          print("["); print(attr); print("]"); println; print(definition);

        case DocDef(comment, definition) =>
          print(comment); println; print(definition);

        case Template(parents, body) =>
          printRow(parents, " with "); printColumn(body, " {", ";", "}");

        case Block(stats, expr) =>
          printColumn(stats ::: List(expr), "{", ";", "}")

        case Visitor(cases) => 
          printColumn(cases, "{", "", "}"); 

        case CaseDef(pat, guard, body) =>
          print("case "); print(pat); printOpt(" if ", guard); 
          print(" => "); print(body)

        case Sequence(trees) =>
          printRow(trees, "[", ", ", "]")

        case Alternative(trees) =>
          printRow(trees, "(", "| ", ")")

        case Bind(name, t) =>
          print(symName(tree, name)); print(" @ "); print(t)

        case Function(vparams, body) =>
          print("("); printValueParams(vparams); print(" => "); print(body); print(")")

        case Assign(lhs, rhs) =>
          print(lhs); print(" = "); print(rhs)

        case For(enumerators, body, isForYield) =>
          printRow(enumerators, "for(", "; ", ") ");
          if (isForYield) print("yield ");
          print(body)

        case If(cond, thenp, elsep) =>
          print("if ("); print(cond); print(")"); indent; println;
          print(thenp); undent;  
          if (elsep != EmptyTree) {
            println; print("else"); indent; println; print(elsep); undent
          }

        case Switch(expr, tags, bodies, defaultBody) =>
          print("<switch> ("); print(expr); print(") {"); indent;
          printSeq(tags zip bodies)
            {case Pair(tag, body) => print("case " + tag + " => "); print(body)} {println}
          print("<default> => "); print(defaultBody); print(" }"); undent

        case Return(expr) =>
          print("return "); print(expr)

        case Try(block, catcher, finalizer) =>
          print("try "); print(block); print(" catch "); print(catcher);
          print(" finally "); print(finalizer)

        case Throw(expr) =>
          print("throw "); print(expr)

        case New(init) =>
          print("new "); print(init)

        case Typed(expr, tp) =>
          print("("); print(expr); print(") : "); print(tp);

        case TypeApply(fun, targs) =>
          print(fun); printRow(targs, "[", ", ", "]");

        case Apply(fun, vargs) =>
          print(fun); printRow(vargs, "(", ", ", ")");

        case Super(qual, mixin) =>
          if (qual != nme.EMPTY.toTypeName) print(symName(tree, qual) + ".");
          print("super");
          if (mixin != nme.EMPTY.toTypeName) print("[" + mixin + "]")

        case This(qual) =>
          if (qual != nme.EMPTY.toTypeName) print(symName(tree, qual) + ".");
          print("this");

        case Select(qualifier, name) =>
          if (global.settings.debug.value || qualifier.symbol == null || !qualifier.symbol.isRoot) {
            print(qualifier); print(".");
          }
          print(symName(tree, name))

        case Ident(name) =>
          print(symName(tree, name))

        case Literal(obj) =>
          print(obj match {
            case null => "null" 
            case s: String => "\"" + s + "\""
            case _ => obj.toString()
          })

        case EmptyTypeTree() =>
          print(tree.tpe.toString());

        case SingletonTypeTree(ref) =>
          print(ref); print(".type")

        case SelectFromTypeTree(qualifier, selector) =>
          print(qualifier); print("#"); print(symName(tree, selector))

        case FunctionTypeTree(argtpes, restpe) =>
          printRow(argtpes, "(", ", ", ") => "); print(restpe)

        case IntersectionTypeTree(parents) =>
          printRow(parents, " with ")

        case RefinementTypeTree(base, members) =>
          print(base); printColumn(members, "{", ";", "}")

        case AppliedTypeTree(tp, args) =>
          print(tp); printRow(args, "[", ", ", "]")
      }
      if (global.settings.printtypes.value && tree.isTerm && tree != EmptyTree) {
        print("{"); print(if (tree.tpe == null) "<null>" else tree.tpe.toString()); print("}")
      }
    }

    def print(unit: CompilationUnit): unit = {
      print("// Scala source: " + unit.source + "\n");
      if (unit.body != null) {
        unit.body foreach { tree => print(tree); print(";"); println }
      } else {
        print("<null>")
      }
      println; flush
    }

    def printAll(): unit = {
      print("[[syntax trees at end of " + phase + "]]");
      for (val unit <- global.units) print(unit)
    }
  }

  def create(writer: PrintWriter): TreePrinter = new TreePrinter(writer);
  def create(stream: OutputStream): TreePrinter = create(new PrintWriter(stream));
  def create(): TreePrinter = create(System.out);
}
