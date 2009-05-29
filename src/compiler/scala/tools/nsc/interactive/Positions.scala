package scala.tools.nsc.interactive

import ast.Trees
import scala.tools.nsc.util.{SourceFile, Position, RangePosition, OffsetPosition, NoPosition, WorkScheduler}
import scala.collection.mutable.ListBuffer

/** Handling range positions
 *  atPos, the main method in this trait, will add positions to a tree,
 *  and will ensure the following properties:
 *
 *    1. All nodes between the root of the tree and nodes that already have positions
 *       will be assigned positions.
 *    2. No node which already has a position will be assigned a different range; however
 *       a RangePosition might become a TransparentPosition.
 *    3. The position of each assigned node includes the positions of each of its children.
 *    4. The positions of all solid descendants of children of an assigned node
 *       are mutually non-overlapping.
 *
 * Here, the solid descendant of a node are:
 *
 *   If the node has a TransparentPosition, the solid descendants of all its children
 *   Otherwise, then singleton consisting of the node itself.
 */
trait Positions extends Trees { 
self: Global =>

  case class Range(val pos: Position, val tree: Tree) {
    def isFree = tree == EmptyTree
  }

  class TransparentPosition(source0: SourceFile, start: Int, point: Int, end: Int) extends RangePosition(source0, start, point, end) {
    override def toString = "<"+super.toString+">"
  }

  def isTransparent(pos: Position) = pos.isInstanceOf[TransparentPosition]
  def isRange(pos: Position) = pos.isInstanceOf[RangePosition]

  // -------------- ensuring no overlaps -------------------------------

  def solidDescendants(tree: Tree): List[Tree] = 
    if (isTransparent(tree.pos)) tree.children flatMap solidDescendants else List(tree)

  /** A free range from `lo` to `hi` */
  private def free(lo: Int, hi: Int): Range = 
    Range(new RangePosition(null, lo, lo, hi), EmptyTree)

  /** The maximal free range */
  private val maxFree: Range = free(0, Math.MAX_INT)

  /** A singleton list of a non-empty range from `lo` to `hi`, or else the empty List */ 
  private def maybeFree(lo: Int, hi: Int) = if (lo < hi) List(free(lo, hi)) else List()

  /** Insert `pos` into ranges `rs` if possible;
   *  otherwise add conflicting trees to `conflicting`.
   */
  private def insert(rs: List[Range], t: Tree, conflicting: ListBuffer[Tree]): List[Range] = rs match {
    case List() =>
      assert(conflicting.nonEmpty)
      rs
    case r :: rs1 =>
      assert(!isTransparent(t.pos))
      if (r.isFree && (r.pos includes t.pos)) {
        maybeFree(t.pos.end, r.pos.end) ::: List(Range(t.pos, t)) ::: maybeFree(r.pos.start, t.pos.start) ::: rs1
      } else {
        if (!r.isFree && (r.pos overlaps t.pos)) conflicting += r.tree 
        r :: insert(rs1, t, conflicting)
      }
  }

  /** Replace elem `t` of `ts` by `replacement` list. */
  private def replace(ts: List[Tree], t: Tree, replacement: List[Tree]): List[Tree] = 
    if (ts.head == t) replacement ::: ts.tail
    else ts.head :: replace(ts.tail, t, replacement)

  /** Ensure that given list of trees has mutually non-overlapping positions,
   *  by assinging TransparentPositions to some of them, if necessary
   */
  def ensureNonOverlapping(cts: List[Tree]): Unit = {

    /** Do a pass over all child trees `cts`, where `ranges` reflects positions previously
     *  encountered. If there are overlaps, break up one node by making its position a TransparentPosition
     *  and do another pass of `ensureOverlapping`.
     *  @param ranges   The current list of non-overlapping ranges,
     *                  both occupied and free, sorted from later to earlier.
     *                  No TransparentPositions allowed here!
     *  @param trees    The list of trees to insert in ranges.
     */
    def iterate(ranges: List[Range], trees: List[Tree]): Unit = trees match {
      case List() => 
        ;
      case ct :: trees1 =>
        if (isTransparent(ct.pos))
          iterate(ranges, solidDescendants(ct) ::: trees1)
        else if (ct.pos.isDefined && !ct.pos.isSynthetic) {
          val conflicting = new ListBuffer[Tree]
          val ranges1 = insert(ranges, ct, conflicting)
          if (conflicting.isEmpty) {
            iterate(ranges1, trees1)
          } else {
            val splitNode = 
              if (conflicting.size == 1 && (conflicting.head.pos includes ct.pos)) conflicting.head
              else ct
            splitNode setPos new TransparentPosition(splitNode.pos.source.get, splitNode.pos.start, splitNode.pos.point, splitNode.pos.end)
            ensureNonOverlapping(replace(cts, splitNode, solidDescendants(splitNode)))
          }
        }
    }

    iterate(List(maxFree), cts)
  }

