public class TestFromJava {
  void test() {
    final X x = new X();
    final OuterImpl o = new OuterImpl(x);
    
    final OuterImpl.Inner i1 = o.newInner();
    i1.getT().getI().getT().getI();  // <--- Error: "The method getI() is undefined for the type Object"
   
    final Outer<X>.Inner i2 = o.newInner();
    i2.getT().getI().getT().getI(); // <--- Error: "The method getI() is undefined for the type Object"
  }
}