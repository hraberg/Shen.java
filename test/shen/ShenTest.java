package shen;

import org.junit.After;
import org.junit.Test;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.invoke.MethodHandle;

import static java.util.Arrays.asList;
import static java.util.Collections.EMPTY_LIST;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.junit.Assert.assertThat;
import static shen.Shen.*;
import static shen.Shen.UncheckedException.uncheck;

public class ShenTest {
    @Test
    public void equals() {
        is(2, 神("2"));
        is(true, 神("true"));
        is("foo", 神("\"foo\""));
        is(intern("bar"), 神("bar"));
        is(false, 神("(= 2 3)"));
        is(false, 神("(= \"foo\" \"bar\")"));
        is(false, 神("(= true false)"));
        is(false, 神("(= foo bar)"));
    }

    @Test
    public void defun_lambda_and_let() {
        is(intern("f"), 神("(defun f (x) (lambda y (+ x y)))"));
        is(5, 神("((f 3) 2)"));
        is(10, 神("(let x 5 (* 2 x))"));
    }

    @Test
    public void errors() {
        is(1.0, 神("(/ 1 1)"));
        is(-1, 神("(trap-error (/ 1 0) (lambda E -1))"));
        is(1.0, 神("(trap-error (/ 1 1) (lambda E -1))"));
        is("testError", 神("(trap-error (set newError (simple-error \"testError\")) (lambda E (error-to-string E)))"));
    }

    @Test
    public void addition() {
        is(5, 神("(+ 2 3)"));
        is(-1, 神("(+ 2 -3)"));
    }

    @Test
    public void subtraction() {
        is(-1, 神("(- 2 3)"));
        is(5, 神("(- 2 -3)"));
    }

    @Test
    public void multiplication() {
        is(6, 神("(* 2 3)"));
        is(-6, 神("(* 2 -3)"));
    }

    @Test
    public void division() {
        is(2.5, 神("(/ 5 2)"));
        is(-1.0, 神("(/ 4 -4)"));
    }

    @Test
    public void less_than() {
        is(true, 神("(< 1 2)"));
        is(false, 神("(< 4 4)"));
        is(false, 神("(< 4 0)"));
    }

    @Test
    public void less_than_or_equal() {
        is(true, 神("(<= 1 2)"));
        is(true, 神("(<= 4 4)"));
        is(false, 神("(< 4 0)"));
    }

    @Test
    public void greater_than() {
        is(true, 神("(> 3 2)"));
        is(false, 神("(> 4 4)"));
        is(false, 神("(> 2 4)"));
    }

    @Test
    public void greater_than_or_equal() {
        is(true, 神("(>= 3 2)"));
        is(true, 神("(>= 4 4)"));
        is(false, 神("(>= 2 4)"));
    }

    @Test
    public void _if() {
        is(true, 神("(if true true false)"));
        is(true, 神("(if false false true)"));
        is(-1, 神("(trap-error (if 5 true true) (lambda _ -1))"));
    }

    @Test
    public void number_p() {
        is(true, 神("(number? 3)"));
        is(true, 神("(number? 3.4)"));
        is(false, 神("(number? \"fuu\")"));
        is(false, 神("(number? bar)"));
    }

    @Test
    public void and() {
        is(true, 神("(and true true true)"));
        is(false, 神("(and true false true)"));
        is(-1, 神("(trap-error (and 2 true) (lambda E -1))"));
    }

    @Test
    public void or() {
        is(true, 神("(or true true true)"));
        is(true, 神("(or true false true)"));
        is(true, 神("(or (and false false false) true)"));
        is(-1, 神("(trap-error (or 2 true) (lambda E -1))"));
    }

    @Test
    public void value() {
        is(5, 神("(set x 5)"));
        is(5, 神("(value x)"));
        is("foo", 神("(set x \"foo\")"));
        is("foo", 神("(value x)"));
        is(-1, 神("(trap-error (value valueTest) (lambda E -1))"));
    }

