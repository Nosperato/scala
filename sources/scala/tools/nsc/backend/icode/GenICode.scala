/* NSC -- new scala compiler
 * Copyright 2005 LAMP/EPFL
 * @author  Martin Odersky
 */

// $Id$

package scala.tools.nsc.backend.icode;

import scala.collection.mutable.{Map, HashMap};
import scala.tools.nsc.symtab._;

abstract class GenICode extends SubComponent  {
  import global._;
  import icodes._;
  import icodes.opcodes._;

  val phaseName = "genicode";

  override def newPhase(prev: Phase) = new ICodePhase(prev);

  class ICodePhase(prev: Phase) extends StdPhase(prev) {
//    import Primitives._;
    override def name = "genicode";

    override def description = "Generate ICode from the AST";
    
    var unit: CompilationUnit = _;

    scalaPrimitives.initPrimitives;
    
    val STRING = REFERENCE(definitions.StringClass);
    val ANY_REF_CLASS = REFERENCE(definitions.AnyRefClass);

    ///////////////////////////////////////////////////////////

    override def apply(unit: CompilationUnit): Unit = {
      this.unit = unit;
      log("Generating icode for " + unit);
      gen(unit.body);
    }

    def gen(tree: Tree): Context = gen(tree, new Context());

    def gen(trees: List[Tree], ctx: Context): Context = {
      var ctx1 = ctx;
      for (val t <- trees)
        ctx1 = gen(t, ctx1);

      ctx1
    }

    /////////////////// Code generation ///////////////////////

    def gen(tree: Tree, ctx: Context): Context = tree match {
      case EmptyTree => ctx;
      
      case PackageDef(name, stats) => gen(stats, ctx setPackage name);
        
      case ClassDef(mods, name, tparams, tpt, impl) => 
        ctx setClass (new IClass(tree.symbol) setCompilationUnit unit);
        addClassFields(ctx, tree.symbol);
        classes = ctx.clazz :: classes;
        gen(impl, ctx);
        ctx setClass null;

      // !! modules should be eliminated by refcheck... or not?
      case ModuleDef(mods, name, impl) =>
        ctx setClass (new IClass(tree.symbol) setCompilationUnit unit);
        classes = ctx.clazz :: classes;
        gen(impl, ctx);
        ctx setClass null;       

      case ValDef(mods, name, tpt, rhs) => ctx; // we use the symbol to add fields
        
      case DefDef(mods, name, tparams, vparamss, tpt, rhs) =>
        log("Entering method " + name);
        val m = new IMethod(tree.symbol);
        ctx.clazz.addMethod(m);
        var ctx1 = ctx.enterMethod(m, tree.asInstanceOf[DefDef]);
        addMethodParams(ctx1, vparamss);
        ctx1 = genLoad(rhs, 
                       ctx1, 
                       if (tree.symbol.isConstructor) 
                         UNIT 
                       else
                         toTypeKind(ctx1.method.symbol.info.resultType));
        ctx1.bb.emit(RETURN());
        ctx1;

      case Template(parents, body) =>
        gen(body, ctx);

      case _ => 
        abort("Illegal tree in gen: " + tree);

    
/*  case AbsTypeDef(mods, name, lo, hi) =>                          (eliminated by erasure)
  case AliasTypeDef(mods, name, tparams, rhs) =>                  (eliminated by erasure)
  case Import(expr, selectors) =>                                 (eliminated by typecheck)
  case Attributed(attribute, definition) =>                       (eliminated by typecheck)
  case DocDef(comment, definition) =>                             (eliminated by typecheck)
  case CaseDef(pat, guard, body) =>                               (eliminated by transmatch)
  case Sequence(trees) =>                                         (eliminated by transmatch)
  case Alternative(trees) =>                                      (eliminated by transmatch)
  case Star(elem) =>                                              (eliminated by transmatch)
  case Bind(name, body) =>                                        (eliminated by transmatch)
  case ArrayValue(elemtpt, trees) =>                              (introduced by uncurry)
  case Function(vparams, body) =>                                 (eliminated by typecheck)
  case Typed(expr, tpt) =>                                         (eliminated by erasure)
  case TypeApply(fun, args) =>
  case SingletonTypeTree(ref) =>                                  (eliminated by typecheck)
  case SelectFromTypeTree(qualifier, selector) =>                 (eliminated by typecheck)
  case CompoundTypeTree(templ: Template) =>                        (eliminated by typecheck)
  case AppliedTypeTree(tpt, args) =>                               (eliminated by typecheck)
*/
    }

    private def genStat(trees: List[Tree], ctx: Context): Context = {
      var currentCtx = ctx;

      for (val t <- trees) 
        currentCtx = genStat(t, currentCtx);

      currentCtx
    }
    
