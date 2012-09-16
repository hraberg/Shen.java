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

Is a work in progress - some primtives are missing, so it doesn't work yet.
The official Shen JVM port is done by Joel Shellman.

It's based on [`shen.clj`](https://github.com/hraberg/shen.clj), but has no dependency on Clojure.
Currently an interpreter using [MethodHandles](http://docs.oracle.com/javase/7/docs/api/java/lang/invoke/MethodHandle.html) as a primitive.
This is experimental, and this entire project acts as a playground for various JDK 8 stuff.

The idea is to compile this down to bytecode eventually, [Asm 4.0](http://asm.ow2.org/index.html) is checked in.

No build exists yet, but there's an IntelliJ project, which requires [Leda EAP](http://confluence.jetbrains.net/display/IDEADEV/IDEA+12+EAP) and [JDK 8 with Lambda support](http://jdk8.java.net/lambda/).
