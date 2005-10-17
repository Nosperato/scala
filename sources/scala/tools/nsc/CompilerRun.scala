/* NSC -- new scala compiler
 * Copyright 2005 LAMP/EPFL
 * @author  Martin Odersky
 */
// $Id$
package scala.tools.nsc;

class CompilerRun { 
  def firstPhase: Phase = NoPhase;
  def terminalPhase: Phase = NoPhase;
  def namerPhase: Phase = NoPhase;
  def typerPhase: Phase = NoPhase;
  def refchecksPhase: Phase = NoPhase;
  def erasurePhase: Phase = NoPhase;
  def flattenPhase: Phase = NoPhase;
  def mixinPhase: Phase = NoPhase;
  def phaseNamed(name: String): Phase = NoPhase;
}

