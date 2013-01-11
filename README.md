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

Is a work in progress - it really doesn't work yet. The main [Shen JVM port](https://www.assembla.com/code/shen-on-java/git/nodes) is done by Joel Shellman and might be used for [Babel](http://www.shenlanguage.org/babel/babel.htm), Mark's IDE project.

It's loosely based on [`shen.clj`](https://github.com/hraberg/shen.clj), but has no dependency on Clojure. The implementation is all in [`Shen.java`](https://github.com/hraberg/Shen.java/blob/master/src/shen/Shen.java).
Started as an interpreter using [MethodHandles](http://docs.oracle.com/javase/7/docs/api/java/lang/invoke/MethodHandle.html) as a primitive.
This is pretty experimental, and this entire project acts as a playground for various JDK 8 and JVM language stuff.

The idea is to compile this down to bytecode eventually. Note that I don't vouch for any of the implementation details regarding this - I'm learning as we go.
There's a start of this in [`ShenCompiler.java`](https://github.com/hraberg/Shen.java/blob/master/src/shen/ShenCompiler.java).

There's an IntelliJ project, which requires [Leda EAP](http://confluence.jetbrains.net/display/IDEADEV/IDEA+12+EAP) and [JDK 8 with Lambda support](http://jdk8.java.net/lambda/). It's based on this [Maven project](https://github.com/hraberg/Shen.java/blob/master/pom.xml).


### What works?

* The K Lambda parser.
* Self recursion as loop.
* Partial application.
* Most primitives - I use [Dominik's tests](https://github.com/hraberg/Shen.java/blob/master/test/shen/ShenTest.java) from [Shen to Clojure](http://code.google.com/p/shen-to-clojure/).


### What doesn't work?

*Regarding the interpreter, which I don't plan to finish*

* Shen - it cannot bootstrap Shen yet, dies loading [`declarations.kl`](https://github.com/hraberg/Shen.java/blob/master/shen/klambda/declarations.kl), with various errors.
* This means no REPL (one could do a KL only REPL).
* Compilation - it's currently an interpreter, but doesn't intend to stay like one.
* Use of MethodHandle - it does work, but not sure it will simplify anything later.
* Creating lambdas only to turn them into MethodHandles, with the assumption that this will be useful later down the line when compiling, but don't really know.
* Varargs method handles, but I want to generate more exact methods, but to do that, I need to generate interfaces dynamically - but I'm holding off bytecode generation until Shen "works".
* There's a very simplistic idea of having more than one MethodHandle registered per function, but it just tries them instead of picking the best one. I guess this will evolve into guards and callsites in the invokedynamic world.
* JDK < 8. Should be easy to backport by removing use of lambdas and `java.util.functions`.
* The tests currently intermittently fail with class cast for numeric operations.


## References

[The Book of Shen](http://www.shenlanguage.org/tbos.html) (Tarver, 2012)

[Dynalink](https://github.com/szegedi/dynalink) (Szegedi, 2010-12) "Dynamic Linker Framework for Languages on the JVM"

[Asm 4.0](http://asm.ow2.org/index.html) (Bruneton, 2007-12) "A Java bytecode engineering library"

[JDK 8 with Lambda support](http://jdk8.java.net/lambda/)

[Nashorn](https://blogs.oracle.com/nashorn/entry/open_for_business) "ECMAScript 5.1 that runs on top of JVM."

[Optimizing JavaScript and Dynamic Languages on the JVM](http://www.oracle.com/javaone/lad-en/program/schedule/sessions/con5390-enok-1885659.pdf) (Lagergren and Friberg
, 2012)

[Invokedynamic them all](https://speakerdeck.com/forax/invokedynamic-them-all) (Forax, 2012)

[Scheme in one class](https://blogs.oracle.com/jrose/entry/scheme_in_one_class) (Rose, 2010) - Parts of this looks pretty similar actually! Slightly more advanced/complex. Haven't been updated from the older java.dyn package. "semi-compiled" to MHs, no ASM used.

[Runtime metaprogramming via java.lang.invoke.MethodHandle](http://lampwww.epfl.ch/~magarcia/ScalaCompilerCornerReloaded/2012Q2/RuntimeMP.pdf) (Garcia, 2012) - The idea of building the AST from MethodHandles without using ASM did occur to me, and looks like it could be possible.

[JSR292 Cookbook](http://code.google.com/p/jsr292-cookbook/) (Forax, 2011)

[Patterns and Performance of InvokeDynamic in JRuby](http://bit.ly/jjug-indy-jruby-en) (Nakamura, 2012)

[InvokeDynamic: Your API for HotSpot](http://www.slideshare.net/boundaryinc/invoke-dynamic-your-api-to-hotspot) (Arcieri, 2012)
