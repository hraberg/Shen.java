# 神.java | Shen for Java

http://shenlanguage.org/

Shen is a portable functional programming language by [Mark Tarver](http://www.lambdassociates.org/) that offers

* pattern matching,
* λ calculus consistency,
* macros,
* optional lazy evaluation,
* static type checking,
* an integrated fully functional Prolog,
* and an inbuilt compiler-compiler.


## This Java Port

Is a work in progress - it really doesn't work yet. The main Shen JVM port is done by Joel Shellman and will be used for [Babel](http://www.shenlanguage.org/babel/babel.htm), Mark's IDE project.

It's loosely based on [`shen.clj`](https://github.com/hraberg/shen.clj), but has no dependency on Clojure. The implementation is all in one `.java` file.
Currently an interpreter using [MethodHandles](http://docs.oracle.com/javase/7/docs/api/java/lang/invoke/MethodHandle.html) as a primitive.
This is pretty experimental, and this entire project acts as a playground for various JDK 8 and JVM language stuff.

The idea is to compile this down to bytecode eventually.

No build exists yet, but there's an IntelliJ project, which requires [Leda EAP](http://confluence.jetbrains.net/display/IDEADEV/IDEA+12+EAP) and [JDK 8 with Lambda support](http://jdk8.java.net/lambda/).

### What works?

* The K Lambda parser.
* Self recursion as loop.
* Partial application.
* Most primitives - I use Dominik's tests from [Shen to Clojure](http://code.google.com/p/shen-to-clojure/).

### What doesn't work?

* Shen - it cannot bootstrap Shen yet, dies loading `declarations.kl`, with various errors.
* This means no REPL (one could do a KL only REPL).
* Compilation - it's currently an interpreter, but doesn't intend to stay like one.
* Use of MethodHandle - it does work, but not sure it will simplifiy anything later.
* JDK < 8. Should be easy to backport by removing use of lambdas and `java.util.functions`.


## References

[Dynalink](https://github.com/szegedi/dynalink) (Szegedi, 2010-12) "Dynamic Linker Framework for Languages on the JVM"

[Asm 4.0](http://asm.ow2.org/index.html) (Bruneton, 2007-12) "A Java bytecode engineering library"

[JDK 8 with Lambda support](http://jdk8.java.net/lambda/)
