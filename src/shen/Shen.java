package shen;

import java.io.*;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.WrongMethodTypeException;
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
import static java.util.Collections.EMPTY_LIST;
import static java.util.Iterables.into;
import static java.util.Objects.deepEquals;
import static shen.Shen.UncheckedException.uncheck;

@SuppressWarnings("UnusedDeclaration")
public class Shen {
    static final boolean debug = false;

    static MethodHandles.Lookup lookup = lookup();
    static Map<String, Symbol> symbols = new HashMap<>();
    static Stack<Map<Symbol, Object>> locals = new Stack<>();

    @Retention(RetentionPolicy.RUNTIME)
    public @interface Macro {}

    static Set<Symbol> macros = new HashSet<>();
    static List<Class<? extends Serializable>> literals =
            asList(Number.class, String.class, Boolean.class, Exception.class);

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
            return lookup.unreflect(asList(lambda.getClass().getDeclaredMethods())
                    .filter(m -> !m.isSynthetic()).getFirst()).bindTo(lambda);
        } catch (IllegalAccessException e) {
            throw uncheck(e);
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
        y = null == y ? new LinkedList<>() : y;
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

    @Macro
    public static Object trap_error(Object x, Object f) {
        try {
            return eval_kl(x);
        } catch (Exception e) {
            return eval_kl(asList(f, e));
        }
    }

    public static Object hd(List<Object> list) {
        return list.isEmpty() ? null : list.get(0);
    }

    public static List<Object> tl(List<Object> list) {
        return list.isEmpty() ? null : list.subList(1, list.size());
    }

    public static Object hd(Cons cons) {
        return cons.car;
    }

    public static Object tl(Cons cons) {
        return cons.cdr;
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
        return findSAM((Factory<Object>) () -> x);
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
        public List<MethodHandle> fn = new ArrayList<>();
        public Object var;

        public Symbol(String symbol) {
            this.symbol = symbol.intern();
        }

        public String toString() {
            return symbol;
        }

        public Object resolve() throws Exception {
            if (!locals.isEmpty() && locals.peek().containsKey(this))
                return locals.peek().get(this);
            return this;
        }

        public void demote(MethodHandle h) {
            fn.remove(h);
            fn.add(h);
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

    @Macro
    public static Object quote(Object x) {
        return x;
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

    @Macro
    public static Object eval_kl(Object kl) {
        return eval_kl(kl, true);
    }

    static Object eval_kl(Object kl, boolean tail) {
        if (debug) err.println(kl);
        try {
            if (literals.anyMatch((c -> c.isInstance(kl)))) return kl;
            if (EMPTY_LIST.equals(kl)) return kl;
            if (kl instanceof Symbol) return ((Symbol) kl).resolve();
            if (kl instanceof List) {
                @SuppressWarnings("unchecked")
                List<Object> list = (List) kl;

                Object hd = hd(list);
                MethodHandle fn = (hd instanceof Symbol) ? ((Symbol) hd).fn.getFirst() : (MethodHandle) eval_kl(hd);

                //noinspection Convert2Diamond,SuspiciousMethodCalls
                final List<Object> args = macros.contains(hd)
                        ? tl(list)
                        : into(tl(list).map(k -> eval_kl(k, false)), new ArrayList<Object>());

                if (intern("this").resolve().equals(fn)) {
                    if (tail) {
                        if (debug) out.println("Recur: " + hd + " " + args);
                        return new Recur(args.toArray());
                    }
                    if (debug) err.println("Can only recur from tail position: " + hd);
                }

                if (isLambda(hd, fn)) {
                    Object result = fn;
                    for (Object arg : args)
                        result = ((MethodHandle) result).invoke(arg);
                    return result;
                }

                @SuppressWarnings("SuspiciousToArrayCall")
                final MethodType targetType = methodType(Object.class, args.map(o -> o.getClass())
                            .into(new ArrayList<>())
                            .toArray(new Class[args.size()]));

                if (!(hd instanceof Symbol)) return apply(fn, targetType, args);

                Symbol symbol = (Symbol) hd;
                for (MethodHandle h : new ArrayList<>(symbol.fn))
                    try {
                        return apply(h, targetType, args);
                    } catch (WrongMethodTypeException | ClassCastException ignore) {
                        err.println(ignore);
                        err.println(hd + " " + h + " " + args);
                        err.println("Candidates: " + symbol.fn);
                        symbol.demote(h);
                    } catch (Throwable t) {
                        err.println(hd + " " + h + " " + args);
                        err.println(symbol.fn);
                        throw uncheck(t);
                    }
                err.println(hd + " " + targetType + " " + args);
                err.println("Did not find matching fn: " + symbol.fn);
            }
        } catch (Throwable t) {
            err.println("Exception: " + kl + " (" + kl.getClass() + ")");
            throw uncheck(t);
        }
        throw new IllegalArgumentException("Cannot eval: " + kl + " (" + kl.getClass() + ")");
    }

    static boolean isLambda(Object hd, MethodHandle fn) {
        return !(hd instanceof Symbol) && fn.type().parameterCount() == 1;
    }

    static Object apply(MethodHandle fn, MethodType targetType, List args) {
        try {
            return fn.type().parameterCount() > targetType.parameterCount()
                    ? insertArguments(fn.asType(fn.type()
                        .dropParameterTypes(0, targetType.parameterCount())
                        .insertParameterTypes(0, targetType.parameterArray())), 0, args.toArray())
                    : insertArguments(fn.asType(targetType), 0, args.toArray()).invokeExact();
        } catch (Throwable t) {
            throw uncheck(t);
        }
    }

    @Macro
    public static Object kl_if(Object test, Object then, Object _else) throws Exception {
        return isTrue(eval_kl(test)) ? eval_kl(then) : eval_kl(_else);
    }

    @Macro
    public static <T> Object kl_if(Object test, Object then) throws Exception {
        return kl_if(test, then, false);
    }

    @Macro
    public static <T> Object cond(Object... clauses) throws Exception {
        for (Object clause : clauses)
            if (isTrue(eval_kl(((List) clause).getFirst())))
                return eval_kl(((List) clause).get(1));
        throw new IllegalArgumentException();
    }

    @Macro
    public static <T> boolean or(Object... clauses) throws Exception {
        return iterable(clauses).anyMatch(c -> isTrue(eval_kl(c)));
    }

    @Macro
    public static <T> boolean and(Object... clauses) throws Exception {
        return iterable(clauses).allMatch( c -> isTrue(eval_kl(c)));
    }

    @Macro
    public static MethodHandle lambda(final Symbol x, final Object y) {
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

    static boolean isTrue(Object test) {
        return ((Boolean) test);
    }

    public interface Defun {
        Object call(Object... args);
    }

    @Macro
    public static Symbol defun(Symbol name, final List<Symbol> args, Object body) {
        name.fn.clear();
        name.fn.add(defun(args, body));
        return name;
    }

    static Symbol defun(Method m) {
        try {
            Symbol name = intern(unscramble(m.getName()));
            if (m.isAnnotationPresent(Macro.class)) macros.add(name);
            name.fn.add(lookup.unreflect(m));
            return name;
        } catch (IllegalAccessException e) {
            throw uncheck(e);
        }
    }

    static MethodHandle defun(List<Symbol> args, Object body) {
        MethodHandle[] self = new MethodHandle[1];
        if (args.isEmpty()) self[0] = findSAM((Factory) () -> fnBody(args, body, self));
        if (args.size() == 1) self[0] = findSAM((Mapper) x -> fnBody(args, body, self, x));
        if (args.size() == 2) self[0] = findSAM((BiMapper) (x, y) -> fnBody(args, body, self, x, y));
        if (args.size() > 2) self[0] = findSAM((Defun) xs -> fnBody(args, body, self, xs)).asCollector(Object[].class, args.size());
        return self[0];
    }

    static Object fnBody(List<Symbol> args, Object body, MethodHandle[] self, Object... values) {
        locals.push(new HashMap<>()).put(intern("this"), self[0]);
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
            throw uncheck(e);
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

    public static class UncheckedException extends RuntimeException {
        public static Set<String> filteredPackages =
                new HashSet<>(asList("sun.reflect", "org.junit", "java.lang.reflect"));

        Throwable wrapped;

        public static RuntimeException uncheck(Throwable t) {
            if (t.getCause() != null)
                return uncheck(t.getCause());
            if (t instanceof RuntimeException) {
                t.setStackTrace(filterStackTrace(t.getStackTrace()));
                return (RuntimeException) t;
            }
            return new UncheckedException(t);
        }

        UncheckedException(Throwable t) {
            super(t.getMessage(), t.getCause());
            this.wrapped = t;
            setStackTrace(filterStackTrace(t.getStackTrace()));
        }

        static StackTraceElement[] filterStackTrace(StackTraceElement[] stackTrace) {
            //noinspection ToArrayCallWithZeroLengthArrayArgument,SuspiciousToArrayCall
            return iterable(stackTrace).filter(e -> isAllowedPackage(e))
                    .into(new ArrayList<>()).toArray(new StackTraceElement[0]);
        }

        static boolean isAllowedPackage(StackTraceElement element) {
            return filteredPackages.noneMatch(p -> element.getClassName().startsWith(p));
        }

        public String toString() {
            String s = wrapped.getClass().getName();
            String message = getLocalizedMessage();
            return (message != null) ? (s + ": " + message) : s;
        }
    }

    public static void main(String[] args) throws Throwable {
//        install();

        out.println(let(intern("x"), 2, eval_kl(intern("x"))));
        out.println(eval_kl(intern("x")));
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
        out.println(readEval("(cond (false 1) ((> 10 3) 3))"));

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
        asList("sys", "writer", "core", "prolog", "yacc", "declarations", "load",
                "macros", "reader", "sequent", "toplevel", "track", "t-star", "types")
                .forEach(f -> { load(format("shen/klambda/%s.kl", f)); });
    }
}
