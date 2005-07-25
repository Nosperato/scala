/* NSC -- new scala compiler
 * Copyright 2005 LAMP/EPFL
 * @author  Martin Odersky
 */

// $Id$

package scala.tools.nsc.backend.icode;

import scala.tools.nsc.ast._;
import scala.collection.mutable.Map;

trait BasicBlocks: Global {
  import opcodes._;

  /** This class represents a basic block. Each
   *  basic block contains a list of instructions that are
   *  either executed all, or none. No jumps
   *  to/from the "middle" of the basic block are allowed.
   */
  class BasicBlock (theLabel: int) {

    /** The type stack at the begining of the block */
    var initialStack : TypeStack = null;

    /** The label of the block */
    val label = theLabel;

    /** The substitute variables of the block 
     *  in the case of a recursive method */
    var substituteVars : List[Symbol] = null;

    /** The stack at the end of the block */
    var endStack : TypeStack = null;

    
    /** ICode instructions */
    private var instructionList: List[Instruction] = Nil;
    
    private var lastInstruction: Instruction = null;

    private var closed: boolean = false;

    private var instrs: Array[Instruction] = _;

    // public:

    /** Compute an hashCode for the block */
    override def hashCode() = label;

    /** Apply a function to all the instructions of the block*/
    def traverse(f: Instruction => unit) = 
      instructionList.reverse.foreach(f);

    /** The number of instructions in this basic block so far. */
    def size: Int = instructionList.length;

    /** Initialize the stack of the block, must be done before evaluation 
     *  the type stack  */
    def initStack(stack : TypeStack) = {
      if (initialStack == null) {
        initialStack = stack;
        endStack = null;
      }
    }
    
    ///////////////////// Substitutions ///////////////////////

    /** 
     * Replace the instruction at the given position. Used by labels when
     * they are anchored.
     */
    def replaceInstruction(pos: Int, instr: Instruction): Boolean = {
      assert(closed, "Instructions can be replaced only after the basic block is closed");

      instrs(pos) = instr;
      true
    }

    /** 
     * Replace the given instruction with the new one. 
     * Returns `true' if it actually changed something. 
     */
    def replaceInstruction(oldInstr: Instruction, newInstr: Instruction): Boolean = {
      assert(closed, "Instructions can be replaced only after the basic block is closed");
      
      var i = 0;
      var changed = false;
      while (i < instrs.length) {
        if (instrs(i) == oldInstr) {
          instrs(i) = newInstr;
          changed = true;
        }
        i = i + 1;
      }
      changed
    }
    
    /** Replace all instructions found in the map. */
    def subst(map: Map[Instruction, Instruction]) = {
      var i = 0;
      while (i < instrs.length) {
        map get instrs(i) match {
          case Some(instr) => replaceInstruction(i, instr);
          case None => ();
        }
        i = i + 1;
      }   
    }

    /** Add a new instruction at the end of the block */
    def emit(instr: Instruction) = {
      assert (!closed, "BasicBlock closed.");
      instructionList = instr :: instructionList;
      lastInstruction = instr;
    }

    /** Compute the type stack of the block */
    def typeBlock = {
      assert(initialStack != null, "Stack not initialized");
      endStack = initialStack;
      traverse((ic : Instruction) => endStack = endStack.eval(ic));
    } 
    
    /** Close the block */
    def close = {
      closed = true;
      instrs = toInstructionArray(instructionList);
    }

    /** Convert the list to an array */
    def toInstructionArray(l: List[Instruction]): Array[Instruction] = {
      var array = new Array[Instruction](l.length);
      var i: Int = 0;

      l foreach (x => { array(i) = x; i = i + 1 });
      array
    }
        
    def isClosed = closed;

    def getLastInstruction = lastInstruction;

    def successors : List[BasicBlock] = // here order will count 
      lastInstruction match {
        case JUMP (where) => List(where);
        case CJUMP(success, failure, _) => failure::success::Nil;
        case CZJUMP(success, failure, _) => failure::success::Nil;
        case SWITCH(_,labels) => labels;
        case RETURN() => Nil;
        case _ =>
	  abort("The last instruction is not a control flow instruction");
      }
    
    // Instead of it, rather use a printer
    def print() : unit = print(System.out);
    
    def print(out: java.io.PrintStream) : unit = {
      out.println("block #"+label+" :");
      instructionList.reverse.foreach(
        (i: Instruction) => out.println("  "+i));
      out.print("Successors: ");
      successors.foreach((x: BasicBlock) => out.print(" "+x.label.toString()));
      out.println();
    }

  }

}
