/* NSC -- new scala compiler
 * Copyright 2005 LAMP/EPFL
 * @author  Martin Odersky
 */
// $Id$
package scala.tools.nsc;

import scala.tools.util.Reporter;

/** A class representing command line info for scalac */
class CompilerCommand(arguments: List[String], error: String => unit, interactive: boolean) {
  private var fs: List[String] = List();

  /** All files to compile */
  def files: List[String] = fs.reverse;

  /** The applicable settings */
  val settings: Settings = new Settings(error);

  /** A message explaining usage and options */
  def usageMsg: String = {
    val helpSyntaxColumnWidth: int = 
      Iterable.max(settings.allSettings map (. helpSyntax.length()));
    def format(s: String): String = {
      val buf = new StringBuffer();
      buf.append(s);
      var i = s.length();
      while (i < helpSyntaxColumnWidth) { buf.append(' '); i = i + 1 }
      buf.toString()
    }
    settings.allSettings 
      .map(setting => 
	format(setting.helpSyntax) + "  " + setting.helpDescription)
      .mkString(
	"Usage: scalac <options | source files>\n" + 
	"where possible options include: \n  ",
	"\n  ",
	"\n");
  }

  // initialization
  var args = arguments;
  var ok = true;
  while (!args.isEmpty && ok) {
    val args0 = args;
    if (args.head.startsWith("-")) {
      if (interactive) {
	error("no options can be given in interactive mode");
	ok = false
      } else {
	for (val setting <- settings.allSettings)
          args = setting.tryToSet(args);
	if (args eq args0) {
	  error("unknown option: '" + args.head + "'");
	  ok = false
	}
      }
    } else if (args.head.endsWith(".scala")) {
      fs = args.head :: fs;
      args = args.tail
    } else {
      error("don't know what to do with " + args.head);
      ok = false
    }
  }
}