    /**
     * Generate code for the given tree. The trees should contain statements
     * and not produce any value. Use genLoad for expressions which leave
     * a value on top of the stack.
     *
     * @return a new context. This is necessary for control flow instructions
     * which may change the current basic block.
     */
    private def genStat(tree: Tree, ctx: Context): Context = tree match {
      case Assign(lhs @ Select(_, _), rhs) =>
        if (lhs.symbol.isStatic) {
          val ctx1 = genLoad(rhs, ctx, toTypeKind(lhs.symbol.info));
          ctx1.bb.emit(STORE_FIELD(lhs.symbol, true));
          ctx1
        } else {
          var ctx1 = genLoadQualifier(lhs, ctx);
          ctx1 = genLoad(rhs, ctx1, toTypeKind(lhs.symbol.info));
          ctx1.bb.emit(STORE_FIELD(lhs.symbol, false));
          ctx1
        }

        case Assign(lhs, rhs) =>
          assert(ctx.method.locals.contains(lhs.symbol), 
                 "Assignment to inexistent local: " + lhs.symbol);
          val ctx1 = genLoad(rhs, ctx, toTypeKind(lhs.symbol.info));
          ctx1.bb.emit(STORE_LOCAL(lhs.symbol, lhs.symbol.isValueParameter));
          ctx1

        case _ => 
          log("Passing " + tree + " to genLoad");
          genLoad(tree, ctx, UNIT);
    }    

