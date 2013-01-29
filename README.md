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

**Shen.java is an [invokedynamic](http://www.slideshare.net/CharlesNutter/jax-2012-invoke-dynamic-keynote) based [K Lambda](http://www.shenlanguage.org/documentation/shendoc.htm) compiler.** I don't vouch for any of the implementation details regarding this - I'm learning as we go. All code lives in [`Shen.java`](https://github.com/hraberg/Shen.java/blob/master/src/shen/Shen.java). It now passes the Shen test suite except for one test (see below).

The main [Shen JVM port](https://www.assembla.com/code/shen-on-java/git/nodes) is done by Joel Shellman and might be used for [Babel](http://www.shenlanguage.org/babel/babel.htm), Mark's IDE project.

This port is loosely based on [`shen.clj`](https://github.com/hraberg/shen.clj), but has no dependency on Clojure.
Started as an [interpreter](https://github.com/hraberg/Shen.java/blob/2359095c59435597e5761c72dbe9f0246fad0864/src/shen/Shen.java) using [MethodHandles](http://docs.oracle.com/javase/7/docs/api/java/lang/invoke/MethodHandle.html) as a primitive. It's currently 2x slower than `shen.clj`.

This is pretty experimental, and this entire project acts as a playground for various JDK 8 and JVM language stuff. There's an IntelliJ project, which requires [IDEA 12](http://www.jetbrains.com/idea/download/index.html) and [JDK 8 with Lambda support](http://jdk8.java.net/lambda/). It's based on this [Maven project](https://github.com/hraberg/Shen.java/blob/master/pom.xml).


### To run the REPL:

    export JAVA_HOME=/path/to/jdk1.8.0/with/lambdas
    ./shen.java

    Shen 2010, copyright (C) 2010 Mark Tarver
    www.shenlanguage.org, version 7.1
    running under Java, implementation: [jvm 1.8.0-ea]
    port 0.1.0-SNAPSHOT ported by Håkan Råberg


    (0-) (define super
           [Value Succ End] Action Combine Zero ->
             (if (End Value)
                 Zero
                 (Combine (Action Value)
                          (super [(Succ Value) Succ End]
                                 Action Combine Zero))))
    super

    (1-) (define for
           Stream Action -> (super Stream Action do 0))
    for

    (2-) (define filter
           Stream Condition ->
             (super Stream
                    (/. Val (if (Condition Val) [Val] []))
                    append
                    []))
    filter

    (3-) (for [0 (+ 1) (= 10)] print)
    01234567890

    (4-) (filter [0 (+ 1) (= 100)]
                 (/. X (integer? (/ X 3))))
    [0 3 6 9 12 15 18 21 24 27 30 33 36 39 42 45 48 51 54 57 60... etc]


### The Shen Test Suite

Now passes. It is run at the end of the build:

    ./build   # or ./tests if the jar already exists.

    [... loads of output ...]
    passed ... 146
    failed ...0
    pass rate ...100.0%

    ok
    0

    run time: 9.882 secs


It's close to 2x faster than [`shen.clj`](https://github.com/hraberg/shen.clj).


The benchmarks can be run via:

    ./benchmarks


### What works?

* The K Lambda parser.
* KL special forms.
* Partial application.
* Implicit recur.
* Simple Java inter-op (based on Clojure's syntax).
* [Dominik's tests](https://github.com/hraberg/Shen.java/blob/master/test/shen/PrimitivesTest.java) from [Shen to Clojure](http://code.google.com/p/shen-to-clojure/).
* The REPL.
* Pre-compilation of the `.kl` to `.class` files.
* The Shen test suite passes.
* Different bootstrap methods for invoke, apply and symbols. Evolving.
* SwitchPoints for symbols - used when redefining functions.
* Cons as persistent collection vs. cons pairs.


### Road Map

This is bound to change as we go:

* Saner choice of target method. Currently this is done by a mix of instanceof guards and fallback to `ClassCastException`. It's really only used for built-ins.
* Proper arithmetic. Shen.java uses long and double, but currently there's probably a lot of boxing going on.
* Adherence to Shen types when compiling typed Shen to Java.
* Performance. My use of invokedynamic is pretty naive so far, so there's a lot of work to be done here.
* Revisit how call sites are built and cached, see above.
* Proper Java inter-op. Potentially using [Dynalink](https://github.com/szegedi/dynalink).
* Reader macros/extension for [`edn`](https://github.com/edn-format/edn) to support embedded Clojure-like maps/sets.
* Persistent collections for the above.
* JSR-223 script engine


## References

[The Book of Shen](http://www.shenlanguage.org/tbos.html) Mark Tarver, 2012

[LISP in Small Pieces](http://pagesperso-systeme.lip6.fr/Christian.Queinnec/WWW/LiSP.html) Christian Queinnec, 1996 "The aim of this book is to cover, in widest possible scope, the semantics and implementations of interpreters and compilers for applicative languages."

[Asm 4.0](http://asm.ow2.org/index.html) Eric Bruneton, 2007-12 -"A Java bytecode engineering library"

[JDK 8 with Lambda support](http://jdk8.java.net/lambda/)

[InvokeDynamic - You Ain't Seen Nothin Yet](http://www.slideshare.net/CharlesNutter/jax-2012-invoke-dynamic-keynote) Charles Nutter, 2012

[JSR292 Cookbook](http://code.google.com/p/jsr292-cookbook/) | [video](http://medianetwork.oracle.com/video/player/1113248965001) Rémi Forax, 2011

[Scheme in one class](https://blogs.oracle.com/jrose/entry/scheme_in_one_class) John Rose, 2010 - Parts of this looks pretty similar actually! Slightly more advanced/complex, has Java interop but no lambdas. Haven't been updated from the older java.dyn package. "semi-compiled" to MHs, no ASM used.

[Optimizing JavaScript and Dynamic Languages on the JVM](http://www.oracle.com/javaone/lad-en/program/schedule/sessions/con5390-enok-1885659.pdf) Marcus Lagergren and Staffan Friberg, 2012

[Nashorn](https://blogs.oracle.com/nashorn/entry/open_for_business) Jim Laskey et al, 2012 "ECMAScript 5.1 that runs on top of JVM."

[Dynalink](https://github.com/szegedi/dynalink) Attila Szegedi, 2010-12 = "Dynamic Linker Framework for Languages on the JVM"

[Invokedynamic them all](https://speakerdeck.com/forax/invokedynamic-them-all) Rémi Forax, 2012

[Runtime metaprogramming via java.lang.invoke.MethodHandle](http://lampwww.epfl.ch/~magarcia/ScalaCompilerCornerReloaded/2012Q2/RuntimeMP.pdf) Miguel Garcia, 2012 - The idea of building the AST from MethodHandles without using ASM did occur to me, and looks like it could be possible. Not sure you can actually create a fn definition though (see above). Did a spike, doesn't seem easy/worth the hassle, may revisit.

[Patterns and Performance of InvokeDynamic in JRuby](http://bit.ly/jjug-indy-jruby-en) Hiroshi Nakamura, 2012

[InvokeDynamic: Your API for HotSpot](http://www.slideshare.net/boundaryinc/invoke-dynamic-your-api-to-hotspot) Tony Arcieri, 2012

[Invokedynamic and JRuby](http://vimeo.com/27207224) Ola Bini, 2011 - Last time I met Ola he said that he considered to go back to C for his next language VM.

[Dynamate: A Framework for Method Dispatch using invokedynamic](http://www.ec-spride.tu-darmstadt.de/media/ec_spride/secure_software_engineering/theses_1/kamil_erhard_master_thesis.pdf) Kamil Erhard, 2012


## License

http://shenlanguage.org/license.html

Shen, Copyright © 2010-2012 Mark Tarver

Shen.java, Copyright © 2012-2013 Håkan Råberg

---
YourKit is kindly supporting open source projects with its full-featured Java Profiler.
YourKit, LLC is the creator of innovative and intelligent tools for profiling
Java and .NET applications. Take a look at YourKit's leading software products:
<a href="http://www.yourkit.com/java/profiler/index.jsp">YourKit Java Profiler</a> and
<a href="http://www.yourkit.com/.net/profiler/index.jsp">YourKit .NET Profiler</a>.
