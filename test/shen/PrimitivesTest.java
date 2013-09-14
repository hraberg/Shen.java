package shen;

import org.junit.After;
import org.junit.Ignore;
import org.junit.Test;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.invoke.MethodHandle;
import java.util.*;
import java.util.concurrent.FutureTask;

import static java.util.Arrays.asList;
import static java.util.Collections.EMPTY_LIST;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.junit.Assert.*;
import static shen.Shen.*;
import static shen.Shen.Numbers.*;
import static shen.Shen.Primitives.intern;
import static shen.Shen.RT.canCast;

public class PrimitivesTest {
    @Test
    public void equals() {
        is(2L, "2");
        is(true, "true");
        is("foo", "\"foo\"");
        is(intern("bar"), "bar");
        is(false, "(= 2 3)");
        is(false, "(= \"foo\" \"bar\")");
        is(false, "(= true false)");
        is(false, "(= foo bar)");
    }

    @Test
    public void defun_lambda_and_let() {
        is(intern("f"), "(defun f (x) (lambda y (+ x y))");
        is(5L, "((f 3) 2)");
        is(10L, "(let x 5 (* 2 x)");
    }

    @Test
    public void errors() {
        is(1.0, "(/ 1 1)");
        is(-1L, "(trap-error (/ 1 0) (lambda E -1))");
        is(1.0, "(trap-error (/ 1 1) (lambda E -1))");
        is(-1L, "(if (trap-error (/ 1 0) (lambda E true)) -1 false)");
        is(-1L, "(if (trap-error (= 1 1) (lambda E false)) -1 false)");
        is("testError", "(trap-error (set newError (simple-error \"testError\")) (lambda E (error-to-string E)))");
    }

    @Test
    public void addition() {
        is(5L, "(+ 2 3)");
        is(-1L, "(+ 2 -3)");
    }

    @Test
    public void subtraction() {
        is(-1L, "(- 2 3)");
        is(5L, "(- 2 -3)");
        is(1.5, "(- 2.0 0.5)");
    }

    @Test
    public void multiplication() {
        is(6L, "(* 2 3)");
        is(-6L, "(* 2 -3)");
    }

    @Test
    public void division() {
        is(2.5, "(/ 5 2)");
        is(-1.0, "(/ 4 -4)");
    }

    @Test
    public void less_than() {
        is(true, "(< 1 2)");
        is(false, "(< 4 4)");
        is(false, "(< 4 0)");
    }

    @Test
    public void less_than_or_equal() {
        is(true, "(<= 1 2)");
        is(true, "(<= 4 4)");
        is(false, "(< 4 0)");
    }

    @Test
    public void arithmetic() {
        is(2L, "(+ 1 1)");
        is(2.0, "(+ 1 1.0)");
        is(2.0, "(+ 1.0 1)");
        is(2.0, "(let x 1 (+ 1.0 x)");
        is(2L, "(let x 1 (+ 1 x)");
        is(2.0, "(let x 1.0 (+ 1 x)");
        is(2.0, "(let x 1.0 (+ x 1)");
        is(1.0, "(set x 1.0)");
        is(2.0, "(+ (value x) 1)");
        is(0.0, "(- (value x) 1)");
        is(3.0, "(* (value x) 3)");
        is(1.5, "(let x 2.0 (let y 0.5  (- x y)))");
        is(1.5, "(let x 2 (let y 0.5  (- x y)))");
        is(true, "(= (value x) 1)");
        is(intern("fun"), "(defun fun (x y) (- x y)))");
        is(1.5, "(fun 2 0.5)");
        is(1.5, "(fun 2.5 1)");
    }

    @Test
    public void greater_than() {
        is(true, "(> 3 2)");
        is(false, "(> 4 4)");
        is(false, "(> 2 4)");
    }

    @Test
    public void greater_than_or_equal() {
        is(true, "(>= 3 2)");
        is(true, "(>= 4 4)");
        is(false, "(>= 2 4)");
    }

    @Test
    public void _if() {
        is(true, "(if true true false)");
        is(true, "(if false false true)");
        is(-1L, "(trap-error (if 5.0 true true) (lambda _ -1)");
    }

    @Test
    public void number_p() {
        is(true, "(number? 3)");
        is(true, "(number? 3.4)");
        is(false, "(number? \"fuu\")");
        is(false, "(number? bar)");
    }

