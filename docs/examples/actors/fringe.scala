package examples.actors

import scala.actors.Actor._
import scala.actors.{Channel, OutputChannel}

/**
 @author Philipp Haller
 @version 1.1, 09/21/2007
 */
object fringe extends Application {

  abstract class Tree
  case class Node(left: Tree, right: Tree) extends Tree
  case class Leaf(v: Int) extends Tree

  case class CompareFringe(t1: Tree, t2: Tree)
  case class ComputeFringe(t1: Tree, atoms: OutputChannel[Option[Leaf]])
  case class Equal(atom1: Option[Leaf], atom2: Option[Leaf])
  case class Extract(tree: Tree)

  val comparator = actor {
    val extractor1 = actor(extractorBehavior())
    val extractor2 = actor(extractorBehavior())
    val ch1 = new Channel[Option[Leaf]]
    val ch2 = new Channel[Option[Leaf]]
    loop {
      react {
        case CompareFringe(tree1, tree2) =>
          extractor1 ! ComputeFringe(tree1, ch1)
          extractor2 ! ComputeFringe(tree2, ch2)
          self ! Equal(ch1.?, ch2.?)

        case Equal(atom1, atom2) =>
          println("comparing "+atom1+" and "+atom2)
          if (atom1 == atom2) atom1 match {
            case None =>
              println("same fringe")
              exit()
            case _ =>
              self ! Triple('Equal, ch1.?, ch2.?)
          } else {
            println("fringes differ")
            exit()
          }
      }
    }
  }

  val extractorBehavior = () => {
    var output: OutputChannel[Option[Leaf]] = null
    loop {
      react {
        case ComputeFringe(tree, leafSink) =>
          output = leafSink
          self ! Extract(tree)

        case Extract(tree) => tree match {
          case atom @ Leaf(_) =>
            println("sending "+Some(atom))
            output ! Some(atom)
            sender ! 'Continue

          case Node(left, right) =>
            val outer = self
            val outerCont = sender
            val cont = actor {
              react {
                case 'Continue =>
                  outer.send(Extract(right), outerCont)
              }
            }
            self.send(Extract(left), cont)
        }

        case 'Continue =>
          output ! None
          exit()
      }
    }
  }

  comparator ! ('Fringe, Node(Leaf(5), Node(Leaf(7), Leaf(3))),
                Node(Leaf(5), Node(Leaf(7), Leaf(3))))
}
