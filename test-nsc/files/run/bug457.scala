object Foo {

//   def method= {
//     val x = "Hello, world";
//     val y = 100;
    
//      y match {
//        case _: Int 
//         if (x match { case t => t.trim().length() == 0 }) =>
//           false;
// //      case _ => true;
//     }
//   }

  def method2(): scala.Boolean = {
    val x: java.lang.String = "Hello, world";
    val y: scala.Int = 100;
    {
      var temp1: scala.Int = y;
      var result: scala.Boolean = false;
      if (
        {
          var result1: scala.Boolean = false;
          if (y == 100)
            result1
          else
            scala.MatchError.fail("crazybox.scala", 11)
        } && (y == 90)
      )
        result
      else
        scala.MatchError.fail("crazybox.scala", 9);
    }
  }

}