    @Test
    public void and() {
        is(true, "(and true true true)");
        is(false, "(and true false true)");
        is(-1L, "(trap-error (and 2 true) (lambda E -1)");
    }

    @Test
    public void or() {
        is(true, "(or true true true)");
        is(true, "(or true false true)");
        is(true, "(or (and false false false) true)");
        is(-1L, "(trap-error (or 2 true) (lambda E -1)");
    }

    @Test
    public void value() {
        is(5L, "(set x 5)");
        is(5L, "(value x)");
        is("foo", "(set x \"foo\")");
        is("foo", "(value x)");
        is(-1L, "(trap-error (value valueTest) (lambda E -1)");
    }

    @Test
    public void string_p_str_tlstr_cn_and_pos() {
        is(true, "(string? \"bar\")");
        is(true, "(string? (str \"foo\")");
        is(true, "(string? (str 2)");
        is(true, "(string? (str true)");
        is(true, "(string? (str bar)");
        is("bar", "(str bar)");
        is(false, "(string? 55)");
        is("oobar", "(tlstr \"foobar\")");
        is(-1L, "(trap-error (cn bla blub) (lambda _ -1)");
        is("foobar", "(cn \"foo\" \"bar\")");
        is("r", "(pos \"bar\" 2)");
    }

    @Test
    public void cons_p() {
        is(true, "(cons? (cons 2 (cons 1 (cons 1 (cons \"foo\" ())))))");
        is(true, "(cons? (cons 3 (cons 2 (cons 1 (cons 1 (cons \"foo\" ()))))))");
        is(false, "(cons? 53)");
        is(2L, "(hd (cons 2 (cons 1 (cons 1 ()))))");
        is(1L, "(hd (tl (cons 2 (cons 1 (cons 1 ())))))");
    }

    @SuppressWarnings({"unchecked", "RedundantCast", "EqualsBetweenInconvertibleTypes"})
    @Test
    public void cons() {
        // Nasty corner case of Cons pair vs List.
        assertFalse(new Cons(1, 2).equals(asList(1, 2)));
        assertFalse(asList(1, 2).equals(new Cons(1, 2)));

        assertEquals(new Cons(1, 2), new Cons(1, 2));
        assertFalse(new Cons(1, new Cons(2, EMPTY_LIST)).equals(new Cons(1, 2)));
        assertFalse(new Cons(1, 2).equals(new Cons(1, new Cons(2, EMPTY_LIST))));
        assertEquals(new Cons(1, new Cons(2, EMPTY_LIST)), new Cons(1, new Cons(2, EMPTY_LIST)));
        Cons cons = new Cons(1, EMPTY_LIST);
        assertEquals(1, cons.iterator().next());
        assertEquals(1, cons.size);
        cons = new Cons(2, cons);
        Iterator iterator = cons.iterator();
        assertEquals(2, iterator.next());
        assertEquals(1, iterator.next());
        assertEquals(2, cons.size);
        assertEquals(new Cons(2, new Cons(1, EMPTY_LIST)), cons);
        assertEquals(asList(2, 1), new ArrayList(cons));
        assertTrue(cons.contains(1));
        assertTrue(cons.contains(2));
        assertFalse(cons.contains(3));
        cons = new Cons(3, cons);
        assertEquals(Arrays.<Object>asList(3, 2, 1), cons.toList());
        try {
            new Cons(1, 2).toList();
            fail();
        } catch (IllegalStateException expected) {
        }
    }

    @Test
    public void absvector_absvector_p_address_gt_and_lt_address() {
        Symbol fail = intern("fail!");
        Object[] absvector = {fail, fail, fail, fail, fail};
        is(absvector, "(set v (absvector 5)");
        is(false, "(absvector? v)");
        is(false, "(absvector? 2)");
        is(false, "(absvector? \"foo\")");
        absvector[2] = integer(5L);
        is(absvector, "(address-> (value v) 2 5)");
        is(5L, "(<-address (value v) 2)");
        is(-1L, "(trap-error (<-address (value v) 5) (lambda E -1))");
    }

