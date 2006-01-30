/* NSC -- new scala compiler
 * Copyright 2005 LAMP/EPFL
 * @author  Martin Odersky
 */
// $Id$
package scala.tools.nsc.util;

object NameTransformer {
  private val nops = 128;
  private val ncodes = 26 * 26;

  private class OpCodes(val op: char, val code: String, val next: OpCodes);

  private val op2code = new Array[String](nops); 
  private val code2op = new Array[OpCodes](ncodes);

  private def enterOp(op: char, code: String) = {
    op2code(op) = code;
    val c = (code.charAt(1) - 'a') * 26 + code.charAt(2) - 'a';
    code2op(c) = new OpCodes(op, code, code2op(c))
  }

  enterOp('~', "$tilde");
  enterOp('=', "$eq");
  enterOp('<', "$less");
  enterOp('>', "$greater");
  enterOp('!', "$bang");
  enterOp('#', "$hash");
  enterOp('%', "$percent");
  enterOp('^', "$up");
  enterOp('&', "$amp");
  enterOp('|', "$bar");
  enterOp('*', "$times");
  enterOp('/', "$div");
  enterOp('+', "$plus");
  enterOp('-', "$minus");
  enterOp(':', "$colon");
  enterOp('\\', "$bslash");
  enterOp('?', "$qmark");

  /** Replace operator symbols by corresponding "$op_name" */
  def encode(name: String): String = {
    var buf: StringBuffer = null;
    val len = name.length();
    var i = 0;
    while (i < len) {
      val c = name charAt i;
      if (c < nops && op2code(c) != null) {
        if (buf == null) {
          buf = new StringBuffer();
          buf.append(name.substring(0, i));
        }
        buf.append(op2code(c));
      } else if (buf != null) {
        buf.append(c)
      }
      i = i + 1
    }
    if (buf == null) name else buf.toString()
  }

  /** Replace $op_name by corresponding operator symbol */
  def decode(name: String): String = {
    //System.out.println("decode: " + name);//DEBUG
    var buf: StringBuffer = null;
    val len = name.length();
    var i = 0;
    while (i < len) {
      var ops: OpCodes = null;
      val c = name charAt i;
      if (c == '$' && i + 2 < len) {
	val ch1 = name.charAt(i+1);
	if ('a' <= ch1 && ch1 <= 'z') {
	  val ch2 = name.charAt(i+2);
	  if ('a' <= ch2 && ch2 <= 'z') {
	    ops = code2op((ch1 - 'a') * 26 + ch2 - 'a');
	    while (ops != null && !name.startsWith(ops.code, i)) ops = ops.next;
	    if (ops != null) {
              if (buf == null) {
		buf = new StringBuffer();
		buf.append(name.substring(0, i));
              }
              buf.append(ops.op);
	      i = i + ops.code.length()
	    }
	  }
	}
      }
      if (ops == null) {
	if (buf != null) buf.append(c);
	i = i + 1	
      }
    }
    //System.out.println("= " + (if (buf == null) name else buf.toString()));//DEBUG
    if (buf == null) name else buf.toString()    
  }
}
