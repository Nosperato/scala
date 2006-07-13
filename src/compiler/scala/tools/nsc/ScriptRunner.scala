/* NSC -- new Scala compiler
 * Copyright 2005-2006 LAMP/EPFL
 * @author  Martin Odersky
 */
// $Id$

package scala.tools.nsc

import java.io._
import java.util.jar._
import java.lang.reflect.InvocationTargetException
import scala.tools.nsc.util._
import scala.tools.nsc.io._
import scala.tools.nsc.reporters.ConsoleReporter

/** An object that runs Scala code in script files.
 *
 *  For example, here is a complete Scala script on Unix:
 *  
 *    #!/bin/sh
 *    exec scala "$0" "$@"
 *    !#
 *    Console.println("Hello, world!")
 *    argv.toList foreach Console.println
 *
 * And here is a batch file example on Windows XP:
 *
 *    ::#!
 *    @echo off
 *    call scala %0 %*
 *    goto :eof
 *    ::!#
 *    Console.println("Hello, world!")
 *    argv.toList foreach Console.println
 *
 * TODO: It would be better if error output went to stderr instead
 * of stdout....
 */
object ScriptRunner {
  /** Choose a jar filename to hold the compiled version
    * of a script
    */
  private def jarFileFor(scriptFile: String): File = {
    val filename =
      if(scriptFile.matches(".*\\.[^.\\\\/]*"))
        scriptFile.replaceFirst("\\.[^.\\\\/]*$", ".jar")
      else
        scriptFile + ".jar"

    new File(filename)
  }
  
  /** Try to create a jar out of all the contents
    * of a directory.
    */
  private def tryMakeJar(jarFile: File, sourcePath: File) = {
    try {
      val jarFileStream = new FileOutputStream(jarFile)
      val jar = new JarOutputStream(jarFileStream)
      val buf = new Array[byte](10240)
    
      def addFromDir(dir: File, prefix: String): Unit = {
        for(val entry <- dir.listFiles) {
          if(entry.isFile) {
            jar.putNextEntry(new JarEntry(prefix + entry.getName))
            
            val input = new FileInputStream(entry)
            var n = input.read(buf, 0, buf.length)
            while (n >= 0) {
              jar.write (buf, 0, n)
              n = input.read(buf, 0, buf.length)
            }
            jar.closeEntry
            input.close
          } else {
            addFromDir(entry, prefix + entry.getName + "/")
          }
        }
      }

      addFromDir(sourcePath, "")
      jar.close
    } catch {
      case _:Error => jarFile.delete // XXX what errors to catch?
    }
  }
  
  /** Read the entire contents of a file as a String. */
  private def contentsOfFile(filename: String): String = {
    val strbuf = new StringBuffer
    val reader = new FileReader(filename)
    val cbuf = new Array[Char](1024)
    while(true) {
      val n = reader.read(cbuf)
      if(n <= 0)
        return strbuf.toString
      strbuf.append(cbuf, 0, n)
    }
    throw new Error("impossible")
  }
  
  /** Find the length of the header in the specified file, if
    * there is one.  The header part starts with "#!" or "::#!"
    * and ends with a line that begins with "!#" or "::!#".
    */
  private def headerLength(filename: String): Int = {
    import java.util.regex._
    
    val fileContents = contentsOfFile(filename)

    if(!(fileContents.startsWith("#!") || fileContents.startsWith("::#!")))
      return 0
      
    val matcher = 
      (Pattern.compile("^(::)?!#.*(\\r|\\n|\\r\\n)", Pattern.MULTILINE)
              .matcher(fileContents))
    if(! matcher.find)
      throw new Error("script file does not close its header with !# or ::!#")

    return matcher.end
  }

  /** Wrap a script file into a runnable object named
    * scala.scripting.Main .
    */
  def wrappedScript(filename: String): SourceFile = {
    val preamble =
      new SourceFile("<script preamble>",
          ("package scala.scripting\n" +
          "object Main {\n" +
          "  def main(argv: Array[String]): Unit = {\n" +
          "  val args = argv;\n").toCharArray)
    
    val middle =
      new SourceFileFragment(
          new SourceFile(new PlainFile(new File(filename))),
          headerLength(filename),
          new File(filename).length.asInstanceOf[Int])
    
    val end = new SourceFile("<script trailer>", "\n} }\n".toCharArray)

    new CompoundSourceFile(preamble, middle, end)
  }

