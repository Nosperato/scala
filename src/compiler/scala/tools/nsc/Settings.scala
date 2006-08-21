/* NSC -- new scala compiler
 * Copyright 2005-2006 LAMP/EPFL
 * @author  Martin Odersky
 */
// $Id$

package scala.tools.nsc

import java.io.File

class Settings(error: String => unit) {

  private var allsettings: List[Setting] = List()

  private def getProperty(name: String): String =
    if (System.getProperty(name) != "")
      System.getProperty(name)
    else null

  private val classpathDefault =
    if (System.getProperty("env.classpath") != null)
      alternatePath(
        getProperty("env.classpath"),
        ".")
    else getProperty("java.class.path")

  private val bootclasspathDefault = 
    alternatePath(
      concatPath(
        getProperty("sun.boot.class.path"),
        guessedScalaBootClassPath),
      "")

  private val extdirsDefault = 
    alternatePath(
      concatPath(
        getProperty("java.ext.dirs"),
        guessedScalaExtDirs),
      "")

  private def alternatePath(p1: String, p2: => String) = 
    if (p1 != null) p1 else p2

  private def concatPath(p1: String, p2: String) = 
     if (p1 != null && p2 != null) p1 + File.pathSeparator + p2
     else if (p1 != null) p1
     else p2

  private def guessedScalaBootClassPath = {
    val scalaHome = System.getProperty("scala.home")
    if (scalaHome != null) {
      val guessJar = new File(new File(new File(scalaHome), "lib"), "scala-library.jar")
      if (guessJar.exists()) guessJar.getPath()
      else {
        val guessDir = new File(new File(new File(scalaHome), "lib"), "library")
        if (guessDir.exists()) guessDir.getPath() else null
      }
    } else null
  }

  private def guessedScalaExtDirs = {
    val scalaHome = System.getProperty("scala.home")
    if (scalaHome != null) {
      val guess = new File(new File(scalaHome), "lib")
      if (guess.exists()) guess.getPath else null
    } else null
  }

  private val encodingDefault =
    new java.io.OutputStreamWriter(
      new java.io.ByteArrayOutputStream()).getEncoding

  private val windowtitleDefault = "Scala Library Documentation"
  private val documenttitleDefault = "Scala 2"