    /**
     * Generate code for trees that produce values on the stack
     *
     * @param tree The tree to be translated
     * @param ctx  The current context
     * @param expectedType The type of the value to be generated on top of the
     *                     stack.
     * @return The new context. The only thing that may change is the current 
     *         basic block (as the labels map is mutable).
     */
    private def genLoad(tree: Tree, ctx: Context, expectedType: TypeKind): Context = {
      var generatedType = expectedType;

      /**
       * Generate code for primitive arithmetic operations.
       */
      def genArithmeticOp(tree: Tree, ctx: Context, code: Int): Context = {
        val Apply(fun @ Select(larg, _), args) = tree;
        var ctx1 = ctx;
        var resKind = toTypeKind(larg.tpe);

        assert(args.length <= 1,
               "Too many arguments for primitive function: " + fun.symbol);
        assert(resKind.isNumericType | resKind == BOOL,
               resKind.toString() + " is not a numeric or boolean type [operation: " + fun.symbol + "]");

        ctx1 = genLoad(larg, ctx1, resKind);
        args match {
          // unary operation
          case Nil => 
            code match {
              case scalaPrimitives.POS => (); // nothing 
              case scalaPrimitives.NEG => ctx.bb.emit(CALL_PRIMITIVE(Negation(resKind)));
              case scalaPrimitives.NOT => ctx.bb.emit(CALL_PRIMITIVE(Arithmetic(NOT, resKind)));
              case _ => abort("Unknown unary operation: " + fun.symbol);
            }
            generatedType = resKind;

          // binary operation
          case rarg :: Nil =>
            resKind = getMaxType(larg.tpe :: rarg.tpe :: Nil);
            if (scalaPrimitives.isShiftOp(code) || scalaPrimitives.isBitwiseOp(code))
              assert(resKind.isIntType | resKind == BOOL, 
                   resKind.toString() + " incompatible with arithmetic modulo operation: " + ctx1);

            ctx1 = genLoad(rarg, 
                           ctx1,  // check .NET size of shift arguments!
                           if (scalaPrimitives.isShiftOp(code)) INT else resKind);

            generatedType = resKind;
            code match {
              case scalaPrimitives.ADD => ctx1.bb.emit(CALL_PRIMITIVE(Arithmetic(ADD, resKind)));
              case scalaPrimitives.SUB => ctx1.bb.emit(CALL_PRIMITIVE(Arithmetic(SUB, resKind)));
              case scalaPrimitives.MUL => ctx1.bb.emit(CALL_PRIMITIVE(Arithmetic(MUL, resKind)));
              case scalaPrimitives.DIV => ctx1.bb.emit(CALL_PRIMITIVE(Arithmetic(DIV, resKind)));
              case scalaPrimitives.MOD => ctx1.bb.emit(CALL_PRIMITIVE(Arithmetic(REM, resKind)));
              case scalaPrimitives.OR  => ctx1.bb.emit(CALL_PRIMITIVE(Logical(OR, resKind)));
              case scalaPrimitives.XOR => ctx1.bb.emit(CALL_PRIMITIVE(Logical(XOR, resKind)));
              case scalaPrimitives.AND => ctx1.bb.emit(CALL_PRIMITIVE(Logical(AND, resKind)));
              case scalaPrimitives.LSL => ctx1.bb.emit(CALL_PRIMITIVE(Shift(LSL, resKind)));
                                          generatedType = INT;
              case scalaPrimitives.LSR => ctx1.bb.emit(CALL_PRIMITIVE(Shift(LSR, resKind)));
                                          generatedType = INT;
              case scalaPrimitives.ASR => ctx1.bb.emit(CALL_PRIMITIVE(Shift(ASR, resKind)));
                                          generatedType = INT;
              case _ => abort("Unknown primitive: " + fun.symbol + "[" + code + "]");
            }

          case _ => abort("Too many arguments for primitive function: " + tree);
        }
        ctx1
      }

      /** Generate primitive array operations. */
      def genArrayOp(tree: Tree, ctx: Context, code: Int): Context = {
        import scalaPrimitives._;
        val Apply(Select(arrayObj, _), args) = tree;
        var ctx1 = genLoad(arrayObj, ctx, toTypeKind(arrayObj.tpe));

        if (scalaPrimitives.isArrayGet(code)) {
           // load argument on stack
           assert(args.length == 1,
                  "Too many arguments for array get operation: " + tree);
           ctx1 = genLoad(args.head, ctx1, INT);
        } else if (scalaPrimitives.isArraySet(code)) {
          assert(args.length == 2,
                 "Too many arguments for array set operation: " + tree);
          ctx1 = genLoad(args.head, ctx1, INT);
          ctx1 = genLoad(args.tail.head, ctx1, toTypeKind(args.tail.head.tpe));
        }

        code match {
          case ZARRAY_LENGTH =>
            ctx1.bb.emit(CALL_PRIMITIVE(ArrayLength(BOOL)));
          case BARRAY_LENGTH =>
            ctx1.bb.emit(CALL_PRIMITIVE(ArrayLength(BYTE)));
          case SARRAY_LENGTH =>
            ctx1.bb.emit(CALL_PRIMITIVE(ArrayLength(SHORT)));
          case CARRAY_LENGTH =>
            ctx1.bb.emit(CALL_PRIMITIVE(ArrayLength(CHAR)));
          case IARRAY_LENGTH =>
            ctx1.bb.emit(CALL_PRIMITIVE(ArrayLength(INT)));
          case LARRAY_LENGTH =>
            ctx1.bb.emit(CALL_PRIMITIVE(ArrayLength(LONG)));
          case FARRAY_LENGTH =>
            ctx1.bb.emit(CALL_PRIMITIVE(ArrayLength(FLOAT)));
          case DARRAY_LENGTH =>
            ctx1.bb.emit(CALL_PRIMITIVE(ArrayLength(DOUBLE)));
          case OARRAY_LENGTH =>
            ctx1.bb.emit(CALL_PRIMITIVE(ArrayLength(ANY_REF_CLASS)));

          case ZARRAY_GET =>
            ctx1.bb.emit(LOAD_ARRAY_ITEM(BOOL));
          case BARRAY_GET =>
            ctx1.bb.emit(LOAD_ARRAY_ITEM(BYTE));
          case SARRAY_GET =>
            ctx1.bb.emit(LOAD_ARRAY_ITEM(SHORT));
          case CARRAY_GET =>
            ctx1.bb.emit(LOAD_ARRAY_ITEM(CHAR));
          case IARRAY_GET =>
            ctx1.bb.emit(LOAD_ARRAY_ITEM(INT));
          case LARRAY_GET =>
            ctx1.bb.emit(LOAD_ARRAY_ITEM(LONG));
          case FARRAY_GET =>
            ctx1.bb.emit(LOAD_ARRAY_ITEM(FLOAT));
          case DARRAY_GET =>
            ctx1.bb.emit(LOAD_ARRAY_ITEM(DOUBLE));
          case OARRAY_GET =>
            ctx1.bb.emit(LOAD_ARRAY_ITEM(ANY_REF_CLASS));

          case ZARRAY_SET =>
            ctx1.bb.emit(STORE_ARRAY_ITEM(BOOL));
          case BARRAY_SET =>
            ctx1.bb.emit(STORE_ARRAY_ITEM(BYTE));
          case SARRAY_SET =>
            ctx1.bb.emit(STORE_ARRAY_ITEM(SHORT));
          case CARRAY_SET =>
            ctx1.bb.emit(STORE_ARRAY_ITEM(CHAR));
          case IARRAY_SET =>
            ctx1.bb.emit(STORE_ARRAY_ITEM(INT));
          case LARRAY_SET =>
            ctx1.bb.emit(STORE_ARRAY_ITEM(LONG));
          case FARRAY_SET =>
            ctx1.bb.emit(STORE_ARRAY_ITEM(FLOAT));
          case DARRAY_SET =>
            ctx1.bb.emit(STORE_ARRAY_ITEM(DOUBLE));
          case OARRAY_SET =>
            ctx1.bb.emit(STORE_ARRAY_ITEM(ANY_REF_CLASS));

          case _ =>
            abort("Unknown operation on arrays: " + tree + " code: " + code);
        }
        ctx1
      }

      // genLoad
      val resCtx: Context = tree match {
        case LabelDef(name, params, rhs) =>
          ctx.method.addLocals(params map (.symbol));
          val ctx1 = ctx.newBlock;
          ctx1.labels.get(tree.symbol) match {
            case Some(label) => label.anchor(ctx1.bb);

            case None => 
              ctx1.labels += tree.symbol -> (new Label(tree.symbol) anchor ctx1.bb setParams (params map (.symbol)));
              log("Adding label " + tree.symbol);
          }
          ctx.bb.emit(JUMP(ctx1.bb));
          ctx.bb.close;
          genLoad(rhs, ctx1, toTypeKind(tree.symbol.info.resultType));

        case ValDef(_, _, _, rhs) =>
          assert(rhs != EmptyTree, 
                 "Uninitialized variable " + tree + " at: " + unit.position(tree.pos));
          val sym = tree.symbol;
          ctx.method.addLocal(sym);
          val ctx1 = genLoad(rhs, ctx, toTypeKind(sym.info));
          ctx1.bb.emit(STORE_LOCAL(sym, false));
          generatedType = UNIT;
          ctx1

        case If(cond, thenp, elsep) =>
          var thenCtx = ctx.newBlock;
          var elseCtx = ctx.newBlock;
          val contCtx = ctx.newBlock;
          genCond(cond, ctx, thenCtx, elseCtx);
          val ifKind = toTypeKind(tree.tpe);

          thenCtx = genLoad(thenp, thenCtx, ifKind);
          elseCtx = genLoad(elsep, elseCtx, ifKind);
          thenCtx.bb.emit(JUMP(contCtx.bb));
          elseCtx.bb.emit(JUMP(contCtx.bb));
          contCtx;
          
        case Return(expr) =>          
          val ctx1 = genLoad(expr, ctx, expectedType);
          ctx1.bb.emit(RETURN());
          ctx1
          
        case Try(block, catches, finalizer) =>
          ctx.bb.close;
          var ctx1 = ctx.newHandler.newBlock;
          val currentHandler = ctx.handlers.head;
          ctx1 = genLoad(block, ctx1, toTypeKind(block.tpe));
          assert(ctx1.handlers.head == currentHandler,
                 "Handler nesting violated. Expected: " + 
                 currentHandler + " found: " + ctx1.handlers.head);
          // TODO: generate code for handlers and finalizer
          ctx1
          


        case Throw(expr) =>
          val ctx1 = genLoad(expr, ctx, expectedType);
          ctx1.bb.emit(THROW());
          ctx;

        case New(tpt) =>
          ctx.bb.emit(NEW(tree.symbol));
          generatedType = REFERENCE(tree.symbol);
          ctx;

        case Apply(TypeApply(fun, targs), _) =>
          val sym = fun.symbol;
          val ctx1 = genLoadQualifier(fun, ctx);

          if (sym == definitions.Object_isInstanceOf) {
            ctx1.bb.emit(IS_INSTANCE(targs.head.tpe));
            generatedType = BOOL;
          } else if (sym == definitions.Object_asInstanceOf) {
            ctx1.bb.emit(CHECK_CAST(targs.head.tpe));
            generatedType = REFERENCE(targs.head.tpe.symbol);
          } else 
            abort("Unexpected type application " + fun + "[sym: " + sym + "]");
          ctx1

        case Apply(fun @ Select(Super(_, mixin), _), args) =>
          val invokeStyle = 
            if (fun.symbol.isConstructor) Static(true) else SuperCall(mixin);

          ctx.bb.emit(THIS(ctx.clazz.symbol));
          val ctx1 = genLoadArguments(args, fun.symbol.info.paramTypes, ctx);
            
          ctx1.bb.emit(CALL_METHOD(fun.symbol, invokeStyle));
          generatedType = toTypeKind(fun.symbol.info.resultType);
          ctx1
          
        case Apply(fun, args) =>
          val sym = fun.symbol;

          if (sym.isLabel) {  // jump to a label
            val label = ctx.labels.get(sym) match {
              case Some(l) => l;

              // it is a forward jump, scan for labels
              case None =>
                log("Performing scan for label because of forward jump.");
                scanForLabels(ctx.defdef, ctx);
                ctx.labels.get(sym) match {
                  case Some(l) => l;
                  case _       => abort("Unknown label target: " + sym);
                }
            }
            val ctx1 = genLoadLabelArguments(args, label, ctx);
            if (label.anchored)
              ctx1.bb.emit(JUMP(label.block));
            else
              ctx1.bb.emit(PJUMP(label));

            ctx1.bb.close;
            ctx1.newBlock;
          } else if (isPrimitive(fun.symbol)) { // primitive method call
            val Select(receiver, _) = fun;
            
            val code = scalaPrimitives.getPrimitive(fun.symbol, receiver.tpe);
            var ctx1 = ctx;

            if (scalaPrimitives.isArithmeticOp(code)) {
              ctx1 = genArithmeticOp(tree, ctx1, code);
            } else if (code == scalaPrimitives.CONCAT) {
              ctx1 = genStringConcat(tree, ctx1);
              generatedType = STRING;
            } else if (scalaPrimitives.isArrayOp(code)) {
              ctx1 = genArrayOp(tree, ctx1, code);
            } else
              abort("Primitive operation not handled yet: " + 
                    fun.symbol.fullNameString + "(" + fun.symbol.simpleName + ") "
                    + " at: " + unit.position(tree.pos));
            ctx1
          } else {  // normal method call
            var invokeStyle = 
              if (sym.isClassConstructor)
                NewInstance;
              else if (sym.isStatic)
                Static(false)
              else if (sym hasFlag Flags.PRIVATE)
                Static(true)
              else
                Dynamic;
            
            var ctx1 = if (invokeStyle.isStatic)
                         ctx;
                       else 
                         genLoadQualifier(fun, ctx);
            ctx1 = genLoadArguments(args, fun.symbol.info.paramTypes, ctx1);
            
            ctx1.bb.emit(CALL_METHOD(sym, invokeStyle));
            generatedType = toTypeKind(sym.info.resultType);
            ctx1
          }
          
        case This(qual) =>
          assert(tree.symbol == ctx.clazz.symbol, 
                 "Trying to access the this of another class: " +
                 "tree.symbol = " + tree.symbol + ", ctx.clazz.symbol = " + ctx.clazz.symbol);
          ctx.bb.emit(THIS(ctx.clazz.symbol));
          generatedType = REFERENCE(ctx.clazz.symbol);
          ctx;

        case Select(qualifier, selector) =>
          val sym = tree.symbol;
          val generatedType = toTypeKind(sym.info);

          if (sym.isStatic) {
            ctx.bb.emit(LOAD_FIELD(sym, true));
            ctx
          } else {
            val ctx1 = genLoadQualifier(tree, ctx); // !! attention
            ctx1.bb.emit(LOAD_FIELD(sym, false));
            ctx1
          }

        case Ident(name) =>
          if (tree.symbol.isModule)
            abort("Modules are not handled yet");
          else {
//            assert(ctx.method.locals contains tree.symbol, "Unkown local " + tree.symbol + "[" + name + "]");
            ctx.bb.emit(LOAD_LOCAL(tree.symbol, tree.symbol.isValueParameter ));
            generatedType = toTypeKind(tree.symbol.info);
          }
          ctx

        case Literal(value) =>
          if (value.tag != UnitTag)
            ctx.bb.emit(CONSTANT(value));
          generatedType = toTypeKind(value.tpe);
          ctx

        case Block(stats, expr) =>
          log("Entering block");
          assert(!(ctx.method eq null), "Block outside method");
          val ctx1 = genStat(stats, ctx);
          genLoad(expr, ctx1, expectedType);

        case EmptyTree => ctx;

        case Assign(_, _) => 
          generatedType = UNIT;
          genStat(tree, ctx);
                  
        case _ => abort("Unexpected tree in genLoad: " + tree);
      }

      // emit conversion
      if (!(generatedType <:< expectedType)) {
        expectedType match {
          case UNIT => resCtx.bb.emit(DROP(generatedType));
          case _ => resCtx.bb.emit(CALL_PRIMITIVE(Conversion(generatedType, expectedType)));
        }
      }
      resCtx;
    }