    @Test
    public void eval_kl_freeze_and_thaw() {
        is(9L, "(eval-kl (cons + (cons 4 (cons 5 ()))))");
        is(4L, "(eval-kl 4)");
        is(intern("hello"), "(eval-kl hello)");
        is(intern("hello"), "(eval-kl hello)");
        is(MethodHandle.class, "(freeze (+ 2 2)");
        is(MethodHandle.class, "(freeze (/ 2 0))");
        is(4L, "((freeze (+ 2 2)))");
        is(4L, "(thaw (freeze (+ 2 2)))");
    }

    @Test
    public void set_value_and_intern() {
        is(5L, "(set x 5)");
        is(5L, "(value x)");
        is(5L, "(value (intern \"x\")");
        is(intern("fun"), "(defun fun () (value x))");
        is(5L, "(fun)");
        is(6L, "(set x 6)");
        is(6L, "(fun)");
    }

    @Test
    public void tagged_values() {
        is(5L, "(set x 5)");
        is(5L, "(value x)");
        is(5.0, "(set x 5.0)");
        is(5.0, "(value x)");
        is(true, "(set x true)");
        is(true, "(value x)");
        is(false, "(set x false)");
        is(false, "(value x)");
        is(asList(), "(set x ())");
        is(asList(), "(value x)");
        is(intern("fun"), "(defun fun (x) (value x))");
        is(asList(), "(fun x)");
        is(5.0, "(set x 5.0)");
        is(5.0, "(fun x)");
        is(5L, "(set x 5)");
        is(5L, "(fun x)");
        is(6L, "(set y 6)");
        is(6L, "(fun y)");
        is(asList(), "(set y ())");
        is(asList(), "(fun y)");
    }

    @Test
    public void get_time() {
        is(Number.class, "(get-time run)");
    }

    @Test
    public void streams() {
        is(String.class, "(set fileName (cn (str (get-time run)) \".txt\"))");
        is(OutputStream.class, "(set writeFile (open (value fileName) out))");
        is(102L, "(write-byte 102 (value writeFile))");
        is(111L, "(write-byte 111 (value writeFile))");
        is(asList(), "(close (value writeFile))");
        is(InputStream.class, "(set readFile (open (value fileName) in))");
        is(102L, "(read-byte (value readFile))");
        is(111L, "(read-byte (value readFile))");
        is(false, "(= 102 (read-byte (value readFile)))");
        is(asList(), "(close (value readFile))");
    }

    @After
    public void delete_file() {
        try {
            File file = new File((String) 神("(value *home-directory*)"), (String) 神("(value fileName)"));
            if (file.exists())
                assertTrue(file.delete());
        } catch (Exception ignore) {
        }
    }

    @Test
    public void n_gt_string() {
        is("d", "(n->string 100)");
        is("h", "(n->string 104)");
        is("(", "(n->string 40)");
        is(false, "(= \"d\" (n->string 101)");
        is(-1L, "(trap-error (n->string -10) (lambda E -1)");
    }

    @Test
    public void string_gt_n() {
        is(100L, "(string->n \"d\")");
        is(104L, "(string->n \"h\")");
        is(40L, "(string->n \"(\")");
        is(false, "(= 101 (string->n \"d\")");
        is(-1L, "(trap-error (string-> \"\") (lambda E -1)");
    }

    @Test
    public void special_tests() {
        is(EMPTY_LIST, "()");
        is(-1L, "(trap-error ((4 3 2)) (lambda E -1))");
        is(-1L, "(trap-error (+4 2) (lambda E -1))");
        is(-1L, "(trap-error (+ 4 \"2\") (lambda E -1))");
        is(-1L, "(trap-error (+ 4 specialTest) (lambda E -1))");
        is(-1L, "(trap-error (+ 4 true) (lambda E -1))");
    }

    @Test
    public void lists() {
        is(-1L, "(trap-error (hd 5) (lambda E -1))");
        is(-1L, "(trap-error (tl 5) (lambda E -1))");
        is(1L, "(hd (cons 1 (cons 2 (cons 3 ()))))");
        is(asList(integer(2L), integer(3L)), "(tl (cons 1 (cons 2 (cons 3 ()))))");
        is(new Cons(integer(5L), integer(10L)), "(cons 5 10)");
    }

