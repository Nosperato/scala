class Point1(x: int) extends Object with Ordered[Point1] {
  val xCoord = x;
  def compareTo [b >: Point1 <% Ordered[b]](that: b): int = that match {
    case that1: Point1 => this.xCoord.compareTo(that1.xCoord)
    case _ => -that.compareTo(this)
  }
}
class Point2(x: int, y: int) extends Point1(x) with Ordered[Point2] {
  val yCoord = y;
  def compareTo [b >: Point2 <% Ordered[b]](that: b): int = that match {
    case that1: Point2 => 
      val r = super.compareTo(that1);
      if (r == 0) this.yCoord.compareTo(that1.yCoord) else r
    case _ => -that.compareTo(this)
  }
}
object Test with Application {
  val p1 = new Point1(1);
  val q1 = new Point1(2);
  System.out.println(p1 < q1);
  val p2 = new Point2(1, 2);
  val q2 = new Point2(1, 3);
  System.out.println(p2 < q2);
  System.out.println(p1 < q2);
  System.out.println(p2 < q1);
}
