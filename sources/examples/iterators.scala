object iterators {
  
  def printArray(xs: Array[Double]) =
    Iterator.fromArray(xs) foreach (x => System.out.println(x));

  def findGreater(xs: Array[Double], limit: Double) = 
    Iterator.fromArray(xs) 
      .zip(Iterator.from(0))
      .filter{case Pair(x, i) => x > limit}
      .map{case Pair(x, i) => i}

  def main(args: Array[String]) = {
    val xs = List(6, 2, 8, 5, 1);
    val ar = xs.copyToArray(new Array[Double](xs.length), 0);
    printArray(ar);
    findGreater(ar, 3.0) foreach { x => Console.print(x); };
  }

}
        