    /** Load the qualifier of `tree' on top of the stack. */
    private def genLoadQualifier(tree: Tree, ctx: Context): Context =
      tree match {
        case Select(qualifier, _) =>
          genLoad(qualifier, ctx, ANY_REF_CLASS); // !!
        case _ =>
          abort("Unknown qualifier " + tree);
      }

    /**
     * Generate code that loads args into label parameters.
     */
    private def genLoadLabelArguments(args: List[Tree], label: Label, ctx: Context): Context = {
      assert(args.length == label.params.length, 
             "Wrong number of arguments in call to label " + label.symbol);
      var ctx1 = ctx;
      var arg = args;
      var param = label.params;
      
      while (arg != Nil) {
        ctx1 = genLoad(arg.head, ctx1, toTypeKind(param.head.info));
        ctx1.bb.emit(STORE_LOCAL(param.head, param.head.isValueParameter));
        arg = arg.tail;
        param = param.tail;
      }
      ctx1
    }

    private def genLoadArguments(args: List[Tree], tpes: List[Type], ctx: Context): Context = {
      assert(args.length == tpes.length, "Wrong number of arguments in call " + ctx);

      var ctx1 = ctx;
      var arg = args;
      var tpe = tpes;
      while (arg != Nil) {
        ctx1 = genLoad(arg.head, ctx1, toTypeKind(tpe.head));
        arg = arg.tail;
        tpe = tpe.tail;
      }
      ctx1
    }