    @Test
    public void strings() {
        is("hello", "(str \"hello\")");
        is("5", "(str 5)");
        is(true, "(string? (str 5)");
        is(true, "(string? (str hello)");
        is(true, "(string? (str \"hello\")");
        is("helloWorld", "(cn \"hello\" \"World\")");
        is("hello", "(cn \"hello\" \"\")");
        is("hello", "(cn \"\" \"hello\")");
    }

    @Test
    public void number() {
        is(1000.0, "10e2");
        is(true, "(= 1.0 1)");
//        is(true, "(--3 3)");
//        is(true, "(---5 5.0)");
    }

    @Test
    public void parse_numbers() {
        is(Long.class, "1");
        is(Double.class, "1.1");
        is(Long.class, "10000000000");
    }

    @Test
    public void partials() {
        is(MethodHandle.class, "(cons 1)");
        is(MethodHandle.class, "(cons)");
        is(MethodHandle.class, "((cons) 1)");
        is(asList(integer(1L)), "((cons 1) ())");
        is(new Cons(integer(1L), integer(2L)), "((cons 1) 2)");
        is(new Cons(integer(1L), integer(2L)), "(((cons) 1) 2)");
        is(new Cons(integer(1L), integer(2L)), "((cons) 1 2)");
        is(true, "((> 50) 10)");
        is(true, "(let test or (test true false))");
        is(false, "(let test and (test true false))");
        is(MethodHandle.class, "(let test or (test true))");
        is(true, "(let test or ((test true) false))");
    }

    @Test
    public void uncurry() {
        is(asList(integer(1L), integer(2L)), "((lambda x (lambda y (cons x (cons y ())))) 1 2)");
        is(new Cons(integer(1L), integer(2L)), "(((cons) 1) 2)");
    }

    @Test
    public void function() {
        is(3L, "((function +) 1 2)");
        is(3.0, "((function +) 1 2.0)");
        is(intern("x"), "(defun x () y)");
        is(intern("y"), "(let z x (z)))");
        is(intern("x"), "(defun x (y) y)");
        is(2L, "(let a x (a 2)))");
        is(intern("x"), "(defun x (y) (y))");
        is(intern("y"), "(defun y () 1))");
        is(1L, "(x y)");
        is(MethodHandle.class, "(function undefined)");
        is(-1L, "(trap-error ((function undefined)) (lambda E -1))");
    }

    @Test
    public void rebind() {
        is(intern("fun"), "(defun fun (x) (cons 1 x)");
        is(asList(integer(1L)), "(fun ())");
        is(new Cons(integer(1L), integer(2L)), "(fun 2)");
        is(intern("fun"), "(defun fun (x) (cons 1 x)");
        is(new Cons(integer(1L), integer(2L)), "(fun 2)");
        is(asList(integer(1L)), "(fun ())");
        is(intern("fun2"), "(defun fun2 (x) (+ 2 x))");
        is(3L, "(fun2 1)");
        is(3.0, "(fun2 1.0)");
    }

    @Test
    public void recur() {
        is(intern("factorial"), "(defun factorial (cnt acc) (if (= 0 cnt) acc (factorial (- cnt 1) (* acc cnt)))");
        is(3628800L, "(factorial 10 1)");
    }

    @Test
    public void can_only_recur_from_tail_position() {
        is(intern("fib"), "(defun fib (n) (if (<= n 1) n (+ (fib (- n 1)) (fib (- n 2)))))");
        is(55L, "(fib 10)");
    }

    @Test
    public void kl_do() {
        is(2L, "(do 1 2)");
        is(3L, "(do 1 2 3)");
    }

    @Test
    public void java() {
        is(long.class, "(System/currentTimeMillis)");
        is("Oracle Corporation", "(System/getProperty \"java.vendor\")");
        is(1.414213562373095, "(Math/sqrt 2)"); // Should be 1.4142135623730951 <- last decimal is truncated
        is(Class.class, "(import java.util.Arrays)");
        is(asList(integer(1L), integer(2L)), "(Arrays/asList 1 2)");
        is(Class.class, "(import java.util.ArrayList)");
        is(Class.class, "(value ArrayList)");
        is(0L, "(.size ()))");
        is(ArrayList.class, "(ArrayList.)");
        is(asList(integer(1L)), "(ArrayList. (cons 1 ())");
        is(Long.class, "(.size (ArrayList. (cons 1 ()))");
//        is(asList(2L), "(tl (ArrayList. (cons 1 (cons 2 ())))");
        is("HELLO", "(.toUpperCase \"Hello\")");
        is(intern("up"), "(defun up (x) (.toUpperCase x))");
        is("UP", "(up \"up\")");
        is("TWICE", "(up \"twice\")");
    }

