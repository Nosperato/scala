package scala {

  module a {

    module b {

      trait c {}
      def foo(x: c): c = bar(x)

    }

    def bar(x: b.c): b.c = x
  }
}
