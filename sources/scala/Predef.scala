package scala;

module Predef {

  type byte = scala.Byte;
  type short = scala.Short;
  type char = scala.Char;
  type int = scala.Int;
  type long = scala.Long;
  type float = scala.Float;
  type double = scala.Double;
  type boolean = scala.Boolean;
  type unit = scala.Unit;

  val True = Boolean.True;
  val False = Boolean.False;

  def List[a](x: a*): List[a] = x as List[a];
  val List = scala.List;

  def error[err](x: String):err = new java.lang.RuntimeException(x).throw;

  def try[a](def block: a): Except[a] =
    new Except(scala.runtime.ResultOrException.tryBlock(block));

  def while(def condition: Boolean)(def command: Unit): Unit = 
    if (condition) {
      command; while(condition)(command)
    } else {
    }

  trait Until {
    def until(def condition: Boolean): Unit
  }

  def repeat(def command: Unit): Until = 
    new Until {
      def until(def condition: Boolean): Unit = {
	command ; 
	if (condition) {}
	else until(condition)
      }
    }

  type Pair = Tuple2;
  def Pair[a, b](x: a, y: b) = Tuple2(x, y);

  type Triple = Tuple3;
  def Triple[a, b, c](x: a, y: b, z: c) = Tuple3(x, y, z);
}





