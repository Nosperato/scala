/*                     __                                               *\
**     ________ ___   / /  ___     Scala API                            **
**    / __/ __// _ | / /  / _ |    (c) 2003-2006, LAMP/EPFL             **
**  __\ \/ /__/ __ |/ /__/ __ |                                         **
** /____/\___/_/ |_/____/_/ | |                                         **
**                          |/                                          **
\*                                                                      */

// $Id$


package scala.concurrent;


/**
* Library for using Pi-calculus concurrent primitives in Scala. As an example,
the definition of a two-place buffer using the <b>pilib</b> library looks like:
*<pre>
*def Buffer[a](put: Chan[a], get: Chan[a]): unit = {
*  def B0: unit = choice ( put * { x => B1(x) } );    
*  def B1(x: a): unit = choice ( get(x) * B0, put * { y => B2(x, y) } );
*  def B2(x: a, y: a): unit = choice ( get(x) * B1(y) );
*  B0
*}
*</pre>
*
* @see <a href="http://scala.epfl.ch/docu/related.html">PiLib: A Hosted Language for Pi-Calculus Style Concurrency</a>
* @author Vincent Cremet, Martin Odersky
*/ 
object pilib {

  /////////////////////////// SPAWN //////////////////////////////

  /**
  * Run several processes in parallel using the following syntax:
  * spawn &lt; p_1 | ... | p_n &gt;
  */
  abstract class Spawn {
    def <(p: => unit): Spawn
    def |(p: => unit): Spawn
    def > : unit
  }
  val spawn = new Spawn {
  //object spawn extends Spawn { // BUG !
    def <(p: => unit): Spawn = { scala.concurrent.ops.spawn(p); this }
    def |(p: => unit): Spawn = { scala.concurrent.ops.spawn(p); this }
    def > : unit = ()
  }

  //////////////////////// GUARDED PROCESSES /////////////////////////

  /** Untyped channel. */
  class UChan {
    /** Default log function. */
    var log = (x: Any) => ()
  }

  /** An untyped guarded process.
  * @param n         channel name
  * @param polarity  input (true) or output (false)
  * @param v         transmitted value
  * @param c         continuation
  */
  case class UGP(n: UChan, polarity: boolean, v: Any, c: Any => Any);

  /** Typed guarded process. */
  class GP[a](n: UChan, polarity: boolean, v: Any, c: Any => a) {
    val untyped = UGP(n, polarity, v, c)
  }

  ////////////////////////// CHANNELS //////////////////////////////

  /**
   * Name on which one can emit, receive or that can be emitted or received
   * during a communication.
   */
  class Chan[a] extends UChan with Function1[a, Product[a]] {

    var defaultValue: a = _

    /** Creates an input guarded process. */
    def input[b](c: a => b) =
      new GP(this, true, (), x => c(x.asInstanceOf[a]))

    /** Creates an input guarded process. */
    def output[b](v: a, c: () => b) =
      new GP(this, false, v, x => c())

    /** Blocking read. */
    def read = {
      var res: a = defaultValue
      choice ( input(x => res = x) )
      res
    }

    /** Blocking write. */
    def write(x: a) =
      choice ( output(x, () => ()) )

    /** Syntactic sugar for input. */
    def *[b](f: a => b) =
      input(f);

    /** Syntactic sugar for output. */
    def apply(v: a) =
      new Product(this, v)

    /** Attach a function to be evaluated at each communication event
    * on this channel. Replace previous attached function.
    */
    def attach(f: a => unit) =
      log = x => f(x.asInstanceOf[a])
  }

  class Product[a](c: Chan[a], v: a) {
    def *[b](f: => b) = c.output(v, () => f)
  }

  //////////////////// SUM OF GUARDED PROCESSES //////////////////////

  case class Sum(gs: List[UGP]) {

    /** Continuation of the sum. */
    var cont: () => Any = _

    var initialized = false

    /**
    * Block if not initialized otherwise continue with the
    * continuation.
    */
    def continue = synchronized {
      if (!initialized) wait();
      cont()
    }

    /** Set the values of parameters and awake the sleeping sum. */
    def set(f: () => Any) = synchronized {
      cont = f
      initialized = true
      notify()
    }
  }

  /////////////////////////// COMMUNICATION  //////////////////////////

  private var sums: List[Sum] = Nil

  /** Test if two lists of guarded processes can communicate. */
  private def matches(gs1: List[UGP], gs2: List[UGP]): Option[Triple[() => unit, () => Any, () => Any]] =
    Pair(gs1, gs2) match {
      case Pair(Nil, _) => None
      case Pair(_, Nil) => None
      case Pair(UGP(a1, d1, v1, c1) :: rest1, UGP(a2, d2, v2, c2) :: rest2) =>
        if (a1 == a2 && d1 == !d2)          
          Some(Triple(() => if (d1) a1.log(v2) else a1.log(v1), () => c1(v2), () => c2(v1)))
        else matches(gs1, rest2) match {
          case None => matches(rest1, gs2)
          case Some(t) => Some(t)
        }
    }

  /**
   * Test if the given sum can react with one of the pending sums.
   * If yes then do the reaction otherwise append the sum at the end
   * of the pending sums.
   */
  private def compare(s1: Sum, ss: List[Sum]): List[Sum] =
    ss match {
      case Nil => ss ::: List(s1)
      case s2 :: rest => matches(s1.gs, s2.gs) match {
        case None => s2 :: compare(s1, rest)
        case Some(Triple(log, c1, c2)) => {
          log()
          s1.set(c1)
          s2.set(c2)
          rest
        }
      }
    }

  /** Pi-calculus non-deterministic choice. */
  def choice[a](s: GP[a]*): a = {
    val sum = Sum(s.asInstanceOf[List[GP[a]]] map { x => x.untyped });
    synchronized { sums = compare(sum, sums) };
    (sum.continue).asInstanceOf[a]
  }


}