  val doc           = BooleanSetting("-doc", "Generate documentation");
  val debuginfo     = new DebugSetting("-g", "Generate debugging info", List("none", "source", "line", "vars", "notc"), "vars", "vars")
  val nowarnings    = BooleanSetting("-nowarn", "Generate no warnings")
  val noassertions  = BooleanSetting("-noassert", "Generate no assertions and assumptions")
  val verbose       = BooleanSetting("-verbose", "Output messages about what the compiler is doing")
  val classpath     = StringSetting ("-classpath", "path", "Specify where to find user class files", classpathDefault)
      classpath.abbreviation = "-cp"
  val sourcepath    = StringSetting ("-sourcepath", "path", "Specify where to find input source files", "")
  val bootclasspath = StringSetting ("-bootclasspath", "path", "Override location of bootstrap class files", bootclasspathDefault)
  val extdirs       = StringSetting ("-extdirs", "dirs", "Override location of installed extensions", extdirsDefault)
  val outdir        = StringSetting ("-d", "directory", "Specify where to place generated class files", ".")
  val encoding      = StringSetting ("-encoding", "encoding", "Specify character encoding used by source files", encodingDefault)
  val windowtitle   = StringSetting ("-windowtitle", "windowtitle", "Specify window title of generated HTML documentation", windowtitleDefault)
  val documenttitle = StringSetting ("-documenttitle", "documenttitle", "Specify document title of generated HTML documentation", documenttitleDefault)
  val target        = ChoiceSetting ("-target", "Specify which backend to use",  List("jvm-1.5", "jvm-1.4", "msil", "cldc"), "jvm-1.4")
  val migrate       = BooleanSetting("-migrate", "Assist in migrating from Scala version 1.0")
  val debug         = BooleanSetting("-debug", "Output debugging messages")
  val statistics    = BooleanSetting("-statistics", "Print compiler statistics")
  val explaintypes  = BooleanSetting("-explaintypes", "Explain type errors in more detail")
  val resident      = BooleanSetting("-resident", "Compiler stays resident, files to compile are read from standard input")
  val uniqid        = BooleanSetting("-uniqid", "Print identifiers with unique names (debugging option)")
  val printtypes    = BooleanSetting("-printtypes", "Print tree types (debugging option)")
  val prompt        = BooleanSetting("-prompt", "Display a prompt after each error (debugging option)")
  val noimports     = BooleanSetting("-noimports", "Compile without any implicit imports")
  val nopredefs     = BooleanSetting("-nopredefs", "Compile without any implicit predefined values")
  val skip          = PhasesSetting ("-skip", "Skip")
  val check         = PhasesSetting ("-check", "Check the tree at start of")
  val print         = PhasesSetting ("-print", "Print out program after")
  val printer       = ChoiceSetting ("-printer", "Printer to use", List("text", "html"), "text")
  val printfile     = StringSetting ("-printfile", "file", "Specify file in which to print trees", "-")
  val graph         = PhasesSetting ("-graph", "Graph the program after")
  val browse        = PhasesSetting ("-browse", "Browse the abstract syntax tree after")
  val stop          = PhasesSetting ("-stop", "Stop after phase")
  val log           = PhasesSetting ("-log", "Log operations in")
  val version       = BooleanSetting("-version", "Print product version and exit")
  val help          = BooleanSetting("-help", "Print a synopsis of standard options")
//  val showPhases    = BooleanSetting("-showphases", "Print a synopsis of compiler phases")

  val inline        = BooleanSetting("-Xinline", "Perform inlining when possible")
  val Xcloselim     = BooleanSetting("-Xcloselim", "Perform closure elimination")
  val Xdce          = BooleanSetting("-Xdce", "Perform dead code elimination")
  val Xshowcls      = StringSetting ("-Xshowcls", "class", "Show class info", "")
  val Xshowobj      = StringSetting ("-Xshowobj", "object", "Show object info", "")
  val Xlinearizer   = ChoiceSetting ("-Xlinearizer", "Linearizer to use", List("normal", "dfs", "rpo", "dump"), "rpo")
  val Xgenerics     = BooleanSetting("-Xgenerics", "Use generic Java types");
  val Xprintpos     = BooleanSetting("-Xprintpos", "Print tree positions (as offsets)");
  val Xscript       = BooleanSetting("-Xscript", "compile script file");

  /** A list of all settings */
  def allSettings: List[Setting] = allsettings.reverse

  /** Disable a setting */
  def disable(s: Setting) = {
    allsettings = allsettings filter (s !=)
  }

  /** A base class for settings of all types.
   *  Subclasses each define a `value' field of the appropriate type.
   */
  abstract class Setting(val name: String, descr: String) {

    /** If first arg defines this setting, consume it as well as all following
     *  args needed to define the setting. If this can be done without
     *  error, set value field and return suffix of args else
     *  issue error message and return empty.
     *  If first arg does not define this setting return args unchanged.
     */
    def tryToSet(args: List[String]): List[String]

    /** The syntax defining this setting in a help string */
    def helpSyntax: String = name

    /** A description of the purpose of this setting in a help string */
    def helpDescription = descr

    /** Return a list of strings that can be used to recreate
      * the receiver's current setting.
      */
    def unparse: List[String]
                      
    // initialization
    allsettings = this :: allsettings
  }

  /** A setting represented by a boolean flag (false, unless set) */
  case class BooleanSetting(nme: String, descr: String)
  extends Setting(nme, descr) {
    var value: boolean = false

    def tryToSet(args: List[String]): List[String] = args match {
      case n :: rest if (n == name) => value = true; rest
      case _ => args
    }
    
    def unparse: List[String] =
      if(value)
        List(name)
      else
        Nil
  }

