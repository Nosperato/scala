/* NSC -- new scala compiler
 * Copyright 2005 LAMP/EPFL
 * @author  Martin Odersky
 */
// $Id$
package scala.tools.nsc.symtab;

import java.io.IOException;
import java.io.File;
import scala.tools.nsc.util.Position;
import scala.tools.nsc.util.ClassPath;
import scala.tools.util.{AbstractFile};
import scala.tools.nsc.util.NameTransformer;
import scala.collection.mutable.HashMap;
import classfile.{ClassfileParser, SymblfileParser};
import Flags._;


abstract class SymbolLoaders {
  val global: Global;
  import global._;

  /** A lazy type that completes itself by calling parameter doComplete.
   *  Any linked modules/classes or module classes are also initialized.
   *  @param doComplete    The type completion procedure to be run.
   *                       It takes symbol to compkete as parameter and returns
   *                       name of file loaded for completion as a result.
   *                       Can throw an IOException on error.
   */
  abstract class SymbolLoader extends LazyType {
    /** Load source or class file for `root', return */
    protected def doComplete(root: Symbol): unit;
    /** The kind of file that's processed by this loader */
    protected def kindString: String;
    private var ok = false;
    private def setSource(sym: Symbol, sourceFile0: AbstractFile): unit = sym match {
      case clazz: ClassSymbol => if (sourceFile0 != null) clazz.sourceFile = sourceFile0;
      case _ => if (sourceFile0 != null) if (false) System.err.println("YYY: " + sym + " " + sourceFile0);
    }
    def sourceFile : AbstractFile = null;
    protected def sourceString : String;

    override def complete(root: Symbol): unit = {
      try {
        val start = System.currentTimeMillis();
        val currentphase = phase;
        doComplete(root);
        phase = currentphase;
	def source = kindString + " " + sourceString;
        informTime("loaded " + source, start);
        if (root.rawInfo != this) {
	  ok = true;
	  val sourceFile0 = if (sourceFile == null) (root match {
	    case clazz: ClassSymbol => clazz.sourceFile;
	    case _ => null;
	  }) else sourceFile; 
	  setSource(root.linkedModule, sourceFile0);
	  setSource(root.linkedClass,  sourceFile0);
	} else error(source + " does not define " + root)
      } catch {
        case ex: IOException =>
	  if (settings.debug.value) ex.printStackTrace();
          val msg = ex.getMessage();
          error(
            if (msg == null) "i/o error while loading " + root.name
            else "error while loading " + root.name + ", " + msg);
      }
      initRoot(root);
      if (!root.isPackageClass) initRoot(root.linkedSym);
    }
    override def load(root: Symbol): unit = complete(root);

    private def initRoot(root: Symbol): unit = {
      if (root.rawInfo == this) {
        root.setInfo(if (ok) NoType else ErrorType);
        if (root.isModule) 
          root.moduleClass.setInfo(if (ok) NoType else ErrorType)
      }
      if (root.isClass && !root.isModuleClass) root.rawInfo.load(root)
    }
  }

  /** Load contents of a package
   */
  class PackageLoader(directory: ClassPath.Context) extends SymbolLoader { 
    // System.err.println("PACKAGE LOADER: " + directory);

    protected def sourceString = directory.toString();

