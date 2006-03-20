/*    __  ______________                                                      *\
**   /  |/ / ____/ ____/                                                      **
**  / | | /___  / /___                                                        **
** /_/|__/_____/_____/ Copyright 2005-2006 LAMP/EPFL                          **
**
** $Id$
\*                                                                            */

package scala.tools.ant {
  
  import scala.collection.immutable.{Map, ListMap}
  
  import java.io.{File, InputStream, FileWriter}
  import java.net.{URL, URLClassLoader}
  import java.util.{ArrayList, Vector}
  
  import org.apache.tools.ant.{AntClassLoader, BuildException,
                               DirectoryScanner, Project}
  import org.apache.tools.ant.taskdefs.MatchingTask
  import org.apache.tools.ant.types.Path
  import org.apache.tools.ant.util.{FileUtils, GlobPatternMapper,
                                    SourceFileScanner}
  import org.apache.tools.ant.types.{EnumeratedAttribute, Reference}
    
  /** An Ant task that generates a SH or BAT script to execute a Scala program.
    * This task can take the following parameters as attributes:<ul>
    *  <li>file (mandatory),</li>
    *  <li>name,</li>
    *  <li>class (mandatory),</li>
    *  <li>platforms,</li>
    *  <li>version,</li>
    *  <li>copyright,</li>
    *  <li>classpath,</li>
    *  <li>properties,</li>
    *  <li>javaflags,</li>
    *  <li>toolflags,</li>
    *  <li>genericfile.</li></ul>
    * 
    * @author Gilles Dubochet */
  class ScalaTool extends MatchingTask {
      
    /** The unique Ant file utilities instance to use in this task. */
    private val fileUtils = FileUtils.newFileUtils()
    
/******************************************************************************\
**                             Ant user-properties                            **
\******************************************************************************/
    
    abstract class PermissibleValue {
      val values: List[String]
      def isPermissible(value: String): Boolean =
        (value == "") || values.exists(.startsWith(value))
    }

    /** Defines valid values for the platforms property. */
    object Platforms extends PermissibleValue {
      val values = List("unix", "windows")
    }
    
    /** The path to the exec script file. ".bat" will be appended for the
      * Windows BAT file, if generated. */
    private var file: Option[File] = None
    /** The main class to run. If this is not set, a generic script will be generated */
    private var mainClass: Option[String] = None
    /** The name of this tool. Can only be set when a main class is defined,
      * default this is equal to the file name. */
    private var name: Option[String] = None
    /** Supported platforms for the script. Either "unix" or "windows". Defaults
      * to both. */
    private var platforms: List[String] = Nil
    /** The optional version number. If set, when "-version" is passed to the
      * script, this value will be printed.  */
    private var version: String = ""
    /** The optional copyright notice, that will be printed in the script. */
    private var copyright: String = "This file is copyrighted by its owner"
    /** An (optional) path to all JARs that this script depend on. Paths must be
      * relative to the scala home directory. If not set, all JAR archives in
      * "lib/" are automatically added. */
    private var extclasspath: List[String] = Nil
    /** Comma-separated Java system properties to pass to the JRE. Properties
      * are formated as name=value. Properties scala.home, scala.class.path,
      * scala.boot.class.path and scala.ext.class.path are always set;
      * scala.tool.name and scala.tool.version are set when this script is
      * non-generic. */
    private var properties: List[Pair[String,String]] = Nil
    /** Additional flags passed to the JRE ("java [javaFlags] class"). */
    private var javaFlags: String = ""
    /** Additional flags passed to the tool ("java class [toolFlags]"). Can only
      * be set when a main class is defined */
    private var toolFlags: String = ""
    
/******************************************************************************\
**                             Properties setters                             **
\******************************************************************************/
    
    /** Sets the file attribute. Used by Ant.
      * @param input The value of <code>file</code>. */
    def setFile(input: File) =
      file = Some(input)
      
    /** Sets the file attribute. Used by Ant.
      * @param input The value of <code>file</code>. */
    def setName(input: String) =
      name = Some(input)
      
    /** Sets the main class attribute. Used by Ant.
      * @param input The value of <code>mainClass</code>. */
    def setClass(input: String) =
      mainClass = Some(input)
    
    /** Sets the platforms attribute. Used by Ant.
      * @param input The value for <code>platforms</code>. */
    def setPlatforms(input: String) = {
      platforms = List.fromArray(input.split(",")).flatMap(s: String => {
        val st = s.trim()
        if (Platforms.isPermissible(st))
          (if (input != "") List(st) else Nil)
        else {
          error("Platform " + st + " does not exist.")
          Nil
        }
      })
    }
    
    /** Sets the version attribute. Used by Ant.
      * @param input The value of <code>version</code>. */
    def setVersion(input: String) =
      version = input
    
    /** Sets the copyright attribute. Used by Ant.
      * @param input The value of <code>copyright</code>. */
    def setCopyright(input: String) =
      copyright = input
    
    /** Sets the extension classpath attribute. Used by Ant.
      * @param input The value of <code>classpath</code>. */
    def setExtclasspath(input: String) =
      extclasspath = extclasspath ::: List.fromArray(input.split(":"))
    
    /** Sets the properties attribute. Used by Ant.
      * @param input The value for <code>properties</code>. */
    def setProperties(input: String) = {
      properties = List.fromArray(input.split(",")).flatMap(s: String => {
        val st = s.trim(); val stArray = st.split("=", 2)
        if (stArray.length == 2) {
          if (input != "") List(Pair(stArray(0), stArray(1))) else Nil
        } else error("Property " + st + " does not conform to specification.")
      })
    }
    
    /** Sets the version attribute. Used by Ant.
      * @param input The value of <code>version</code>. */
    def setJavaflags(input: String) =
      javaFlags = input
    
    /** Sets the version attribute. Used by Ant.
      * @param input The value of <code>version</code>. */
    def setToolflags(input: String) =
      toolFlags = input

/******************************************************************************\
**                             Properties getters                             **
\******************************************************************************/
    
    /** Gets the value of the file attribute in a Scala-friendly form. 
      * @returns The file as a file. */
    private def getFile: File =
      if (file.isEmpty) error("Member 'file' is empty.")
      else getProject().resolveFile(file.get.toString())

    /** Gets the value of the classpath attribute in a Scala-friendly form.
      * @returns The class path as a list of files. */
    private def getUnixExtClasspath: String =
      extclasspath.mkString("", ":", "")
    
    /** Gets the value of the classpath attribute in a Scala-friendly form.
      * @returns The class path as a list of files. */
    private def getWinExtClasspath: String =
      extclasspath.map(.replace('/', '\\')).
                mkString("", ";", "")
      
    /** Gets the value of the classpath attribute in a Scala-friendly form.
      * @returns The class path as a list of files. */
    private def getProperties: String =
      properties.map({
        case Pair(name,value) => "-D" + name + "=\"" + value + "\""
      }).mkString("", " ", "")
    
/******************************************************************************\
**                       Compilation and support methods                      **
\******************************************************************************/
    
    /** Generates a build error. Error location will be the current task in the  
      * ant file.
      * @param message A message describing the error.
      * @throws BuildException A build error exception thrown in every case. */
    private def error(message: String): Nothing =
      throw new BuildException(message, getLocation())
    
    private def readResource(resource: String,
                             tokens: Map[String, String]
    ): String = {
      val chars = new Iterator[Char] {
        private val stream =
          this.getClass().getClassLoader().getResourceAsStream(resource)
        private def readStream(): Char = stream.read().asInstanceOf[Char]
        private var buf: Char = readStream()
        def hasNext: Boolean = (buf != (-1.).asInstanceOf[Char])
        def next: Char = {
          val bufbuf = buf
          buf = readStream()
          bufbuf
        }
      }
      val builder = new StringBuffer()
      while (chars.hasNext) {
        val char = chars.next
        if (char == '@') {
          var char = chars.next
          val token = new StringBuffer()
          while (chars.hasNext && char != '@') {
            token.append(char)
            char = chars.next
          }
          if (tokens.contains(token.toString()))
            builder.append(tokens(token.toString()))
          else if (token.toString() == "")
            builder.append('@')
          else
            builder.append("@" + token.toString() + "@")
        } else builder.append(char)
      }
      builder.toString()
    }
    
    private def writeFile(file: File, content: String) =
      if (file.exists() && !file.canWrite())
        error("File " + file + " is not writable")
      else {
        val writer = new FileWriter(file, false)
        writer.write(content)
        writer.close()
      }
    
    private def expandUnixVar(vars: Map[String,String]): Map[String,String] =
      vars.map((key:String,vari:String) => vari.
        replaceAll("#([^#]*)#", "\\$$1")
      )
    
    private def expandWinVar(vars: Map[String,String]): Map[String,String] =
      vars.map((key:String,vari:String) => vari.
        replaceAll("#([^#]*)#", "%_$1%")
      )
    
    private def pipeTemplate(template: String, patches: Map[String,String]) = {
      val resourceRoot = "scala/tools/ant/templates/"
      if (platforms.contains("unix")) {
        val unixPatches = expandUnixVar(patches.
          update("extclasspath", getUnixExtClasspath))
        val unixTemplateResource = resourceRoot + template + "-unix.tmpl"
        val unixTemplate = readResource(unixTemplateResource, unixPatches)
        writeFile(getFile, unixTemplate)
      }
      if (platforms.contains("windows")) {
        val winPatches = expandWinVar(patches.
          update("extclasspath", getWinExtClasspath))
        val winTemplateResource = resourceRoot + template + "-windows.tmpl"
        val winTemplate = readResource(winTemplateResource, winPatches)
        writeFile(new File(getFile.getAbsolutePath() + ".bat"), winTemplate)
      }
    }
      

/******************************************************************************\
**                           The big execute method                           **
\******************************************************************************/

    /** Performs the compilation. */
    override def execute() = {
      // Tests if all mandatory attributes are set and valid.
      if (file.isEmpty) error("Attribute 'file' is not set.")
      if (platforms.isEmpty) platforms = Platforms.values
      if (mainClass.isEmpty) {
        if (toolFlags != "")
          error("Attribute 'toolflags' cannot be set in a generic file.")
        if (!name.isEmpty)
          error("Attribute 'name' cannot be set in a generic file.")
        val patches = ListMap.Empty.
          update("version", version).
          update("copyright", copyright).
          update("properties", getProperties).
          update("javaflags", javaFlags)
        pipeTemplate("generic", patches)
      } else {
        val patches = ListMap.Empty.
          update("name", name.get).
          update("class", mainClass.get).
          update("version", version).
          update("copyright", copyright).
          update("properties", getProperties).
          update("javaflags", javaFlags).
          update("toolflags", toolFlags)
        if (name.isEmpty) name = Some(file.get.getName())
        pipeTemplate("tool", patches)
      }
    }
    
  }
  
}