    @Test @Ignore("Broken by the new GWT tree")
    public void java_proxies() {
        is(Class.class, "(import java.util.concurrent.FutureTask)");
        is(FutureTask.class, "(set ft (FutureTask. (lambda x \"Call\")))");
        is(null, "(.run (value ft))");
        is("Call", "(.get (value ft))");
        is(FutureTask.class, "(set ft (FutureTask. (lambda x x)))");
        is(null, "(.run (value ft))");
        is(null, "(.get (value ft))");
    }

    @Test
    public void relink_java() {
        is(Class.class, "(import java.util.ArrayList)");
        is(Class.class, "(import java.util.LinkedList)");
        is(intern("to-string"), "(defun to-string (x) (.toString x))");
        is(String.class, "(to-string (ArrayList. (cons 1 ()))");
        is(String.class, "(to-string (LinkedList. (cons 1 ()))");
        is(intern("size"), "(defun size (x) (.size x))");
        is(1L, "(size (ArrayList. (cons 1 ()))");
        is(1L, "(size (LinkedList. (cons 1 ()))");
        is(0L, "(size ())");
        is(Class.class, "(import java.util.BitSet)");
        is((long) new BitSet().size(), "(size (BitSet.))");
    }

    @Test
    public void redefine() {
        is(intern("fun"), "(defun fun (x y) (+ x y))");
        is(intern("fun2"), "(defun fun2 () (fun 1 2))");
        is(3L, "(fun 1 2)");
        is(3L, "(fun2)");
        is(intern("fun"), "(defun fun (x y) (- x y))");
        is(-1L, "(fun 1 2)");
        is(-1L, "(fun2)");
        is(intern("fun"), "(defun fun (x y) (+ x y))");
        is(3L, "(fun 1 2)");
        is(3L, "(fun2)");
    }

    @Test
    public void casts() {
        assertTrue(canCast(Long.class, Object.class));
        assertTrue(canCast(Object.class, Long.class));
        assertTrue(canCast(Long.class, Double.class));
        assertFalse(canCast(Double.class, Long.class));
        assertTrue(canCast(long.class, double.class));
        assertFalse(canCast(double.class, long.class));
        assertTrue(canCast(long.class, Double.class));
        assertFalse(canCast(Double.class, Long.class));
        assertTrue(canCast(long.class, Object.class));
        assertTrue(canCast(Object.class, long.class));
        assertTrue(canCast(Long.class, long.class));
        assertTrue(canCast(long.class, Long.class));
        assertTrue(canCast(long.class, long.class));
        assertTrue(canCast(Object.class, Object.class));
        assertTrue(canCast(String.class, Object.class));
        assertTrue(canCast(Object.class, String.class));
        assertFalse(canCast(Long.class, List.class));
    }

    @Test @Ignore("Reported by Artella.")
    public void let_and_recur() throws Throwable {
        is(intern("fun"),
                "(defun fun (V503) (let Z (- V503 1) (if (= Z 1) (* 3 V503) (fun Z))))");
        is(6L, "(fun 10)");
        is(6L, "(fun 10)");

        is(intern("fun"),
                "(defun fun (V503) (let Z (- V503 1) (if (= Z 1) (* 2 V503) (fun Z))))");
        is(4L, "(fun 10)");
    }

    void is(Object expected, String actual) {
        Object 神 = 神(actual);
        if (expected instanceof Class)
            if (expected == Double.class) assertThat(isInteger((Long) 神), equalTo(false));
            else assertThat(神, instanceOf((Class<?>) expected));
        else if (神 instanceof Long)
            assertThat(asNumber((Long) 神), equalTo(expected));
        else if (神 instanceof Cons && expected instanceof List)
            assertThat(((Cons) 神).toList(), equalTo(expected));
        else
            assertThat(神, equalTo(expected));
    }

    Object 神(String shen) {
        try {
            return eval(shen);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }
}