    protected def doComplete(root: Symbol): unit = {
      assert(root.isPackageClass, root);
      root.setInfo(new PackageClassInfoType(new Scope(), root));
      
      /** Is the given name a valid input file base name? */
      def isValid(name: String): boolean =
        name.length() > 0 && !name.endsWith("$class") && name.indexOf("$anon") == -1;

      def enterPackage(str: String, completer: SymbolLoader): unit = {
        val pkg = root.newPackage(Position.NOPOS, newTermName(str));
        pkg.moduleClass.setInfo(completer);
        pkg.setInfo(pkg.moduleClass.tpe);
        root.info.decls.enter(pkg)
      }

      def enterClassAndModule(str: String, completer: SymbolLoader): unit = {
	val owner = if (root.isRoot) definitions.EmptyPackageClass else root;
	val name = newTermName(str);
        val clazz = owner.newClass(Position.NOPOS, name.toTypeName);
        val module = owner.newModule(Position.NOPOS, name);
	if (completer.sourceFile != null) clazz.sourceFile = completer.sourceFile;
        clazz.setInfo(completer);
	module.setInfo(completer);
        module.moduleClass.setInfo(moduleClassLoader);
        owner.info.decls.enter(clazz);
        owner.info.decls.enter(module);
	assert(clazz.linkedModule == module, module);
	assert(module.linkedClass == clazz, clazz);
      }
      val classes  = new HashMap[String, ClassPath.Context];
      val packages = new HashMap[String, ClassPath.Context];
      for (val dir <- directory.classes) if (dir != null) {
	val it = dir.list();
	while (it.hasNext()) {
	  val file = it.next().asInstanceOf[AbstractFile];
	  if (file.isDirectory() && directory.validPackage(file.getName()) && !packages.isDefinedAt(file.getName()))
	    packages(file.getName()) = directory.find(file.getName(), true);
	  else if (!file.isDirectory() && file.getName().endsWith(".class")) {
	    val name = file.getName().substring(0, file.getName().length() - (".class").length());
	    if (isValid(name) && !classes.isDefinedAt(name)) 
	      classes(name) = directory.find(name, false);
	  }
	}
      }
      for (val dir <- directory.sources) if (dir != null) {
	val it = dir.location.list();
	while (it.hasNext()) {
	  val file = it.next().asInstanceOf[AbstractFile];
	  if (file.isDirectory() && directory.validPackage(file.getName()) && !packages.isDefinedAt(file.getName()))
	    packages(file.getName()) = directory.find(file.getName(), true);
	  else if (dir.compile && !file.isDirectory() && file.getName().endsWith(".scala")) {
	    val name = file.getName().substring(0, file.getName().length() - (".scala").length());
	    if (isValid(name) && !classes.isDefinedAt(name)) 
	      classes(name) = directory.find(name, false);
	  }
	}
      }
      //if (!packages.isEmpty) System.err.println("COMPLETE: " + packages);
      //if (! classes.isEmpty) System.err.println("COMPLETE: " + classes);
      // do classes first

      for (val Pair(name, file) <- classes.elements) {
	val loader = if (!file.isSourceFile) new ClassfileLoader(file.file, file.sourceFile, file.sourcePath);
	else new SourcefileLoader(file.file);
	enterClassAndModule(name, loader);
      }
      for (val Pair(name, file) <- packages.elements) enterPackage(name, new PackageLoader(file));
    }
    protected def kindString: String = "directory path"
  }

/*
  private object symblfileParser extends SymblfileParser {
    val global: SymbolLoaders.this.global.type = SymbolLoaders.this.global;
  }
*/

  class ClassfileLoader(classFile: AbstractFile, sourceFile0: AbstractFile, sourcePath0: AbstractFile) extends SymbolLoader {
    private object classfileParser extends ClassfileParser {
      val global: SymbolLoaders.this.global.type = SymbolLoaders.this.global;
      override def sourcePath = sourcePath0; /* could be null */
    }
    protected def doComplete(root: Symbol): unit = classfileParser.parse(classFile, root);
    protected def kindString: String = "class file";
    protected def sourceString = classFile.toString();
    override def sourceFile : AbstractFile = sourceFile0;
  }
/*
  class SymblfileLoader(file: AbstractFile) extends SymbolLoader(file) {
    protected def doComplete(root: Symbol): unit = symblfileParser.parse(file, root);
    protected def kindString: String = "symbl file";
  }
*/
  class SourcefileLoader(sourceFile0: AbstractFile) extends SymbolLoader {
    protected def doComplete(root: Symbol): unit = global.currentRun.compileLate(sourceFile0);
    protected def kindString: String = "source file";
    override def sourceFile = sourceFile0;
    protected def sourceString = sourceFile0.toString();
  }

  object moduleClassLoader extends SymbolLoader {
    protected def doComplete(root: Symbol): unit = 
      root.sourceModule.initialize;
    protected def kindString: String = "";
    protected def sourceString = "";
  }
}
