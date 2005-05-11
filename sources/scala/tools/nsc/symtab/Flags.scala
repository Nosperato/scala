/* NSC -- new scala compiler
 * Copyright 2005 LAMP/EPFL
 * @author  Martin Odersky
 */
// $Id$
package scala.tools.nsc.symtab;

object Flags {

  // modifiers
  val IMPLICIT      = 0x00000001;
  val FINAL         = 0x00000002;
  val PRIVATE       = 0x00000004;
  val PROTECTED     = 0x00000008;
  
  val SEALED        = 0x00000010; 
  val OVERRIDE      = 0x00000020;
  val CASE          = 0x00000040;
  val ABSTRACT      = 0x00000080;   // abstract class, or used in conjunction
                                    // with abstract override.
                                    // Note difference to DEFERRED!

  val DEFERRED      = 0x00000100;   // was `abstract' for members
  val METHOD        = 0x00000200;   // a def parameter
  val TRAIT         = 0x00000400;   // a trait
  val MODULE        = 0x00000800;   // symbol is module or class implementing a module

  val MUTABLE       = 0x00001000;   // symbol is a mutable variable.
  val PARAM         = 0x00002000;   // symbol is a (value or type) parameter to a method
  val PACKAGE       = 0x00004000;   // symbol is a java package
  val DEPRECATED    = 0x00008000;   // symbol is deprecated.

  val COVARIANT     = 0x00010000;   // symbol is a covariant type variable
  val CONTRAVARIANT = 0x00020000;   // symbol is a contravariant type variable
  val ABSOVERRIDE   = 0x00040000;   // combination of abstract & override
  val LOCAL         = 0x00080000;   // symbol is local to current class.
	                            // pre: PRIVATE is also set

  val JAVA          = 0x00100000;   // symbol was defined by a Java class
  val SYNTHETIC     = 0x00200000;   // symbol is compiler-generated
  val STABLE        = 0x00400000;   // functions that are assumed to be stable
				    // (typically, access methods for valdefs)

  val ACCESSED      = 0x01000000;   // symbol was accessed at least once
  val SELECTOR      = 0x02000000;   // symbol was used as selector in Select

  val CAPTURED      = 0x04000000;   // variable is accessed from nested function. Set by LambdaLift
  val ACCESSOR      = 0x08000000;   // a value or variable accessor

  val ACCESS_METHOD = 0x10000000;   // function is an access function for a method in some 
                                    // outer class; set by ExplicitOuter
  val PARAMACCESSOR = 0x20000000;   // for value definitions: is an access method for a val parameter
                                    // for parameters: is a val parameter

  val LABEL         = 0x40000000;   // symbol is a label. Set by TailCall
  val BRIDGE        = 0x80000000;   // function is a bridge method. Set by Erasure

  val INTERFACE     = 0x100000000l; // symbol is an interface
  val IS_ERROR      = 0x200000000l; // symbol is an error symbol
  val OVERLOADED    = 0x400000000l; // symbol is overloaded

  val TRANS_FLAG    = 0x800000000l; // transient flag guaranteed to be reset after each phase.
  val LIFTED        = TRANS_FLAG;   // transient flag for lambdalift
  val INCONSTRUCTOR = TRANS_FLAG;   // transient flag for analyzer

  val INITIALIZED   = 0x1000000000l; // symbol's definition is complete
  val LOCKED        = 0x2000000000l; // temporary flag to catch cyclic dependencies

  // masks
  val SourceFlags   = 0x001FFFFF;    // these modifiers can be set in source programs.
  val ExplicitFlags =                // these modifiers can be set explicitly in source programs.
    PRIVATE | PROTECTED | ABSTRACT | FINAL | SEALED | OVERRIDE | CASE | IMPLICIT | ABSOVERRIDE;
  val PrintableFlags =               // these modifiers appear in TreePrinter output.
    ExplicitFlags | LOCAL | SYNTHETIC | STABLE | ACCESSOR | 
    ACCESS_METHOD | PARAMACCESSOR | LABEL | BRIDGE;
  val GenFlags      =                // these modifiers can be in generated trees
    SourceFlags | PrintableFlags;
  val FieldFlags = MUTABLE | ACCESSED | PARAMACCESSOR;
    
  val AccessFlags   = PRIVATE | PROTECTED;
  val VARIANCES     = COVARIANT | CONTRAVARIANT;
  val ConstrFlags   = JAVA;
  val PickledFlags  = 0xFFFFFFFF & ~LOCKED & ~INITIALIZED;

  /** Module flags inherited by their module-class */
  val ModuleToClassFlags = AccessFlags | PACKAGE;

  def flags2mods(flags: long): int = flags.asInstanceOf[int] & GenFlags;

  def flagsToString(flags: long): String =
    List.range(0, 63)
      .map(i => flagToString(flags & (1L << i)))
      .filter("" !=).mkString("", " ", "");

  private def flagToString(flag: long): String = {
    if (flag == INTERFACE) "<interface>"
    else if (flag == IS_ERROR) "<is_error>"
    else if (flag == OVERLOADED) "<overloaded>"
    else if (flag == TRANS_FLAG) "<transient>"
    else if (flag == INITIALIZED) "<initialized>"
    else if (flag == LOCKED) "<locked>"
    else flag.asInstanceOf[int] match {
      case IMPLICIT      => "implicit"
      case FINAL         => "final"    
      case PRIVATE       => "private"  
      case PROTECTED     => "protected"

      case SEALED        => "sealed"   
      case OVERRIDE      => "override" 
      case CASE          => "case"     
      case ABSTRACT      => "abstract" 

      case DEFERRED      => "<deferred>"
      case METHOD        => "<method>"
      case TRAIT         => "<trait>"
      case MODULE        => "<module>"

      case MUTABLE       => "<mutable>"
      case PARAM         => "<param>"
      case PACKAGE       => "<package>"
      case DEPRECATED    => "<deprecated>"

      case COVARIANT     => "<covariant>"
      case CONTRAVARIANT => "<contravariant>"
      case ABSOVERRIDE   => "abstract override"
      case LOCAL         => "<local>"

      case JAVA          => "<java>"
      case SYNTHETIC     => "<synthetic>"
      case STABLE        => "<stable>"

      case ACCESSED      => "<accessed>"
      case SELECTOR      => "<selector>"
      case CAPTURED      => "<captured>"
      case ACCESSOR      => "<accessor>"

      case ACCESS_METHOD => "<access>"
      case PARAMACCESSOR => "<paramaccessor>"
      case LABEL         => "<label>"
      case BRIDGE        => "<bridge>"

      case _ => ""
    }
  }
}