    @Test
    public void string_p_str_tlstr_cn_and_pos() {
        is(true, 神("(string? \"bar\")"));
        is(true, 神("(string? (str \"foo\"))"));
        is(true, 神("(string? (str 2))"));
        is(true, 神("(string? (str true))"));
        is(true, 神("(string? (str bar))"));
        is("bar", 神("(str bar)"));
        is(false, 神("(string? 55)"));
        is("oobar", 神("(tlstr \"foobar\")"));
        is(-1, 神("(trap-error (cn bla blub) (lambda _ -1))"));
        is("foobar", 神("(cn \"foo\" \"bar\")"));
        is("r", 神("(pos \"bar\" 2)"));
    }

    @Test
    public void cons_p() {
        is(true, 神("(cons? '(2 1 1 \"foo\"))"));
        is(true, 神("(cons? (cons 3 '(2 1 1 \"foo\")))"));
        is(false, 神("(cons? 53)"));
        is(2, 神("(hd '(2 1 1))"));
        is(1, 神("(hd (tl '(2 1 1)))"));
    }

    @Test
    public void absvector_absvector_p_address_gt_and_lt_address() {
        Symbol fail = intern("fail!");
        Object[] absvector = {fail, fail, fail, fail, fail};
        is(absvector, 神("(set v (absvector 5))"));
        is(false, 神("(absvector? v)"));
        is(false, 神("(absvector? 2)"));
        is(false, 神("(absvector? \"foo\")"));
        absvector[2] = 5;
        is(absvector, 神("(address-> (value v) 2 5)"));
        is(5, 神("(<-address (value v) 2)"));
        is(-1, 神("(trap-error (<-address (value v) 5) (lambda E -1)) -1)"));
    }

    @Test
    public void eval_kl_freeze_and_thaw() {
        is(9, 神("(eval-kl (+ 4 5))"));
        is(4, 神("(eval-kl 4)"));
        is(intern("hello"), 神("(eval-kl hello)"));
        is(intern("hello"), 神("(eval-kl hello)"));
        is(MethodHandle.class, 神("(freeze (+ 2 2))"));
        is(4, 神("((freeze (+ 2 2)))"));
    }

    @Test
    public void set_value_and_intern() {
        is(5, 神("(set x 5)"));
        is(5, 神("(value x)"));
        is(5, 神("(value (intern \"x\"))"));
    }

    @Test
    public void get_time() {
        is(Number.class, 神("(get-time run)"));
    }

    @Test
    public void streams() {
        is(String.class, 神("(set fileName (cn (str (get-time run)) \".txt\"))"));
        is(FileOutputStream.class, 神("(set writeFile (open file (value fileName) out))"));
        is("foobar", 神("(pr \"foobar\" (value writeFile))"));
        is(null, 神("(close (value writeFile))"));
        is(FileInputStream.class, 神("(set readFile (open file (value fileName) in))"));
        is(102, 神("(read-byte (value readFile))"));
        is(111, 神("(read-byte (value readFile))"));
        is(false, 神("(= 102 (read-byte (value readFile)))"));
        is(null, 神("(close (value readFile))"));
    }

    @After
    public void delete_file() {
        try {
            File file = new File((String) 神("(value *home-directory*)"), (String) 神("(value fileName)"));
            if (file.exists())
                is(true, file.delete());
        } catch (NullPointerException ignore) {
        }
    }

    @Test
    public void n_gt_string() {
        is("d", 神("(n->string 100)"));
        is("h", 神("(n->string 104)"));
        is("(", 神("(n->string 40)"));
        is(false, 神("(= \"d\" (n->string 101)"));
        is(-1, 神("(trap-error (n->string -10) (lambda E -1))"));
    }

    @Test
    public void string_gt_n() {
        is(100, 神("(string->n \"d\")"));
        is(104, 神("(string->n \"h\")"));
        is(40, 神("(string->n \"(\")"));
        is(false, 神("(= 101 (string->n \"d\")"));
        is(-1, 神("(trap-error (string-> \"\") (lambda E -1))"));
    }

    @Test
    public void special_tests() {
        is(EMPTY_LIST, 神("()"));
        is(-1, 神("(trap-error ((4 3 2)) (lambda E -1)))"));
        is(-1, 神("(trap-error (+4 2) (lambda E -1)))"));
        is(-1, 神("(trap-error (+ 4 \"2\") (lambda E -1)))"));
        is(-1, 神("(trap-error (+ 4 specialTest) (lambda E -1)))"));
        is(-1, 神("(trap-error (+ 4 true) (lambda E -1)))"));
    }