  /** Does given list of trees have mutually non-overlapping positions? */
  def findOverlapping(cts: List[Tree]): List[(Tree, Tree)] = {
    var ranges = List(maxFree)
    for (ct <- cts) {
      if (ct.pos.isDefined && !ct.pos.isSynthetic) {
        val conflicting = new ListBuffer[Tree]
        ranges = insert(ranges, ct, conflicting)
        if (conflicting.nonEmpty) return conflicting.toList map (t => (t, ct))
      }
    }
    List()
  }

  // -------------- setting positions -------------------------------

  /** The sorted list of descendant nodes, a long positions */

  /** Set position of all children of a node
   *  @param  pos   A target position.
   *                Uses the point of the position as the point of all positions it assigns.
   *                Uses the start of this position as an Offset position for unpositioed trees
   *                without children.
   *  @param  trees  The children to position
   */
  private def setChildrenPos(pos: Position, trees: List[Tree]) {
    var currentPos = pos
    for (tree <- trees) {
      if (tree != EmptyTree && tree.pos == NoPosition) {
        val children = tree.children
        if (children.isEmpty)
          tree setPos OffsetPosition(pos.source.get, currentPos.start)
        else {
          setChildrenPos(currentPos, children)
          tree setPos new RangePosition(
            pos.source.get, (children map (_.pos.start)).min, pos.point, (children map (_.pos.end)).max)
        }
        currentPos = new RangePosition(pos.source.get, tree.pos.end, pos.point, pos.end)
      }
//      println("set children pos "+pos+" of "+trees)
    }
    ensureNonOverlapping(trees)
  }

  /** Position a tree.
   *  This means: Set position of a node and position all its unpositioned children.
   */
  override def atPos[T <: Tree](pos: Position)(tree: T): T =
    if (isRange(pos)) {
      if (tree != EmptyTree && tree.pos == NoPosition) {
        tree.setPos(pos)
        val children = tree.children
        if (children.nonEmpty) {
          if (children.tail.isEmpty) atPos(pos)(children.head)
          else setChildrenPos(pos, children)
        }
      }
      tree
    } else {
      super.atPos(pos)(tree)
    } 

  // ---------------- Validating positions ----------------------------------

  def validatePositions(tree: Tree) {
    def error(msg: String) {
      inform("**** bad positions:")
      inform(msg)
      inform("================= in =================")
      inform(tree.toString)
    }
    def validate(tree: Tree, encltree: Tree) {
      if (tree != EmptyTree && !tree.pos.isDefined)
        error("tree without position: "+tree)
      if (encltree.pos.isSynthetic && !tree.pos.isSynthetic) 
        error("synthetic "+encltree+" contains nonsynthetic" + tree)
      if (!(encltree.pos includes tree.pos))
        error(encltree+" does not include "+tree)
      for ((t1, t2) <- findOverlapping(tree.children flatMap solidDescendants))
        error("overlapping trees: "+t1+" === and === "+t2)
      for (ct <- tree.children flatMap solidDescendants) validate(ct, tree)
    }
    validate(tree, tree)
  }

  // ---------------- Locating trees ----------------------------------

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
      if (!t.pos.isSynthetic && (t.pos includes pos)) {
        if (!isTransparent(t.pos)) last = t
        super.traverse(t)
      }
    }
  }
}
