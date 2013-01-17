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

See also: [shen.clj](https://github.com/hraberg/shen.clj)


## This Java Port

Is a work in progress - it really doesn't work yet. The main [Shen JVM port](https://www.assembla.com/code/shen-on-java/git/nodes) is done by Joel Shellman and might be used for [Babel](http://www.shenlanguage.org/babel/babel.htm), Mark's IDE project.

Shen.java is an invokedynamic based K Lambda compiler. I don't vouch for any of the implementation details regarding this - I'm learning as we go.
All code lives in [`Shen.java`](https://github.com/hraberg/Shen.java/blob/master/src/shen/Shen.java).

It's loosely based on [`shen.clj`](https://github.com/hraberg/shen.clj), but has no dependency on Clojure.
Started as an [interpreter](https://github.com/hraberg/Shen.java/blob/master/src/shen/Shen.java) using [MethodHandles](http://docs.oracle.com/javase/7/docs/api/java/lang/invoke/MethodHandle.html) as a primitive.
This is pretty experimental, and this entire project acts as a playground for various JDK 8 and JVM language stuff.

There's an IntelliJ project, which requires [IDEA 12](http://www.jetbrains.com/idea/download/index.html) and [JDK 8 with Lambda support](http://jdk8.java.net/lambda/). It's based on this [Maven project](https://github.com/hraberg/Shen.java/blob/master/pom.xml).


### Building

    export JAVA_HOME=/path/to/jdk1.8.0/with/lambdas
    mvn package
    $JAVA_HOME/bin/java -jar target/shen.java-*-standalone.jar

    Shen 2010, copyright (C) 2010 Mark Tarver
    www.shenlanguage.org, version 7.1
    running under Java, implementation: [jvm 1.8.0-ea]
    port 0.1.0-SNAPSHOT ported by Håkan Råberg


    (0-)


### What works?

* The K Lambda parser.
* KL special forms.
* Partial application.
* Implicit recur.
* Simple Java inter-op (based on Clojure's syntax).
* [Dominik's tests](https://github.com/hraberg/Shen.java/blob/master/test/shen/PrimitivesTest.java) from [Shen to Clojure](http://code.google.com/p/shen-to-clojure/).


### Road Map

This is bound to change as we go:

* Full KL compilation.
* Ability to load the `kl`.
* The REPL
* The Shen test suite.
* SwitchPoints for symbols.
* Saner choice of target method.
* Different bootstrap methods for invoke, apply and value/set.
* Proper arithmetic.
* Pre-compilation of the `kl` to `.class` files.
* Ad-herence to Shen types when compiling typed Shen.
* Performance.


## References

[The Book of Shen](http://www.shenlanguage.org/tbos.html) (Tarver, 2012)

[Dynalink](https://github.com/szegedi/dynalink) (Szegedi, 2010-12) "Dynamic Linker Framework for Languages on the JVM"

[Asm 4.0](http://asm.ow2.org/index.html) (Bruneton, 2007-12) "A Java bytecode engineering library"

[JDK 8 with Lambda support](http://jdk8.java.net/lambda/)

[Nashorn](https://blogs.oracle.com/nashorn/entry/open_for_business) "ECMAScript 5.1 that runs on top of JVM."

[Optimizing JavaScript and Dynamic Languages on the JVM](http://www.oracle.com/javaone/lad-en/program/schedule/sessions/con5390-enok-1885659.pdf) (Lagergren and Friberg, 2012)

[Invokedynamic them all](https://speakerdeck.com/forax/invokedynamic-them-all) (Forax, 2012)

[Scheme in one class](https://blogs.oracle.com/jrose/entry/scheme_in_one_class) (Rose, 2010) - Parts of this looks pretty similar actually! Slightly more advanced/complex, has Java interop but no lambdas. Haven't been updated from the older java.dyn package. "semi-compiled" to MHs, no ASM used.

[Runtime metaprogramming via java.lang.invoke.MethodHandle](http://lampwww.epfl.ch/~magarcia/ScalaCompilerCornerReloaded/2012Q2/RuntimeMP.pdf) (Garcia, 2012) - The idea of building the AST from MethodHandles without using ASM did occur to me, and looks like it could be possible. Not sure you can actually create a fn definition though (see above). Did a spike, doesn't seem easy/worth the hassle, may revisit.

[JSR292 Cookbook](http://code.google.com/p/jsr292-cookbook/) | [video](http://medianetwork.oracle.com/video/player/1113248965001) (Forax, 2011)

[Patterns and Performance of InvokeDynamic in JRuby](http://bit.ly/jjug-indy-jruby-en) (Nakamura, 2012)

[InvokeDynamic: Your API for HotSpot](http://www.slideshare.net/boundaryinc/invoke-dynamic-your-api-to-hotspot) (Arcieri, 2012)

[Invokedynamic and JRuby](http://vimeo.com/27207224) (Bini, 2011) - Last time I met Ola he said that he considered to go back to C for his next language VM.