    /** Is the given symbol a primitive operation? */
    def isPrimitive(fun: Symbol): Boolean = {
      import scalaPrimitives._;

      if (scalaPrimitives.isPrimitive(fun)) 
        scalaPrimitives.getPrimitive(fun) match {
          case EQUALS | HASHCODE | TOSTRING | COERCE => false;
          case _ => true;
        }
      else
        false;
    }

    /** Generate string concatenation. */
    def genStringConcat(tree: Tree, ctx: Context): Context = {
      val Apply(Select(larg, _), rarg) = tree;
      var ctx1 = ctx;

      assert(rarg.length == 1, 
             "Too many parameters for string concatenation");

      val lKind = toTypeKind(larg.tpe);
      val rKind = toTypeKind(rarg.head.tpe);

      ctx1 = genLoad(larg, ctx1, lKind);
      ctx1 = genLoad(rarg.head, ctx1, rKind);
      ctx1.bb.emit(CALL_PRIMITIVE(StringConcat(lKind, rKind)));
      
      ctx1;
    }

    /** 
     * Traverse the tree and store label stubs in the contxt. This is
     * necessary to handle forward jumps, because at a label application
     * with arguments, the symbols of the corresponding LabelDef parameters
     * are not yet known.
     *
     * Since it is expensive to traverse each method twice, this method is called
     * only when forward jumps really happen, and then it re-traverses the whole
     * method, scanning for LabelDefs.
     */
    private def scanForLabels(tree: Tree, ctx: Context): Unit =
      new Traverser() {
        override def traverse(tree: Tree): Unit = tree match {

          case LabelDef(name, params, _) =>
            // TODO: check that previously entered labels survive the scan
            ctx.labels += tree.symbol -> (new Label(tree.symbol) setParams(params map (.symbol)));

          case _ => super.traverse(tree);
        } 
      } traverse(tree);

