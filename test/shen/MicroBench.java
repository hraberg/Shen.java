package shen;

import java.io.StringReader;
import java.util.concurrent.Callable;

import static java.lang.System.currentTimeMillis;
import static shen.Shen.Compiler;
import static shen.Shen.KLReader.read;
import static shen.Shen.Primitives.set;
import static shen.Shen.eval;

public class MicroBench {
    public static void main(String[] args) throws Throwable {
        int times = 10;

        set("*debug*", true);
        eval("(defun fib (n) (if (<= n 1) n (+ (fib (- n 1)) (fib (- n 2)))))");

        bench("(fib 30)", times);
        bench("(fib 30.0)", times); // Will switch fib from long to double
        bench("(fib 30)", times);   // long widened to double
        bench("(fib 30.0)", times);

        bench(() -> fib(30), times); // Java
        bench(() -> fibBoxed(30L), times); // Java Boxed

        eval("(defun my-cons (a b) (cons a b))");
        bench("(my-cons 1 2)", times);  // Will pick cons(Object, Object)
        bench("(my-cons 1 ())", times); // Fails without hack/fix

        eval("(defun my-cons (a b) (cons a b))");
        bench("(my-cons 1 ())", times); // Picks cons(Object, List)
        bench("(my-cons 1 2)", times);  // Guarded down to cons(Object, Object)
        bench("(my-cons 1 ())", times); // Reuses same target matching guard

        bench("(= 1 1)", times);        // Not for performance, but these easily break
        bench("(= 1 1.0)", times);

        eval("(defun map (f x) (if (cons? x) (cons (f (hd x)) (map f (tl x))) ()))");
        bench("(map (+ 1) (cons 1 (cons 2 (cons 3 ()))))", times);

        eval("(defun inc (x) (+ 1 x))");
        bench("(map inc (cons 1 (cons 2 (cons 3 ()))))", times);

        times = 30;
        bench("(cons 1 1.0)", times);
        bench("((cons 1) 1.0)", times);

        eval("(defun my-cons (x) ((cons 1) x))");
        bench("(my-cons 1.0)", times);
    }

    static long fib(long n) {
        if (n <= 1) return n;
        return fib(n - 1) + fib(n - 2);
    }

    private static Long fibBoxed(Long n) {
        if (n <= 1) return n;
        return fibBoxed(n - 1) + fibBoxed(n - 2);
    }

    static void bench(String test, int times) throws Throwable {
        Object kl = read(new StringReader(test)).get(0);
        bench(new Compiler(kl).load("__eval__", Callable.class).newInstance(), times);
    }

    static void bench(Callable<?> code, int times) throws Exception {
        long start = currentTimeMillis();
        for (int i = 0; i < times; i++) System.out.println(code.call());
        System.out.println(times + ": "  + ((currentTimeMillis() - start) / ((double) times) + "ms."));
    }
}