    @Test
    public void lists() {
        is(-1, 神("(trap-error (hd 5) (lambda E -1)) -1)"));
        is(-1, 神("(trap-error (tl 5) (lambda E -1)) -1)"));
        is(1, 神("(hd '(1 2 3))"));
        is(asList(2, 3), 神("(tl '(1 2 3))"));
        is(new Cons(5, 10), 神("(cons 5 10)"));
//        is(asList(5, 10), 神("(cons 5 10)"));
    }

    @Test
    public void strings() {
        is("hello", 神("(str \"hello\")"));
        is("5", 神("(str 5)"));
        is(true, 神("(string? (str 5))"));
        is(true, 神("(string? (str hello))"));
        is(true, 神("(string? (str \"hello\"))"));
        is("helloWorld", 神("(cn \"hello\" \"World\")"));
        is("hello", 神("(cn \"hello\" \"\")"));
        is("hello", 神("(cn \"\" \"hello\")"));
    }

    @Test
    public void number() {
        is(1000.0, 神("10e2"));
        is(true, 神("(= 1.0 1)"));
//        is(true, 神("(--3 3)"));
//        is(true, 神("(---5 5.0)"));
    }

    private void is(Object expected, Object actual) {
        if (expected instanceof Class)
            assertThat(actual, instanceOf((Class<?>) expected));
        else
            assertThat(actual, equalTo(expected));
    }

    private Object 神(String shen) {
        try {
            return readEval(shen);
        } catch (Exception e) {
            throw uncheck(e);
        }
    }
/*
            "testing Streams"
            (set fileName (cn (str (get-time run)) ".txt"))
            (set writeFile (open file (value fileName) out))
            (pr "foobar" (value writeFile))
            (close (value writeFile))
            (set readFile (open file (value fileName) in))
            (test-is (= 102 (read-byte (value readFile))))
            (test-is (= 111 (read-byte (value readFile))))
            (test-is (= (= 102 (read-byte (value readFile))) false))
            (close (value readFile))

            // This test requires Shen's reader
            (test-is (= (str "hello") "c#34;helloc#34;"))

            // The tests below require Shen
            (map (/. X (test-is (= (pos "hello" X) (pos (tlstr "hello") (- X 1))))) [1 2 3 4])

            "Tuples"
            (test-is (tuple? (@p hello ())))
            (test-is (= (fst (@p hello ())) hello))
            (test-is (= (snd (@p hello ())) ()))

            "Symbols"
            (test-is (symbol? x))
            (test-is (= x (intern "x")))
            (test-is (= (intern (cn "x" "y")) (concat x y)))

            "Vectors"
            (set x (vector 100))
            (test-is (= (<-address (value x) 0) 100))
            (test-is (= (<-address (value x) 1) (fail)))
            (test-is (= (<-address (value x) 99) (fail)))
            (test-is (= (trap-error (<-address (value x) 101) (/. E -1)) -1))
            (address-> (value x) 10 100)
            (test-is (= (<-address (value x) 10) 100))

            // Tested elsewhere above
            "Absvectors"
            (set x (absvector 100))
            (test-is (= (<-address (value x) 1) fail!))
            (test-is (= (<-address (value x) 99) fail!))
            (test-is (= (trap-error (<-address (value x) 100) (/. E -1)) -1))
            (address-> (value x) 10 100)
            (test-is (= (<-address (value x) 10) 100))

            "Exceptions"
            (test-is (= (trap-error (/ 1 0) (/. E -1)) -1))
            (test-is (= (trap-error (/ 1 0) (/. E (error-to-string E))) "division by zero"))

            "Numbers"
            (test-is (= 10e2 1000))
            (test-is (= 1 1.0))
            (test-is (= --3 3))
            (test-is (= ---5 -5.0))


            (intoutput "~%passed ... ~A~%failed ... ~A~%Passrate ... ~A %~%" (@p (value *passed*) (@p (value *failed*) (@p (/ (* 100 (value *passed*)) (+ (value *failed*) (value *passed*))) ()))))
*/
}