  /** Compile a script using the fsc compilation deamon */
  private def compileWithDaemon
    (settings: GenericRunnerSettings, scriptFile: String)
    :Boolean =
  {
    val compSettingNames =
      (new Settings(error)).allSettings.map(.name)
      
    val compSettings =
      settings.allSettings.filter(stg => 
        compSettingNames.contains(stg.name))
          
    val coreCompArgs = 
      compSettings.foldLeft[List[String]](Nil)((args, stg) =>
        stg.unparse ::: args)
      
    val compArgs = coreCompArgs ::: List("-Xscript", scriptFile)
    
    val socket = CompileSocket.getOrCreateSocket("")
    val out = new PrintWriter(socket.getOutputStream(), true)
    val in = new BufferedReader(new InputStreamReader(socket.getInputStream()))

    out.println(CompileSocket.getPassword(socket.getPort))
    out.println(compArgs.mkString("", "\0", ""))

    var compok = true
    
    var fromServer = in.readLine()
    while (fromServer != null) {
      System.out.println(fromServer)
      if(fromServer.matches(".*errors? found.*"))
        compok = false
        
      fromServer = in.readLine()
    }
    in.close()
    out.close()
    socket.close()

    compok
  }
  
  
  /** Compile a script and then run the specified closure with
    * a classpath for the compiled script.
    */
  private def withCompiledScript
        (settings: GenericRunnerSettings, scriptFile: String)
        (handler: String=>Unit)
        :Unit =
  {
    import Interpreter.deleteRecursively
    
    /** Compiles the script file, and returns two things:
      * the directory with the compiled class files,
      * and a flag for whether the compilation succeeded.
      */
    def compile: Pair[File, Boolean] = {
      val compiledPath = File.createTempFile("scalascript", "")
      compiledPath.delete  // the file is created as a file; make it a directory
      compiledPath.mkdirs
      
      settings.outdir.value = compiledPath.getPath

      if(settings.nocompdaemon.value) {
        val reporter = new ConsoleReporter
        val compiler = new Global(settings, reporter)
        val cr = new compiler.Run
	cr.compileSources(List(wrappedScript(scriptFile)))
        Pair(compiledPath, reporter.errors == 0)
      } else {
        val compok = compileWithDaemon(settings, scriptFile)
        Pair(compiledPath, compok)
      }
    }

      
    if(settings.savecompiled.value) {
      val jarFile = jarFileFor(scriptFile)

      def jarOK = (jarFile.canRead && 
        (jarFile.lastModified > new File(scriptFile).lastModified))

      if(jarOK) {
        // pre-compiled jar is current
        handler(jarFile.getAbsolutePath)
      } else {
        // The pre-compiled jar is old.  Recompile the script.
        jarFile.delete
        val Pair(compiledPath, compok) = compile
        try {
          if(compok) {
            tryMakeJar(jarFile, compiledPath)
            if(jarOK) {
              deleteRecursively(compiledPath)
              handler(jarFile.getAbsolutePath)
            } else {
              // run from the interpreter's temporary
              // directory
              handler(compiledPath.getPath)
            }
          }
        } finally {
          deleteRecursively(compiledPath)
        }
      }
    } else {
      // don't use the cache; just run from the interpreter's temporary directory
      val Pair(compiledPath, compok) = compile
      try {
        if(compok)
          handler(compiledPath.getPath)
      } finally {
        deleteRecursively(compiledPath)
      }
    }
  }
      
  /** Run a script file with the specified arguments and compilation
    * settings.
    */
  def runScript(
      settings: GenericRunnerSettings, 
      scriptFile: String, 
      scriptArgs: List[String]): Unit = 
  {
    if(!(new File(scriptFile)).exists) {
      Console.println("no such file: " + scriptFile)
      return ()
    }
    
    withCompiledScript(settings, scriptFile)(compiledLocation => {
      def pparts(path: String) = path.split(File.pathSeparator).toList
          
      val classpath =
        pparts(settings.bootclasspath.value) :::
        List(compiledLocation) :::
        pparts(settings.classpath.value)
            
      try {
        ObjectRunner.run(
          classpath,
          "scala.scripting.Main",
          scriptArgs.toArray)
      } catch {
        case e:InvocationTargetException =>
          e.getCause.printStackTrace
      }
    })
  }
}
