package scala.tools.nsc.interactive

import scala.collection.mutable.{LinkedHashSet, LinkedHashMap, SynchronizedMap}
import scala.concurrent.SyncVar
import scala.tools.nsc.io.AbstractFile
import scala.tools.nsc.util.{SourceFile, Position, RangePosition, OffsetPosition, NoPosition, WorkScheduler}
import scala.tools.nsc.reporters._
import scala.tools.nsc.symtab._
import scala.tools.nsc.ast._

/** The main class of the presentation compiler in an interactive environment such as an IDE
 */
class Global(settings: Settings, reporter: Reporter) 
  extends nsc.Global(settings, reporter) with ContextTrees { 
self =>

  /** A list indicating in which order some units should be typechecked.
   *  All units in firsts are typechecked before any unit not in this list
   *  Modified by askToDoFirst, reload, typeAtTree.
   */
  var firsts: List[SourceFile] = List()

  /** A map of all loaded files units to the rich compilation units that corresponds to them.
   */ 
  val unitOfFile = new LinkedHashMap[AbstractFile, RichCompilationUnit] with
                       SynchronizedMap[AbstractFile, RichCompilationUnit]

  /** The currently active typer run */
  private var currentTyperRun: TyperRun = _

  /** Is a background compiler currently running? */
  private var compiling = false

  /** Is a reload/ background compiler currently running? */
  private var acting = false

  /** The status value of a unit that has not yet been loaded */
  final val NotLoaded = -1

  /** The status value of a unit that has not yet been typechecked */
  final val JustParsed = 0

  // ----------- Overriding hooks in nsc.Global -----------------------
  
  /** Create a RangePosition */
  override def rangePos(source: SourceFile, start: Int, mid: Int, end: Int) = 
    new RangePosition(source, start, mid, end)

  /** Called from typechecker: signal that a node has been completely typechecked
   *  @param  context  The context that typechecked the node
   *  @param  old      The original node
   *  @param  result   The transformed node
   */
  override def signalDone(context: Context, old: Tree, result: Tree) {
    def integrateNew() {
      context.unit.body = new TreeReplacer(old, result) transform context.unit.body
    }
    if (context.unit.targetPos includes result.pos) {
      integrateNew()
      throw new TyperResult(result)
    }
    val typerRun = currentTyperRun
    pollForWork()
    if (typerRun != currentTyperRun) {
      integrateNew()
      throw new FreshRunReq
    }
  }

  /** Called every time a context is created
   *  Register the context in a context tree
   */
  override def registerContext(c: Context) = c.unit match {
    case u: RichCompilationUnit => addContext(u.contexts, c)
    case _ =>
  }

  // ----------------- Polling ---------------------------------------

  /** Called from runner thread and signalDone:
   *  Poll for exeptions. 
   *  Poll for work reload/typedTreeAt/doFirst commands during background checking.
   */
  def pollForWork() {
    scheduler.pollException() match {
      case Some(ex: CancelActionReq) => if (acting) throw ex
      case Some(ex: FreshRunReq) => if (compiling) throw ex
      case Some(ex) => throw ex
      case _ =>
    }
    scheduler.nextWorkItem() match {
      case Some(action) =>
        try {
          acting = true
          action()
        } catch {
          case ex: CancelActionReq =>
        } finally {
          acting = false
        }
      case None =>
    }
  }    

  // ----------------- The Background Runner Thread -----------------------

  /* Must be initialized before starting compilerRunner */
  private val scheduler = new WorkScheduler
  
  /** The current presentation compiler runner */
  private var compileRunner = newRunnerThread

  /** Create a new presentation compiler runner.
   */
  def newRunnerThread: Thread = new Thread("Scala Presentation Compiler") {
    override def run() {
      try {
        while (true) {
          scheduler.waitForMoreWork()
          pollForWork()
          var continue = true
          while (continue) {
            try {
              compiling = true
              backgroundCompile()
              continue = false
            } catch {
              case ex: FreshRunReq => 
            } finally {
              compiling = false
            }
          }
        }
      } catch {
        case ex: ShutdownReq => 
          ;
        case ex => 
          ex.printStackTrace()
          inform("Fatal Error: "+ex)
          compileRunner = newRunnerThread
      }
    }
    start()
  }

  /** Compile all given units
   */ 
  private def backgroundCompile() {
    inform("Starting new presentation compiler type checking pass")
    firsts = firsts filter (s => unitOfFile contains (s.file))
    val prefix = firsts map unitOf
    val units = prefix ::: (unitOfFile.values.toList diff prefix)
    units foreach recompile
  }

  /** Reset unit to just-parsed state */
  def reset(unit: RichCompilationUnit): Unit =
    if (unit.status > JustParsed) {
      unit.depends.clear()
      unit.defined.clear()
      unit.synthetics.clear()
      unit.toCheck.clear()
      unit.targetPos = NoPosition
      unit.contexts.clear()
      ResetAttrs.traverse(unit.body)
      currentTyperRun.enterNames(unit)
      unit.status = JustParsed
    }
    
  /** Make sure symbol and type attributes are reset and recompile unit. 
   */
  def recompile(unit: RichCompilationUnit) {
    assert(unit.status != NotLoaded) 
    reset(unit)
    inform("type checking: "+unit)
    currentTyperRun.typeCheck(unit)
    unit.status = currentRunId
  }

  /** Move list of files to front of firsts */
  def moveToFront(fs: List[SourceFile]) {
    firsts = fs ::: (firsts diff fs)
  }

  // ----------------- Implementations of client commmands -----------------------

  /** Make sure a set of compilation units is loaded and parsed */
  def reload(sources: List[SourceFile], result: SyncVar[Either[Unit, Throwable]]) {
    try {
      currentTyperRun = new TyperRun()
      for (source <- sources) {
        val unit = new RichCompilationUnit(source)
        unitOfFile(source.file) = unit
        currentTyperRun.compileLate(unit)
        unit.status = JustParsed
      }
      moveToFront(sources)
      result set Left(())
      if (compiling) throw new FreshRunReq
    } catch {
      case ex =>
        result set Right(ex)
        throw ex
    }
  }

  /** Set sync var `result` to a fully attributed tree located at position `pos`  */
  def typedTreeAt(pos: Position, result: SyncVar[Either[Tree, Throwable]]) {
    try {
      val unit = unitOf(pos)
      assert(unit.status != NotLoaded)
      moveToFront(List(unit.source))
      result set Left(currentTyperRun.typedTreeAt(pos))
    } catch {
      case ex => 
        result set Right(ex)
        throw ex
    }
  }

  // ---------------- Helper classes ---------------------------

  /** A locator for trees with given positions.
   *  Given a position `pos`, locator.apply returns
   *  the smallest tree that encloses `pos`.
   */
  class Locator(pos: Position) extends Traverser {
    var last: Tree = _
    def locateIn(root: Tree): Tree = {
      this.last = EmptyTree
      traverse(root)
      this.last
    }
    override def traverse(t: Tree) {
      if (t.pos includes pos) {
        last = t
        super.traverse(t)
      }
    }
  }

  /** A transformer that replaces tree `from` with tree `to` in a given tree */
  class TreeReplacer(from: Tree, to: Tree) extends Transformer {
    override def transform(t: Tree): Tree = {
      if (t.pos includes from.pos)
        if (t == from) to
        else super.transform(t)
      else 
        t
    }
  }

  /** A traverser that resets all type and symbol attributes in a tree */
  object ResetAttrs extends Traverser {
    override def traverse(t: Tree) {
      if (t.hasSymbol) t.symbol = NoSymbol
      t.tpe = null
      super.traverse(t)
    }
  }

  /** The typer run */
  class TyperRun extends Run {
    // units is always empty
    // symSource, symData are ignored
    override def compiles(sym: Symbol) = false

    def typeCheck(unit: CompilationUnit) {
      applyPhase(typerPhase, unit)
    }

    def enterNames(unit: CompilationUnit) {
      applyPhase(namerPhase, unit)
    }

    /** Return fully attributed tree at given position
     *  (i.e. largest tree that's contained by position)
     */
    def typedTreeAt(pos: Position): Tree = {
      val tree = locateTree(pos)
      if (tree.tpe ne null) tree
      else {
        val unit = unitOf(pos)
        assert(unit.status >= JustParsed)
        unit.targetPos = pos
        try {
          typeCheck(unit)
          throw new FatalError("tree not found")
        } catch {
          case ex: TyperResult => ex.tree
        }
      }
    } 

    /** Apply a phase to a compilation unit */
    private def applyPhase(phase: Phase, unit: CompilationUnit) {
      val oldSource = reporter.getSource          
      try {
        reporter.setSource(unit.source)    
        atPhase(phase) { phase.asInstanceOf[GlobalPhase] applyPhase unit }
      } finally {
        reporter setSource oldSource
      }
    }
  }

  class TyperResult(val tree: Tree) extends Exception

  class RichCompilationUnit(source: SourceFile) extends CompilationUnit(source) {

    /** The runid of the latest compiler run that typechecked this unit,
     *  or else @see NotLoaded, JustParsed
     */
    var status: Int = NotLoaded

    /** the current edit point offset */
    var editPoint: Int = -1

    /** The position of a targeted type check
     *  If this is different from NoPosition, the type checking
     *  will stop once a tree that contains this position range
     *  is fully attributed.
     */
    var _targetPos: Position = NoPosition
    override def targetPos: Position = _targetPos
    def targetPos_=(p: Position) { _targetPos = p }

    var contexts: Contexts = new Contexts
    
  }

  assert(globalPhase.id == 0)

  // ----------------- interface to IDE ------------------------------------
  
  /** The compilation unit corresponding to a source file */
  def unitOf(s: SourceFile): RichCompilationUnit = unitOfFile get s.file match {
    case Some(unit) => 
      unit
    case None => 
      val unit = new RichCompilationUnit(s)
      unitOfFile(s.file) = unit
      unit
  }

  /** The compilation unit corresponding to a position */
  def unitOf(pos: Position): RichCompilationUnit = unitOf(pos.source.get)

  /** Locate smallest tree that encloses position */
  def locateTree(pos: Position): Tree = 
    new Locator(pos) locateIn unitOf(pos).body
    
  /** Locate smallest context that encloses position */
  def locateContext(pos: Position): Option[Context] = 
    locateContext(unitOf(pos).contexts, pos)
    
  /** Make sure a set of compilation units is loaded and parsed */
  def askReload(sources: List[SourceFile], result: SyncVar[Either[Unit, Throwable]]) = 
    scheduler.postWorkItem(() => reload(sources, result))

  /** Set sync var `result` to a fully attributed tree located at position `pos`  */
  def askTypeAt(pos: Position, result: SyncVar[Either[Tree, Throwable]]) = 
    scheduler.postWorkItem(() => self.typedTreeAt(pos, result))

  /** Ask to do unit first on present and subsequent type checking passes */
  def askToDoFirst(f: SourceFile) = {
    scheduler.postWorkItem { () => moveToFront(List(f)) }
  }

  /** Cancel currently pending high-priority jobs */
  def askCancel() = 
    scheduler.raise(new CancelActionReq)

  /** Cancel current compiler run and start a fresh one where everything will be re-typechecked
   *  (but not re-loaded).
   */
  def askReset() = 
    scheduler.raise(new FreshRunReq)

  /** Tell the compile server to shutdown, and do not restart again */
  def askShutdown() = 
    scheduler.raise(new ShutdownReq)

  class CancelActionReq extends Exception
  class FreshRunReq extends Exception
  class ShutdownReq extends Exception
}

