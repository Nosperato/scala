package test;

trait Ordered[a] {
  def < (x: a): boolean;
}

object O {
  def view (x: String): Ordered[String] = new Ordered[String] {
    def < (y: String) = x.compareTo(y) < 0;
  }
}

object Empty extends Tree[All];
case class Node[+c <% Ordered[c]](elem: c, l: Tree[c], r: Tree[c]) extends Tree[c];

trait Tree[+a <% Ordered[a]] {
  def insert[b >: a <% Ordered[b]](x: b): Tree[b] = this match {
    case Empty => new Node(x, Empty, Empty)
    case Node(elem, l, r) => 
      if (x == elem) this
      else if (x < elem) Node(elem, l insert x, r)
      else Node(elem, l, r insert x);
  }
  def elements: List[a] = this match {
    case Empty => List()
    case Node(elem, l, r) => 
      l.elements ::: List(elem) ::: r.elements
  }
}

object Test {
  import O.view;

  def main(args: Array[String]) = {
    var t: Tree[String] = Empty;
    for (val s <- args) {
      t = t insert s
    }
    System.out.println(t.elements)
  }
}