    /**
     * Generate code for conditional expressions. The two basic blocks
     * represent the continuation in case of success/failure of the
     * test.
     */
    private def genCond(tree: Tree, 
                        ctx: Context, 
                        thenCtx: Context, 
                        elseCtx: Context): Unit = {
      log("Entering genCond");

      tree match {
        case Apply(fun, args)
          if isPrimitive(fun.symbol) =>
            assert(args.length <= 1, 
                   "Too many arguments for primitive function: " + fun.symbol);

            val Select(leftArg, _) = fun;
            val kind: TypeKind = getMaxType(leftArg.tpe :: (args map (.tpe)));
            val code = scalaPrimitives.getPrimitive(fun.symbol);

            if (code == scalaPrimitives.ZNOT) {
              genCond(leftArg, ctx, elseCtx, thenCtx);
            } else if ((code == scalaPrimitives.EQ || code == scalaPrimitives.NE) &&
                       (kind.isReferenceType)) {
              genEqEqPrimitive(leftArg, args.head, ctx, thenCtx, elseCtx);
            } else if (scalaPrimitives.isComparisonOp(code)) {

              val op: TestOp = code match {
                case scalaPrimitives.LT => LT;
                case scalaPrimitives.LE => LE;
                case scalaPrimitives.GT => GT;
                case scalaPrimitives.GE => GE;
                case scalaPrimitives.ID | scalaPrimitives.EQ => EQ;
                case scalaPrimitives.NI | scalaPrimitives.NE => NE;
                
                case _ => abort("Unknown comparison primitive: " + code);
              };

              var ctx1 = genLoad(leftArg, ctx, kind);
                  ctx1 = genLoad(args.head, ctx1, kind);
              ctx1.bb.emit(CJUMP(thenCtx.bb, elseCtx.bb, op, kind));
              ctx1.bb.close;
            } else 
              code match {
                case scalaPrimitives.ZAND =>
                  val ctxInterm = ctx.newBlock;
                  genCond(leftArg, ctx, ctxInterm, elseCtx);
                  genCond(args.head, ctxInterm, thenCtx, elseCtx);

                case scalaPrimitives.ZOR =>
                  val ctxInterm = ctx.newBlock;
                  genCond(leftArg, ctx, thenCtx, ctxInterm);
                  genCond(args.head, ctxInterm, thenCtx, elseCtx);
  
                case _ =>                  
                  var ctx1 = genLoad(tree, ctx, BOOL);
                  ctx1.bb.emit(CZJUMP(thenCtx.bb, elseCtx.bb, NE, kind));
                  ctx1.bb.close;
              }
                        
        case _ => 
          var ctx1 = genLoad(tree, ctx, BOOL);
          ctx1.bb.emit(CZJUMP(thenCtx.bb, elseCtx.bb, NE, BOOL));
          ctx1.bb.close;
      }
    }

