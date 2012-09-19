package shen;

import java.io.*;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.*;
import java.util.functions.*;

import static java.lang.String.format;
import static java.lang.System.*;
import static java.lang.invoke.MethodHandles.insertArguments;
import static java.lang.invoke.MethodHandles.lookup;
import static java.lang.invoke.MethodType.methodType;
import static java.lang.reflect.Modifier.isPublic;
import static java.util.Arrays.*;
import static java.util.Objects.deepEquals;
import static java.util.functions.Predicates.isEqual;

@SuppressWarnings({"UnusedDeclaration", "Convert2Diamond", "SuspiciousNameCombination"})
public class Shen {
    static final boolean debug = false;

    static MethodHandles.Lookup lookup = lookup();
    static Map<String, Symbol> symbols = new HashMap<>();
    static Stack<Map<Symbol, Object>> locals = new Stack<>();

    @Retention(RetentionPolicy.RUNTIME)
    public @interface Macro {}

    static Set<Symbol> macros = new HashSet<>();
    static List<Class<? extends Serializable>> literals =
            asList(Number.class, String.class, Boolean.class);

    static {
        set("*language*", "Java");
        set("*implementation*", format("[jvm %s]", System.getProperty("java.version")));
        set("*porters*", "Håkan Råberg");
        set("*stinput*", in);
        set("*stoutput*", out);
        set("*home-directory*", System.getProperty("user.dir"));

        iterable(Shen.class.getDeclaredMethods())
                .filter(m -> isPublic(m.getModifiers()))
                .forEach(m -> { defun(m); });

        op("=", (BiPredicate<Object, Object>)
                (left, right) -> left instanceof Number && right instanceof Number
                        ? ((Number) left).doubleValue() == ((Number) right).doubleValue()
                        : deepEquals(left, right));
        op("+", (IntBinaryOperator) (left, right) -> left + right);
        op("-", (IntBinaryOperator) (left, right) -> left - right);
        op("*", (IntBinaryOperator) (left, right) -> left * right);
        op("/", (BiMapper<Integer, Integer, Number>)
                (left, right) -> left % right == 0 ? left / right : left / (double) right);
        op("+", (LongBinaryOperator) (left, right) -> left + right);
        op("-", (LongBinaryOperator) (left, right) -> left - right);
        op("*", (LongBinaryOperator) (left, right) -> left * right);
        op("/", (BiMapper<Long, Long, Number>)
                (left, right) -> left % right == 0 ? left / right : left / (double) right);
        op("+", (DoubleBinaryOperator) (left, right) -> left + right);
        op("-", (DoubleBinaryOperator) (left, right) -> left - right);
        op("*", (DoubleBinaryOperator) (left, right) -> left * right);
        op("/", (DoubleBinaryOperator) (left, right) -> left / right);
        op("<", (BiPredicate<Integer, Integer>) (left, right) -> left < right);
        op("<=", (BiPredicate<Integer, Integer>) (left, right) -> left <= right);
        op(">", (BiPredicate<Integer, Integer>) (left, right) -> left > right);
        op(">=", (BiPredicate<Integer, Integer>) (left, right) -> left >= right);
        op("<", (BiPredicate<Long, Long>) (left, right) -> left < right);
        op("<=", (BiPredicate<Long, Long>) (left, right) -> left <= right);
        op(">", (BiPredicate<Long, Long>) (left, right) -> left > right);
        op(">=", (BiPredicate<Long, Long>) (left, right) -> left >= right);
        op("<", (BiPredicate<Double, Double>) (left, right) -> left < right);
        op("<=", (BiPredicate<Double, Double>) (left, right) -> left <= right);
        op(">", (BiPredicate<Double, Double>) (left, right) -> left > right);
        op(">=", (BiPredicate<Double, Double>) (left, right) -> left >= right);
    }

    static void op(String name, Object op) {
        intern(name).fn.add(findSAM(op));
    }