  /** A setting represented by a string, (`default' unless set) */
  case class StringSetting(nme: String, arg: String, descr: String, default: String)
  extends Setting(nme, descr) {
    var abbreviation: String = null
    
    var value: String = default

    def tryToSet(args: List[String]): List[String] = args match {
      case n :: rest if (name == n || abbreviation == n) =>
        if (rest.isEmpty) {
          error("missing argument")
          List()
        } else {
          value = rest.head
          rest.tail
        }
      case _ => args
    }

    override def helpSyntax = name + " <" + arg + ">"
      
    def unparse: List[String] =
      if(value == default)
        Nil
      else 
        List(name, value)
  }

  /** A setting represented by a string in a given set of `choices',
   *  (`default' unless set)
   */
  case class ChoiceSetting(nme: String, descr: String, choices: List[String], default: String)
  extends Setting(nme, descr + choices.mkString(" (", ",", ")")) {
    protected var v: String = default;

    def value: String = this.v;
    def value_=(s: String): Unit = this.v = s;
    
    protected def argument: String = name.substring(1)

    def tryToSet(args: List[String]): List[String] = args match {
      case n :: rest if (n startsWith (name + ":")) =>
        val choice = n.substring(name.length() + 1)
        if (!(choices contains choice)) {
          error(
            if (choice == "") "missing " + argument
            else "unknown " + argument + " '" + choice + "'")
          args
        } else {
          value = choice
          rest
        }
      case _ => args
    }

    override def helpSyntax = name + ":<" + argument + ">"
      
    def unparse: List[String] =
      if(value == default)
        Nil
      else
        List(name + ":" + value)
  }
  
  /** Same as ChoiceSetting but have a 'level' int which tells the index of the selected
   * choice. The 'defaultEmpty' is used when this setting is used without specifying any of
   * the available choices.
   */
  class DebugSetting(nme: String, descr: String, choices: List[String], default: String, defaultEmpty: String)
  	extends ChoiceSetting(nme, descr, choices, default) {
    
    def indexOf[a](xs: List[a], e: a): Option[Int] = xs match {
      case y :: ys => if (e == y) Some(0) else indexOf(ys, e) match {
          case Some(idx) => Some(1 + idx)
          case None => None
        }
      case _ => None;
    }
    var level: Int = indexOf(choices, default).get;
  
    override def value_=(choice: String): Unit = {
      this.v     = choice;
      this.level = indexOf(choices, choice).get;
    }
    
    override def tryToSet(args: List[String]): List[String] = args match {
      case n :: rest if (n startsWith (name + ":")) =>
        val choice = n.substring(name.length() + 1)
        if (!(choices contains choice)) {
          error(
              if (choice == "") "missing " + argument
              else "unknown " + argument + " '" + choice + "'")
          args
        } else {
          value = choice
          rest
        }

      case n :: rest if (n startsWith name) =>
        value = defaultEmpty;
        rest
      	
      case _ => args
    }
  }
  
  /** A setting represented by a list of strings which should be prefixes of
   *  phase names. This is not checked here, however.
   *  (the empty list, unless set)
   */
  case class PhasesSetting(nme: String, descr: String)
  extends Setting(nme, descr + " <phase>") { // (see -showphases)") {
    var value: List[String] = List()

    def tryToSet(args: List[String]): List[String] = args match {
      case n :: rest if (n startsWith (name + ":")) =>
        val phase = n.substring(name.length() + 1)
        if (phase == "") {
          error("missing phase")
          List()
        } else {
          value = value ::: List(phase)
          rest
        }
      case _ => args
    }

    override def helpSyntax = name + ":<phase>"

    def contains(phasename: String): boolean =
      value exists (str => phasename startsWith str)
    
    def unparse: List[String] = 
      (value.foldLeft[List[String]]
          (Nil)
          ((args, phase) =>
            List(name + ":" + phase) ::: args))
  }
}