    val eqEqTemp: Name = "eqEqTemp$";
    

    /**
     * Generate the "==" code for object references. It is equivalent of
     * if (l == null) then r == null else l.equals(r);
     */
    def genEqEqPrimitive(l: Tree, r: Tree, ctx: Context, thenCtx: Context, elseCtx: Context): Unit = {
      var eqEqTempVar: Symbol = _;
      ctx.method.lookupLocal(eqEqTemp) match {
        case Some(sym) => eqEqTempVar = sym;
        case None => 
          eqEqTempVar = ctx.method.symbol.newVariable(l.pos, eqEqTemp);
          eqEqTempVar.setInfo(definitions.AnyRefClass.typeConstructor);
          ctx.method.addLocal(eqEqTempVar);
      }
      
      var ctx1 = genLoad(l, ctx, ANY_REF_CLASS);
      ctx1 = genLoad(r, ctx1, ANY_REF_CLASS);
      val tmpNullCtx = ctx1.newBlock;
      val tmpNonNullCtx = ctx1.newBlock;
      ctx1.bb.emit(STORE_LOCAL(eqEqTempVar, false));
      ctx1.bb.emit(DUP(ANY_REF_CLASS));
      ctx1.bb.emit(CZJUMP(tmpNullCtx.bb, tmpNonNullCtx.bb, EQ, ANY_REF_CLASS));
      ctx1.bb.close;

      tmpNullCtx.bb.emit(DROP(ANY_REF_CLASS)); // type of AnyRef
      tmpNullCtx.bb.emit(LOAD_LOCAL(eqEqTempVar, false));
      tmpNullCtx.bb.emit(CZJUMP(thenCtx.bb, elseCtx.bb, EQ, ANY_REF_CLASS));
      tmpNullCtx.bb.close;

      tmpNonNullCtx.bb.emit(LOAD_LOCAL(eqEqTempVar, false));
      tmpNonNullCtx.bb.emit(CALL_METHOD(definitions.Object_equals, Dynamic));
      tmpNonNullCtx.bb.emit(CZJUMP(thenCtx.bb, elseCtx.bb, NE, BOOL));
      tmpNonNullCtx.bb.close;
    }

    /**
     * Add all fields of the given class symbol to the current ICode
     * class.
     */
    private def addClassFields(ctx: Context, cls: Symbol): Unit = {
      assert(ctx.clazz.symbol eq cls, 
             "Classes are not the same: " + ctx.clazz.symbol + ", " + cls);
      
      for (val f <- cls.info.decls.elements)
        if (!f.isMethod && f.isTerm) 
          ctx.clazz.addField(new IField(f));
    }

    /**
     * Add parameters to the current ICode method. It is assumed the methods
     * have been uncurried, so the list of lists contains just one list.
     */
    private def addMethodParams(ctx: Context, vparamss: List[List[ValDef]]): Unit = 
      vparamss match {
        case Nil => ()

        case vparams :: Nil =>
          for (val p <- vparams)
            ctx.method.addParam(p.symbol);

        case _ => 
          abort("Malformed parameter list: " + vparamss);
      }


    def getMaxType(ts: List[Type]): TypeKind = {
      def maxType(a: TypeKind, b: TypeKind): TypeKind = 
        a maxType b;
      
      val kinds = ts map toTypeKind;
      kinds reduceLeft maxType;
    }
    

    /////////////////////// Context ////////////////////////////////


    /**
     * The Context class keeps information relative to the current state
     * in code generation
     */
    class Context {

      /** The current package. */
      var packg: Name = _;

      /** The current class. */
      var clazz: IClass = _;

      /** The current method. */
      var method: IMethod = _;

      /** The current basic block. */
      var bb: BasicBlock = _;

      /** Map from label symbols to label objects. */
      var labels: HashMap[Symbol, Label] = new HashMap();

      /** Current method definition. */
      var defdef: DefDef = _;

      /** current exception handlers */
      var handlers: List[ExceptionHandler] = NoHandler :: Nil;

      var handlerCount = 0;

      def this(other: Context) = {
        this();
        this.packg = other.packg;
        this.clazz = other.clazz;
        this.method = other.method;
        this.bb = other.bb;
        this.labels = other.labels;
        this.defdef = other.defdef;
        this.handlers = other.handlers;
        this.handlerCount = other.handlerCount;
      }

