import java.io._
object Test {
  var c = 0
  println((c = 1) > 0)
  println((c = 1) <= 0)
  println((c = 1) == 0)

  println(1 == "abc")
  println(1 != true)

  println(((x: int) => x + 1) == null)
  println(new Object == new Object)
  println(new Array(1) != new Array(1))

  val foo: Array[String] = Array("1","2","3");
  if( foo.length == null )    //  == 0 makes more sense, but still
    Console.println("plante"); // this code leads to runtime crash
  else
    Console.println("plante pas");

  def main(args: Array[String]) = {
    val in = new FileInputStream(args(0))
    
    var c = 0
    
    while((c = in.read) != -1) {
      Console.print(c.toChar)
    }
    
    in.close
  }

}
