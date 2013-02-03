package shen;

import org.junit.Test;

import java.util.LinkedList;

import static java.lang.System.out;
import static java.util.Arrays.asList;
import static shen.Shen.Primitives.*;
import static shen.Shen.eval;

// These are the main methods from the interpreter and compiler, no structure or niceness.
// Tests lots of random stuff, written while developing, most this should be covered in PrimitivesTest.
// Both run against the compiler, as the interpreter has been removed.
public class SmokeTest {
    @Test
    public void interpreter() throws Throwable {
        out.println(eval_kl(intern("x")));
        out.println(eval("(or false)"));
        out.println(eval("(or false false)"));
        out.println(eval("(or false true)"));
        out.println(eval("(or false false false)"));
        out.println(eval("((or false) true)"));
        out.println(eval("()"));
        out.println(eval("(cons 2 3)"));

        out.println(eval("(absvector? (absvector 10))"));
        out.println(eval("(absvector 10)"));
        out.println(eval("(absvector? ())"));
        out.println(eval("(+ 1 2)"));
        out.println(eval("((+ 6.5) 2.0)"));
        out.println(eval("(+ 1.0 2.0)"));
        out.println(eval("(* 5 2)"));
        out.println(eval("(* 5)"));
        out.println(eval("(let x 42 x)"));
        out.println(eval("(let x 42 (let y 2 (cons x y)))"));
        out.println(eval("((lambda x (lambda y (cons x y))) 2 3)"));
        out.println(eval("((lambda x (lambda y (cons x y))) 2)"));
        out.println(eval("((let x 3 (lambda y (cons x y))) 2)"));
        out.println(eval("(cond (true 1))"));
        out.println(eval("(cond (false 1) ((> 10 3) 3))"));
        out.println(eval("(cond (false 1) ((> 10 3) ()))"));

        out.println(eval("(defun fib (n) (if (<= n 1) n (+ (fib (- n 1)) (fib (- n 2)))))"));
        out.println(eval("(fib 10)"));

        out.println(eval("(defun factorial (cnt acc) (if (= 0 cnt) acc (factorial (- cnt 1) (* acc cnt)))"));
        out.println(eval("(factorial 10 1)"));
        out.println(eval("(factorial 12)"));
        out.println(eval("((factorial 19) 1)"));

        out.println(eval_kl(asList(intern("lambda"), intern("x"), intern("x"))));
        out.println(eval_kl(asList(intern("defun"), intern("my-fun"), asList(intern("x")), intern("x"))));
        out.println(str(eval_kl(asList(intern("my-fun"), 3L))));
        out.println(eval_kl(asList(intern("defun"), intern("my-fun2"), asList(intern("x"), intern("y")), asList(intern("cons"), intern("y"), asList(intern("cons"), intern("x"), new LinkedList())))));
        out.println(eval_kl(asList(intern("my-fun2"), 3L, 5L)));
        out.println(eval_kl(asList(intern("defun"), intern("my-fun3"), asList(), "Hello")));
        out.println(str(eval_kl(asList(intern("my-fun3")))));
    }

    @Test
    public void compiler() throws Throwable {
        out.println(eval("(trap-error my-symbol my-handler)"));
        out.println(eval("(trap-error (simple-error \"!\") (lambda x x))"));
        out.println(eval("(if true \"true\" \"false\")"));
        out.println(eval("(if false \"true\" \"false\")"));
        out.println(eval("(cond (false 1) (true 2))"));
        out.println(eval("(cond (false 1) ((or true false) 3))"));
        out.println(eval("(or false)"));
        out.println(eval("((or false) false)"));
        out.println(eval("(or false false)"));
        out.println(eval("(or false true false)"));
        out.println(eval("(and true true)"));
        out.println(eval("(and true true (or false false))"));
        out.println(eval("(and true false)"));
        out.println(eval("(and true)"));
        out.println(eval("(lambda x x)"));
        out.println(eval("((lambda x x) 2)"));
        out.println(eval("(let x \"str\" x)"));
        out.println(eval("(let x 10 x)"));
        out.println(eval("(let x 10 (let y 5 x))"));
        out.println(eval("((let x 42 (lambda y x)) 0)"));
        out.println(eval("((lambda x ((lambda y x) 42)) 0)"));
        out.println(eval("(get-time unix)"));
        out.println(eval("(value *language*)"));
        out.println(eval("(+ 1 1)"));
        out.println(eval("(+ 1.2 1.1)"));
        out.println(eval("(+ 1.2 1)"));
        out.println(eval("(+ 1 1.3)"));
        out.println(eval("(cons x y)"));
        out.println(eval("(cons x)"));
        out.println(eval("((cons x) z)"));
        out.println(eval("(cons x y)"));
        out.println(eval("(absvector? (absvector 10))"));
        out.println(eval("(trap-error (/ 1 0) (lambda x x))"));
        out.println(eval("(defun fun (x y) (+ x y))"));
        out.println(eval("(defun fun2 () (fun 1 2))"));
        out.println(eval("(fun2)"));
        out.println(eval("(defun fun (x y) (- x y))"));
        out.println(eval("(fun2)"));
        out.println(eval("(fun 1 2)"));
        out.println(eval("(set x y)"));
        out.println(eval("(value x)"));
        out.println(eval("(set x z)"));
        out.println(eval("(value x)"));
        out.println(eval("()"));
        out.println(eval("(cond (true ()) (false 2))"));
        out.println(eval("(if (<= 3 3) x y)"));
        out.println(eval("(eval-kl (cons + (cons 1 (cons 2 ()))))"));
    }
}