      def setPackage(p: Name): this.type = {
        this.packg = p;
        this
      }

      def setClass(c: IClass): this.type = {
        this.clazz = c;
        this
      }

      def setMethod(m: IMethod): this.type = {
        this.method = m;
        this
      }

      def setBasicBlock(b: BasicBlock): this.type = {
        this.bb = b;
        this
      }

      /** Prepare a new context upon entry into a method */
      def enterMethod(m: IMethod, d: DefDef): Context = {
        val ctx1 = new Context(this) setMethod(m);
        ctx1.labels = new HashMap();
        ctx1.method.code = new Code(m.symbol.simpleName.toString());
        ctx1.bb = ctx1.method.code.startBlock;
        ctx1.defdef = d;
        ctx1
      }

      /** Return a new context for a new basic block. */
      def newBlock: Context = {
        val block = method.code.newBlock;
        handlers foreach (h => h addBlock block);
        new Context(this) setBasicBlock block;
      }

      def newHandler: Context = {
        handlerCount = handlerCount + 1;
        val exh = new ExceptionHandler("" + handlerCount) setOuter handlers.head;
        handlers = exh :: handlers;
        this
      }

      def exitHandler: Context = {
        assert(handlerCount > 0,
               "Wrong nesting of exception handlers.");
        handlerCount = handlerCount - 1;
        handlers = handlers.tail;
        this
      }
    }

    /** 
     * Represent a label in the current method code. In order
     * to support forward jumps, labels can be created without
     * having a deisgnated target block. They can later be attached 
     * by calling `anchor'.
     */
    class Label(val symbol: Symbol) {
      var anchored = false;
      var block: BasicBlock = _;
      var params: List[Symbol] = _;

      private var toPatch: List[Instruction] = Nil;

      /** Fix this label to the given basic block. */
      def anchor(b: BasicBlock): Label = {
        assert(!anchored, "Cannot anchor an already anchored label!");
        anchored = true;
        this.block = b;
        this
      }

      def setParams(p: List[Symbol]): Label = {
        assert(params == null, "Cannot set label parameters twice!");
        params = p;
        this
      }

      /** Add an instruction that refers to this label. */
      def addCallingInstruction(i: Instruction) = 
        toPatch = i :: toPatch;

      /** 
       * Patch the code by replacing pseudo call instructions with
       * jumps to the given basic block.
       */
      def patch(code: Code): Unit = {
        def substMap: Map[Instruction, Instruction] = {
          val map = new HashMap[Instruction, Instruction]();

          toPatch foreach (i => map += i -> patch(i));
          map
        }

        val map = substMap;
        code traverse (.subst(map));
      }

      /** 
       * Return the patched instruction. If the given instruction
       * jumps to this label, replace it with the basic block. Otherwise,
       * return the same instruction. Conditional jumps have more than one
       * label, so they are replaced only if all labels are anchored.
       */
      def patch(instr: Instruction): Instruction = {
        assert(anchored, "Cannot patch until this label is anchored: " + this);

        instr match {
          case PJUMP(self)
          if (self == this) => JUMP(block);

          case PCJUMP(self, failure, cond, kind)
          if (self == this && failure.anchored) => 
            CJUMP(block, failure.block, cond, kind);

          case PCJUMP(success, self, cond, kind)
          if (self == this && success.anchored) => 
            CJUMP(success.block, block, cond, kind);

          case PCZJUMP(self, failure, cond, kind)
          if (self == this && failure.anchored) => 
            CZJUMP(block, failure.block, cond, kind);

          case PCZJUMP(success, self, cond, kind)
          if (self == this && success.anchored) =>
            CZJUMP(success.block, block, cond, kind);
          
          case _ => instr;
        }
      }
    }

    ///////////////// Fake instructions //////////////////////////

    /** 
     * Pseudo jump: it takes a Label instead of a basick block.
     * It is used temporarily during code generation. It is replaced
     * by a real JUMP instruction when all labels are resolved.
     */
    class PseudoJUMP(label: Label) extends Instruction {
      override def toString(): String ="PJUMP " + label.symbol.simpleName;

      override def consumed = 0;
      override def produced = 0;

      // register with the given label
      if (!label.anchored)
        label.addCallingInstruction(this);
    }

    case class PJUMP(where: Label) extends PseudoJUMP(where);

    case class PCJUMP(success: Label, failure: Label, cond: TestOp, kind: TypeKind) 
         extends PseudoJUMP(success) {

       if (!failure.anchored)
         failure.addCallingInstruction(this);
    }

    case class PCZJUMP(success: Label, failure: Label, cond: TestOp, kind: TypeKind)
         extends PseudoJUMP(success) {

       if (!failure.anchored)
         failure.addCallingInstruction(this);
    }

  }
}

