/* NSC -- new Scala compiler
 * Copyright 2005-2006 LAMP/EPFL
 * @author  Martin Odersky
 */
// $Id: Main.scala 7679 2006-06-02 14:36:18 +0000 (Fri, 02 Jun 2006) odersky $
package scala.tools.nsc

import scala.tools.util.StringOps
import java.io._

/** The main class for NSC, a compiler for the programming
 *  language Scala.
 */
object CompileClient {

  def normalize(args: Array[String]): Pair[String, String] = {
    def absFileName(path: String) = new File(path).getAbsolutePath()
    def absFileNames(paths: String) = {
      def afns(sep: char): String =
        StringOps.decompose(paths, sep)
          .map(absFileName)
          .mkString("", String.valueOf(sep), "")
      if (paths.indexOf(';') > 0) afns(';')
      else if (paths.indexOf(':') > 0) afns(':')
      else absFileName(paths)
    }
    var i = 0
    val vmArgs = new StringBuffer
    var serverAdr = ""
    while (i < args.length) {
      val arg = args(i)
      if (arg endsWith ".scala") {
        args(i) = absFileName(arg)
      } else if (arg startsWith "-J") {
        vmArgs append " -"+arg.substring(2)
        args(i) = ""
      }
      i = i + 1
      if (i < args.length) {
        if (arg == "-classpath" || 
            arg == "-sourcepath" || 
            arg == "-bootclasspath" || 
            arg == "-extdirs" ||
            arg == "-d") {
          args(i) = absFileNames(args(i))
          i = i + 1
        } else if (arg == "-server") {
          serverAdr = args(i)
          args(i-1) = ""
          args(i) = ""
        }
      }
    }
    Pair(vmArgs.toString, serverAdr)
  }

  def main(args: Array[String]): unit = {
    val Pair(vmArgs, serverAdr) = normalize(args)
    if (args exists ("-verbose" ==))
      System.out.println("[Server arguments: "+args.mkString("", " ", "]"))
    val socket = if (serverAdr == "") CompileSocket.getOrCreateSocket(vmArgs)
                 else CompileSocket.getSocket(serverAdr)
    val out = new PrintWriter(socket.getOutputStream(), true)
    val in = new BufferedReader(new InputStreamReader(socket.getInputStream()))
    out.println(args.mkString("", " ", ""))
    var fromServer = in.readLine()
    while (fromServer != null) {
      System.out.println(fromServer)
      fromServer = in.readLine()
    }
    in.close()
    out.close()
    socket.close()
  }
}
