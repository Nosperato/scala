package scala;

trait Iterator[a] {
  def hasNext: Boolean;
  def next: a;

  def foreach(f: a => Unit): Unit = 
    while (hasNext) { f(next) }

  def map[b](f: a => b): Iterator[b] = new Iterator[b] {
    def hasNext = Iterator.this.hasNext;
    def next = f(Iterator.this.next)
  }

  def flatMap[b](f: a => Iterator[b]): Iterator[b] = new Iterator[b] {
    private var cur: Iterator[b] = Iterator.empty;
    def hasNext: Boolean = 
      if (cur.hasNext) True
      else if (Iterator.this.hasNext) { cur = f(Iterator.this.next); hasNext }
      else False;
    def next: b = 
      if (cur.hasNext) cur.next
      else if (Iterator.this.hasNext) { cur = f(Iterator.this.next); next }
      else error("next on empty iterator");
  }

  def filter(p: a => Boolean): Iterator[a] = new BufferedIterator[a] {
    private val source: BufferedIterator[a] = 
      Iterator.this.buffered;
    private def skip: Unit = 
      while (source.hasNext && !p(source.head)) { source.next; () }
    def hasNext: Boolean = 
      { skip; source.hasNext }
    def next: a = 
      { skip; source.next }
    def head: a = 
      { skip; source.head; }
  }

  def zip[b](that: Iterator[b]) = new Iterator[Pair[a, b]] with {
    def hasNext = Iterator.this.hasNext && that.hasNext;
    def next = Pair(Iterator.this.next, that.next);
  }

  def buffered: BufferedIterator[a] = new BufferedIterator[a] {
    private var hd: a = _;
    private var ahead: Boolean = False;
    def head: a = {
      if (!ahead) { hd = Iterator.this.next; ahead = True }
      hd
    }
    def next: a = 
      if (ahead) { ahead = False; hd }
      else head;
    def hasNext: Boolean = 
      ahead || Iterator.this.hasNext;
    override def buffered: BufferedIterator[a] = this;
  }
}

module Iterator {

  def empty[a] = new Iterator[a] {
    def hasNext = False;
    def next: a = error("next on empty iterator");
  }

  def fromArray[a](xs: Array[a]) = new Iterator[a] {
    private var i = 0;
    def hasNext: Boolean = 
      i < xs.length;
    def next: a = 
      if (i < xs.length) { val x = xs(i) ; i = i + 1 ; x }
      else error("next on empty iterator");
  }

  def range(lo: Int, hi: Int) = new Iterator[Int] {
    private var i = 0;
    def hasNext: Boolean = 
      i <= hi;
    def next: Int = 
      if (i <= hi) { i = i + 1 ; i - 1 } 
      else error("next on empty iterator");
  }

  def from(lo: Int) = new Iterator[Int] {
    private var i = 0;
    def hasNext: Boolean = 
      True;
    def next: Int = 
      { i = i + 1 ; i - 1 } 
  }
}