    static MethodHandle findSAM(Object lambda) {
        try {
            return lookup.unreflect(some(iterable(lambda.getClass().getDeclaredMethods()),
                                        m -> !m.isSynthetic())).bindTo(lambda);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    static class Cons {
        public final Object car, cdr;

        public Cons(Object car, Object cdr) {
            this.car = car;
            this.cdr = cdr;
        }

        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            Cons cons = (Cons) o;
            return !(car != null ? !car.equals(cons.car) : cons.car != null)
                    && !(cdr != null ? !cdr.equals(cons.cdr) : cons.cdr != null);
        }

        public int hashCode() {
            return 31 * (car != null ? car.hashCode() : 0) + (cdr != null ? cdr.hashCode() : 0);
        }

        public String toString() {
            return "[" + car + " | " + cdr + "]";
        }
    }

    public static Object cons(Object x, Object y) {
        if (y instanceof List) //noinspection unchecked
            return cons(x, (List) y);
        return new Cons(x, y);
    }

    static List<Object> cons(Object x, List<Object> y) {
        y.add(0, x);
        return y;
    }

    public static boolean cons_p(Object x) {
        return x instanceof Cons || x instanceof List && !((List) x).isEmpty();
    }

    public static Object fail_ex() {
        throw new AssertionError();
    }

    public static Object simple_error(String s) {
        throw new RuntimeException(s);
    }

    public static String error_to_string(Exception e) {
        return e.getMessage();
    }

    public static <T> T hd(List<T> list) {
        return list.isEmpty() ? null : list.get(0);
    }

    public static <T> List<T> tl(List<T> list) {
        return list.isEmpty() ? list : list.subList(1, list.size()).into(new ArrayList<T>());
    }

    public static Object hd(Cons cons) {
        return cons.car;
    }

    public static Object tl(Cons cons) {
        return cons.cdr;
    }

    static <T> T hd(T[] array) {
        return array[0];
    }

    static <T> T[] tl(T[] array) {
        return copyOfRange(array, 1, array.length);
    }

    public static String str(Object x) {
        if (cons_p(x)) throw new IllegalArgumentException();
        if (x != null && x.getClass().isArray()) return deepToString((Object[]) x);
        return String.valueOf(x);
    }

    public static String pos(String x, int n) {
        return str(x.charAt(n));
    }

    public static String tlstr(String x) {
        return x.substring(1);
    }

    public static MethodHandle freeze(Object x) {
        return lambda(intern("_"), x);
    }

    public static Class type(Object x) {
        return x.getClass();
    }

    public static Object[] absvector(int n) {
        Object[] objects = new Object[n];
        fill(objects, intern("fail!"));
        return objects;
    }

    public static boolean absvector_p(Object x) {
        return x != null && x.getClass() == Object[].class;
    }

    public static Object lt_address(Object[] vector, int n) {
        return vector[n];
    }

    public static Object[] address_gt(Object[] vector, int n, Object value) {
        vector[n] = value;
        return vector;
    }

    public static boolean number_p(Object x) {
        return x instanceof Number;
    }

    public static boolean string_p(Object x) {
        return x instanceof String;
    }

    public static String n_gt_string(int n) {
        if (n < 0) throw new IllegalArgumentException();
        return Character.toString((char) n);
    }

    public static String byte_gt_string(byte n) {
        return n_gt_string(n);
    }

    public static int string_gt_n(String s) {
        return (int) s.charAt(0);
    }

    public static int read_byte(InputStream s) throws IOException {
        return s.read();
    }

    public static int read_byte(Reader s) throws IOException {
        return s.read();
    }

    public static Object pr(Object x, OutputStream s) throws IOException {
        return pr(x, new OutputStreamWriter(s));
    }

    public static Object pr(Object x, Writer s) throws IOException {
        s.write(str(x));
        s.flush();
        return x;
    }

    public static Closeable open(Symbol type, String string, Symbol direction) throws IOException {
        if (!"file".equals(type.symbol)) throw new IllegalArgumentException();

        File file = new File((String) value("*home-directory*"), string);
        switch (direction.symbol) {
            case "in": return new FileInputStream(file);
            case "out": return new FileOutputStream(file);
        }
        throw new IllegalArgumentException();
    }


    public static Object close(Closeable stream) throws IOException {
        stream.close();
        return null;
    }

    public static long get_time(Symbol time) {
        switch (time.symbol) {
            case "run": return System.nanoTime();
            case "unix": return System.currentTimeMillis() / 1000;
        }
        throw new IllegalArgumentException();
    }

    public static String cn(String s1, String s2) {
        return s1 + s2;
    }

    static class Symbol {
        public final String symbol;
        public Set<MethodHandle> fn = new HashSet<>();
        public Object var;

        public Symbol(String symbol) {
            this.symbol = symbol.intern();
        }

        public String toString() {
            return symbol;
        }

        public Object resolve() {
            if (!locals.isEmpty() && locals.peek().containsKey(this))
                return locals.peek().get(this);
            return this;
        }
    }

    public static Symbol intern(String string) {
        if (!symbols.containsKey(string)) symbols.put(string, new Symbol(string));
        return symbols.get(string);
    }

    public static Object set(Symbol x, Object y) {
        return x.var = y;
    }

    static Object set(String x, Object y) {
        return set(intern(x), y);
    }

    public static Object value(Symbol x) {
        x.var.getClass();
        return x.var;
    }

    static Object value(String x) {
        return value(intern(x));
    }

    public static MethodHandle function(Symbol x) {
        return x.fn.getFirst();
    }

    static MethodHandle function(String x) {
        return function(intern(x));
    }

    public static class Recur {
        Object[] args;

        public Recur(Object[] args) {
            this.args = args;
        }
    }

    public static Object eval_kl(Object kl) {
        return eval_kl(kl, true);
    }

    static Object eval_kl(Object kl, boolean tail) {
        if (debug) err.println(kl);
        if (literals.anyMatch((c -> c.isInstance(kl)))) return kl;
        if (kl instanceof Symbol) return ((Symbol) kl).resolve();
        if (kl instanceof List) {
            @SuppressWarnings("unchecked")
            List<Object> list = (List) kl;
            if (list.isEmpty()) return list;
            Object hd = eval_kl(hd(list), tail);
            //noinspection SuspiciousMethodCalls
            List<Object> args = macros.contains(hd)
                    ? tl(list)
                    : tl(list).map(k -> eval_kl(k, false)).into(new ArrayList<Object>());

            if (intern("this").resolve().equals(hd)) if (tail) {
                if (debug) err.println("Recur: " + hd + " " + args);
                return new Recur(args.toArray());
            } else if (debug) err.println("Can only recur from tail position: " + hd);

            try {
                if (isLambda(hd)) return uncurry(hd, args);

                @SuppressWarnings("SuspiciousToArrayCall")
                final MethodType targetType = methodType(Object.class, args.map(o -> o.getClass())
                            .into(new ArrayList<>())
                            .toArray(new Class[args.size()]));

                if (hd instanceof MethodHandle) return apply((MethodHandle) hd, targetType, args);

                Symbol symbol = (Symbol) hd;

                MethodHandle exact = some(symbol.fn, isEqual(targetType));
                if (exact != null) return apply(exact, targetType, args);

                MethodHandle match = some(symbol.fn, f -> hasMatchingSignature(f, targetType, (x, y) -> x.isAssignableFrom(y)));
                if (match != null) return apply(match, targetType, args);

                err.println(hd + " " + targetType + " " + args);
                err.println("Did not find matching fn in: " + symbol.fn);
            } catch (RuntimeException e) {
                throw e;
            } catch (Throwable t) {
                throw new IllegalArgumentException(kl.toString(), t);
            }
        }
        throw new IllegalArgumentException("Cannot eval: " + kl + " (" + kl.getClass() + ")");
    }

    static Object uncurry(Object chain, List<Object> args) throws Throwable {
        for (Object arg : args)
            chain = ((MethodHandle) chain).invoke(arg);
        return chain;
    }

    static boolean isLambda(Object hd) {
        if (!(hd instanceof MethodHandle)) return false;
        MethodHandle fn = (MethodHandle) hd;
        return fn.type().parameterCount() == 1 && !fn.isVarargsCollector();
    }

    static boolean hasMatchingSignature(MethodHandle h, MethodType args, BiPredicate<Class, Class> match) {
        int last = h.type().parameterCount() - 1;
        if (h.isVarargsCollector() && args.parameterCount() - last > 0)
            h = h.asCollector(h.type().parameterType(last), args.parameterCount() - last);
        if (args.parameterCount() > h.type().parameterCount()) return false;

        Class<?>[] classes = h.type().wrap().parameterArray();
        for (int i = 0; i < args.parameterCount(); i++)
            if (!match.eval(classes[i], args.parameterType(i))) return false;
        return true;
    }

    static Object apply(MethodHandle fn, MethodType targetType, List args) throws Throwable {
        int nonVarargs = fn.isVarargsCollector() ? fn.type().parameterCount() - 1 : fn.type().parameterCount();
        if (nonVarargs > targetType.parameterCount()) {
            MethodHandle partial = insertArguments(fn.asType(fn.type()
                    .dropParameterTypes(0, targetType.parameterCount())
                    .insertParameterTypes(0, targetType.parameterArray())), 0, args.toArray());
            return fn.isVarargsCollector() ? partial.asVarargsCollector(fn.type().parameterType(nonVarargs)) : partial;
        }
        return insertArguments(fn.asType(targetType), 0, args.toArray()).invokeExact();
    }


    @Macro
    public static Object trap_error(Object x, Object f) throws Throwable {
        try {
            return eval_kl(x);
        } catch (Exception e) {
            return ((MethodHandle) eval_kl(f)).invoke(e);
        }
    }

    @Macro
    public static Object quote(Object x) {
        return x;
    }

    @Macro
    public static Object kl_if(Object test, Object then, Object _else) throws Exception {
        return isTrue(eval_kl(test)) ? eval_kl(then) : eval_kl(_else);
    }

    @Macro
    public static Object cond(List... clauses) throws Exception {
        if (clauses.length == 0) simple_error("condition failure");
        return isTrue(eval_kl(hd(clauses).getFirst()))
                ? eval_kl(hd(clauses).get(1))
                : cond(tl(clauses));
    }

    @Macro
    public static boolean or(Object x, Object y, Object... clauses) throws Exception {
        return isTrue(eval_kl(x)) || (clauses.length > 0
                ? or(y, hd(clauses), tl(clauses))
                : isTrue(eval_kl(y)));
    }

    @Macro
    public static boolean and(Object x, Object y, Object... clauses) throws Exception {
        return isTrue(eval_kl(x)) && (clauses.length > 0
                ? and(y, hd(clauses), tl(clauses))
                : isTrue(eval_kl(y)));
    }

    @Macro
    public static MethodHandle lambda(Symbol x, Object y) {
        Map<Symbol, Object> scope = new HashMap<>();
        if (!locals.isEmpty()) scope.putAll(locals.peek());
        Mapper lambda = (arg) -> {
            locals.push(new HashMap<>(scope)).put(x, arg);
            try {
                return eval_kl(y);
            } finally {
                locals.pop();
            }
        };
        return findSAM(lambda);
    }

    @Macro
    public static Object let(Symbol x, Object y, Object z) throws Throwable {
        return lambda(x, z).invoke(eval_kl(y));
    }

    @Macro
    public static Symbol defun(Symbol name, final List<Symbol> args, Object body) {
        name.fn.clear();
        name.fn.add(fn(name, args, body));
        return name;
    }

    static <T> T some(Iterable<T> iterable, Predicate<? super T> predicate) {
        Iterable<T> filter = iterable.filter(predicate);
        return filter.isEmpty() ? null : filter.getAny();
    }

    @SafeVarargs
    static <T> List<T> list(T... elements) {
        return asList(elements).into(new ArrayList<T>());
    }

    static boolean isTrue(Object test) {
        return ((Boolean) test);
    }
    public interface Fn {
        Object call(Object... args) throws Throwable;

    }

    static MethodHandle fn(Symbol name, List<Symbol> args, Object body) {
        if (args.isEmpty()) return findSAM((Factory) () -> fnBody(name, args, body));
        if (args.size() == 1) return findSAM((Mapper) x -> fnBody(name, args, body, x));
        if (args.size() == 2) return findSAM((BiMapper) (x, y) -> fnBody(name, args, body, x, y));
        return findSAM((Fn) xs -> fnBody(name, args, body, xs)).asCollector(Object[].class, args.size());
    }

    static Symbol defun(Method m) {
        try {
            Symbol name = intern(unscramble(m.getName()));
            if (m.isAnnotationPresent(Macro.class)) macros.add(name);
            name.fn.add(lookup.unreflect(m));
            return name;
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    static Object fnBody(Symbol name, List<Symbol> args, Object body, Object... values) {
        locals.push(new HashMap<>()).put(intern("this"), name);
        try {
            while (true) {
                for (int i = 0; i < args.size(); i++)
                    locals.peek().put(args.get(i), values[i]);
                Object result = eval_kl(body);
                if (result instanceof Recur)
                    values = ((Recur) result).args;
                else return result;
            }
        } finally {
            locals.pop();
        }
    }

    static String unscramble(String s) {
        return s.replaceAll("_", "-").replaceAll("-p$", "?")
                .replaceAll("-ex$", "!").replaceAll("-?gt-?", "->")
                .replaceAll("-?lt-?", "<-").replaceAll("^kl-", "");
    }

    static Object load(String file) {
        try {
            out.println("LOADING " + file);
            //noinspection unchecked,RedundantCast
            return read(new File(file)).reduce(null, (BinaryOperator) (left, right) -> eval_kl(right));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static Object readEval(String shen) throws Exception {
        return eval_kl(read(shen).getFirst());
    }

    static List read(String s) throws Exception {
        return parse(new StringReader(s));
    }

    static List read(File f) throws Exception {
        try (FileReader reader = new FileReader(f)) {
            return parse(reader);
        }
    }

    static List parse(Reader reader) throws Exception {
        return tokenizeAll(new Scanner(reader).useDelimiter("(\\s|\\)|\")"));
    }

    static Object tokenize(Scanner sc) throws Exception {
        if (find(sc, "\\(")) return tokenizeAll(sc);
        if (find(sc, "\"")) return nextString(sc);
        if (find(sc, "\\s")) return tokenize(sc);
        if (find(sc, "'")) return asList(intern("quote"), tokenize(sc));
        if (find(sc, "\\)")) return null;
        if (sc.hasNextBoolean()) return sc.nextBoolean();
        if (sc.hasNextInt()) return sc.nextInt();
        if (sc.hasNextLong()) return sc.nextLong();
        if (sc.hasNextDouble()) return sc.nextDouble();
        if (sc.hasNext()) return intern(sc.next());
        return null;
    }

    static boolean find(Scanner sc, String pattern) {
        return sc.findWithinHorizon(pattern, 1) != null;
    }

    static Object nextString(Scanner sc) throws IOException {
        String s = sc.findWithinHorizon("(?s).*?\"", 0);
        return s.substring(0, s.length() - 1);
    }

    static List tokenizeAll(Scanner sc) throws Exception {
        List<Object> list = new LinkedList<>();
        Object x;
        while ((x = tokenize(sc)) != null) list.add(x);
        return list;
    }

    public static void main(String[] args) throws Throwable {
//        install();
        out.println(let(intern("x"), 2, eval_kl(intern("x"))));
        out.println(eval_kl(intern("x")));
        out.println(readEval("(or false)"));
        out.println(readEval("(or false false)"));
        out.println(readEval("(or false true)"));
        out.println(readEval("(or false false false)"));
        out.println(readEval("((or false) true)"));
        out.println(readEval("((and true) true true)"));
        out.println(readEval("()"));
        out.println(readEval("(cons 2 3)"));
        out.println(readEval("(cons? (cons 2 '(3)))"));
        out.println(readEval("(cons 2 '(3))"));
        out.println(readEval("(absvector? (absvector 10))"));
        out.println(readEval("(absvector 10)"));
        out.println(readEval("(absvector? ())"));
        out.println(readEval("'(1 2 3)"));
        out.println(readEval("(+ 1 2)"));
        out.println(readEval("((+ 6.5) 2.0)"));
        out.println(readEval("(+ 1.0 2.0)"));
        out.println(readEval("(* 5 2)"));
        out.println(readEval("(* 5)"));
        out.println(readEval("(tl '(1 2 3))"));
        out.println(readEval("(let x 42 x)"));
        out.println(readEval("(let x 42 (let y 2 (cons x y)))"));
        out.println(readEval("((lambda x (lambda y (cons x y))) 2 3)"));
        out.println(readEval("((lambda x (lambda y (cons x y))) 2)"));
        out.println(readEval("((let x 3 (lambda y (cons x y))) 2)"));
        out.println(readEval("(cond (true 1))"));
        out.println(readEval("(cond (false 1) ((> 10 3) 3))"));
        out.println(readEval("(cond (false 1) ((> 10 3) ()))"));

        out.println(readEval("(defun fib (n) (if (<= n 1) n (+ (fib (- n 1)) (fib (- n 2)))))"));
        out.println(readEval("(fib 10)"));

        out.println(readEval("(defun factorial (cnt acc) (if (= 0 cnt) acc (factorial (- cnt 1) (* acc cnt)))"));
        out.println(readEval("(factorial 10 1)"));
        out.println(readEval("(factorial 12)"));
        out.println(readEval("((factorial 12) 1)"));

        out.println(eval_kl(asList(intern("quote"), asList(1, 2, 3))));
        out.println(eval_kl(asList(intern("hd"), asList(intern("quote"), asList(1, 2, 3)))));
        out.println(eval_kl(asList(intern("let"), intern("x"), 2, asList(intern("tl"), asList(intern("quote"), asList(1, 2, intern("x")))))));
        out.println(eval_kl(asList(intern("lambda"), intern("x"), intern("x"))));
        out.println(eval_kl(asList(intern("defun"), intern("my-fun"), asList(intern("x")), intern("x"))));
        out.println(str(eval_kl(asList(intern("my-fun"), 3))));
        out.println(eval_kl(asList(intern("defun"), intern("my-fun2"), asList(intern("x"), intern("y")), asList(intern("cons"), intern("y"), asList(intern("cons"), intern("x"), new LinkedList())))));
        out.println(eval_kl(asList(intern("my-fun2"), 3, 5)));
        out.println(eval_kl(asList(intern("defun"), intern("my-fun3"), asList(), "Hello")));
        out.println(str(eval_kl(asList(intern("my-fun3")))));
    }

    static void install() {
        for (String file : asList("sys", "writer", "core", "prolog", "yacc", "declarations", "load",
                "macros", "reader", "sequent", "toplevel", "track", "t-star", "types"))
            load(format("shen/klambda/%s.kl", file));
    }
}
