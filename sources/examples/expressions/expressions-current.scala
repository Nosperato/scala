package test;

abstract class Lang {
  trait Visitor { 
    def caseNum(n: int): unit;
  }

  abstract class Exp {
    def visit(v: visitor): unit;
  }

  type visitor <: Visitor;

  class Num(n: int) extends Exp {
    def visit(v: visitor): unit = v.caseNum(n);
  }
  
  class Eval(result: Ref[int]): visitor extends Visitor {
    def caseNum(n: int) = result.elem = n;
  }
}

abstract class Lang2 extends Lang {
  abstract class Visitor2 extends Visitor { 
    def casePlus(left: Exp, right: Exp): unit;
  }

  type visitor <: Visitor2;

  class Plus(l: Exp, r: Exp) extends Exp {
    def visit(v: visitor): unit = v.casePlus(l, r);
  }
  
  class Eval2(result: Ref[int]): visitor extends Eval(result) with Visitor2 {
    def casePlus(l: Exp, r: Exp) = 
      result.elem = { l.visit(this); result.elem } + { r.visit(this); result.elem }
  }

  class Show2(result: Ref[String]): visitor extends Visitor2 {
    def caseNum(n: int) = result.elem = n.toString();
    def casePlus(l: Exp, r: Exp) = 
      result.elem = 
	"(" + { l.visit(this); result.elem } + 
        "+" + { r.visit(this); result.elem }+ ")";
  }
}

object Main {
  
  def main(args: Array[String]) = {
    val l1 = new Lang { type visitor = Visitor }
    val e1: l1.Exp = new l1.Num(42);
    
    val iref = new Ref(0);
    System.out.println("eval: " + { e1.visit(new l1.Eval(iref)); iref.elem });

    val l2 = new Lang2 { type visitor = Visitor2 }
    val e2: l2.Exp = new l2.Plus(new l2.Num(5), new l2.Num(37));
    val sref = new Ref("");
    System.out.println("eval: " + { e2.visit(new l2.Eval2(iref)); iref.elem });
    System.out.println("show: " + { e2.visit(new l2.Show2(sref)); sref.elem });
  }
}
