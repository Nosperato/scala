module lists {

  trait List[a] {
      def isEmpty: Boolean;
      def head: a;
      def tail: List[a];
      def prepend(x: a) = Cons[a](x, this);
  }

  def Nil[a] = new List[a] {
    def isEmpty: Boolean = True;
    def head = error[a]("head of Nil");
    def tail = error[List[a]]("tail of Nil");
  }

  def Cons[a](x: a, xs: List[a]): List[a] = new List[a] {
    def isEmpty = Boolean.False;
    def head = x;
    def tail = xs;
  } 

  def foo = {
    val intnil = Nil[Int];
    val intlist = intnil.prepend(1).prepend(1+1);
    val x: Int = intlist.head;
    val strnil = Nil[String];
    val strlist = strnil.prepend("A").prepend("AA");
    val y: String = strlist.head;
    ()
  }

  class IntList() extends List[Int] {
    def isEmpty: Boolean = False;
    def head: Int = 1;
    def foo: List[Int] with { def isEmpty: Boolean; def head: Int; def tail: List[Int] } = Nil[Int];
    def tail0: List[Int] = foo.prepend(1).prepend(1+1);
    def tail: List[Int] = Nil[Int].prepend(1).prepend(1+1);
  }

  def foo2 = {
    val il1 = new IntList();
    val il2 = il1.prepend(1).prepend(2);
    ()
  }
}