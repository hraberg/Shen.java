package shen;

import java.io.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.WrongMethodTypeException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.*;
import java.util.functions.*;

import static java.lang.String.*;
import static java.lang.System.err;
import static java.lang.System.out;
import static java.lang.invoke.MethodHandles.insertArguments;
import static java.lang.invoke.MethodHandles.lookup;
import static java.lang.invoke.MethodType.methodType;
import static java.lang.reflect.Modifier.isPublic;
import static java.util.Arrays.*;
import static java.util.Collections.EMPTY_LIST;
import static java.util.Collections.nCopies;
import static java.util.Iterables.into;
import static java.util.functions.Mappers.constant;
import static shen.Shen.UncheckedException.uncheck;

@SuppressWarnings("UnusedDeclaration")
public class Shen {
    private static final MethodHandles.Lookup lookup = lookup();
    private static final Map<String, Symbol> symbols = new HashMap<>();

    private static Set specialForms = asList("let", "lambda", "cond", "quote",
            "if", "and", "or", "defun", "trap-error").map(s -> intern(s)).into(new HashSet());
    private static List<Class<? extends Serializable>> literals = asList(Number.class, String.class, Boolean.class);

    static {
        set("*language*", "Java");
        set("*implementation*", format("[jvm %s]", System.getProperty("java.version")));
        set("*porters*", "Håkan Råberg");
        set("*stinput*", System.in);
        set("*stoutput*", out);
        set("*home-directory*", System.getProperty("user.dir"));

        iterable(Shen.class.getDeclaredMethods())
                .filter(m -> isPublic(m.getModifiers()))
                .forEach(m -> { defun(intern(unscramble(m.getName())), m); });

        op("=", (BinaryOperator<Object>) (left, right) -> Objects.deepEquals(left, right));
        op("+", (IntBinaryOperator) (left, right) -> left + right);
        op("-", (IntBinaryOperator) (left, right) -> left - right);
        op("*", (IntBinaryOperator) (left, right) -> left * right);
        op("/", (IntBinaryOperator) (left, right) -> left / right);
        op("+", (LongBinaryOperator) (left, right) -> left + right);
        op("-", (LongBinaryOperator) (left, right) -> left - right);
        op("*", (LongBinaryOperator) (left, right) -> left * right);
        op("/", (LongBinaryOperator) (left, right) -> left / right);
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

    private static Object op(String name, Object op) {
        Symbol symbol = intern(name);
        symbol.fn.add(findSAM(op));
        return symbol;
    }

    private static MethodHandle findSAM(Object lambda) {
        try {
            return lookup.unreflect(asList(lambda.getClass().getDeclaredMethods())
                    .filter(m -> !m.isSynthetic()).getFirst()).bindTo(lambda);
        } catch (IllegalAccessException e) {
            throw uncheck(e);
        }
    }

    public static class Cons {
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

        @Override
        public String toString() {
            return "[" + car + " | " + cdr + "]";
        }
    }

    public static Cons cons(Object x, Object y) {
        return new Cons(x, y);
    }

    public static List<Object> cons(Object x, List<Object> y) {
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

    public static String str(Object x) {
        if (x != null && x.getClass().isArray()) return str((Object[]) x);
        return String.valueOf(x);
    }

    public static String str(Object[] x) {
        return deepToString(x);
    }

    public static String str(List x) {
        throw new IllegalArgumentException();
    }

    public static String pos(String x, int n) {
        return str(x.charAt(n));
    }

    public static String tlstr(String x) {
        return x.substring(1);
    }

    public static Mapper<Object, Object> freeze(Object x) {
        return constant(x);
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

    public static String n_gt_string(int n) {
        return "" + n;
    }

    public static String byte_gt_string(byte n) {
        return "" + n;
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
        return null;
    }

    public static Closeable open(Symbol type, String string, Symbol direction) throws IOException {
        if (!"file".equals(type.symbol)) throw new IllegalArgumentException();

        File file = new File(valueOf("*home-directory*"), string);
        switch(direction.symbol) {
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
        switch(time.symbol ) {
            case "run": return System.nanoTime();
            case "unix": return System.currentTimeMillis() / 1000;
        }
        throw new IllegalArgumentException();
    }

    public static String cn(String s1, String s2) {
        return str(s1) + str(s2);
    }

    public static class Symbol {
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
            ListIterator<Map<Symbol, Object>> i = locals.listIterator(locals.size());
            while (i.hasPrevious()) {
                Map<Symbol, Object> map = i.previous();
                if (map.containsKey(this))
                    return map.get(this);
            }
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

    private static Object set(String x, Object y) {
        return set(intern(x), y);
    }

    public static Object value(Symbol x) {
        return x.var;
    }

    public static Object quote(Object x) {
        return x;
    }

    public static Object value(String x) {
        return value(intern(x));
    }

    public static MethodHandle function(Symbol x) {
        return x.fn.getFirst();
    }

    private static MethodHandle function(String x) {
        return function(intern(x));
    }

    private static final Stack<Map<Symbol,Object>> locals = new Stack<>();

    public static class Recur {
        Object[] args;

        public Recur(Object[] args) {
            this.args = args;
        }
    }

    public static Object eval_kl(Object kl) {
        return eval_kl(kl, true);
    }

    private static Object eval_kl(Object kl, boolean tail) {
        System.err.println(kl);
        try {
            if (literals.anyMatch((c -> c.isInstance(kl)))) return kl;
            if (EMPTY_LIST.equals(kl)) return kl;
            if (kl instanceof Symbol) return ((Symbol) kl).resolve();
            if (kl instanceof List) {
                @SuppressWarnings("unchecked")
                List<Object> list = (List) kl;

                Object hd = hd(list);
                MethodHandle fn =  (hd instanceof Symbol) ? ((Symbol) hd).fn.getFirst() : (MethodHandle) eval_kl(hd, tail);

                List<Object> args = tl(list);
                //noinspection Convert2Diamond
                args = specialForms.contains(hd) ? args : into(args.map(k -> eval_kl(k, false)), new ArrayList<Object>());

                if (intern("this").resolve().equals(fn)) {
                    if (tail) {
                        System.out.println("RECUR in " + hd + " " + args);
                        return new Recur(args.toArray());
                    }
                    System.err.println("Can only recur from tail position " + hd);
                }

                if (fn.type().parameterCount() == 1 && !fn.isVarargsCollector()) {
                    Object result = fn;
                    for (Object arg : args)
                        result = ((MethodHandle) result).invoke(arg);
                    return result;
                }
                @SuppressWarnings("SuspiciousToArrayCall")
                MethodType targetType = methodType(Object.class, args.map(o -> o.getClass())
                        .into(new ArrayList<>())
                        .toArray(new Class[args.size()]));

                if (!(hd instanceof Symbol)) return invokeVarArgs(fn, targetType, args.toArray());

                Symbol symbol = (Symbol) hd;
                for (MethodHandle h : symbol.fn)
                    try {
                        return invokeVarArgs(h, targetType, args.toArray());
                    } catch (WrongMethodTypeException | IncompatibleClassChangeError ignore) {
                        err.println(ignore);
                        err.println(h + " " + hd + " " + args);
                        err.println(symbol.fn);
                    } catch (Throwable t) {
                        err.println(hd + " " + h  + " " + args);
                        err.println(symbol.fn);
                        throw uncheck(t);
                    }
            }
        } catch (Throwable t) {
            throw uncheck(t);
        }
        throw new IllegalArgumentException("Cannot eval " + kl + " (" + kl.getClass() + ")");
    }

    private static Object invokeVarArgs(MethodHandle fn, MethodType targetType, Object... args) throws Throwable {
        return insertArguments(fn.asType(targetType), 0, args).invokeExact();
    }

    private static Symbol defun(Symbol name, Method m) {
        try {
            name.fn.add(lookup.unreflect(m));
            return name;
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    public static Symbol defun(Symbol name, final List<Symbol> args, Object body) {
        name.fn.clear();
        name.fn.add(defun(args, body));
        return name;
    }

    public interface Defun {
        Object call(Object... args);
    }

    private static MethodHandle partials(final MethodHandle lambda, Object... knownArgs) {
        return findSAM((Defun) newArgs -> {
            try {
                List<Object> args = new ArrayList<>();
                args.addAll(asList(knownArgs));
                args.addAll(asList(newArgs));
                return lambda.invokeWithArguments(args);
            } catch (Throwable t) {
                throw uncheck(t);
            }
        }).asVarargsCollector(Object[].class);
    }

    private static MethodHandle defun(List<Symbol> args, Object body) {
        final MethodHandle[] lambda = new MethodHandle[1];
        Defun defun = xs -> {
            if (xs.length < args.size()) return partials(lambda[0], xs);
            locals.push(new HashMap<>());
            locals.peek().put(intern("this"), lambda[0]);
            try {
                while (xs != null) {
                    for (int i = 0; i < args.size(); i++)
                        locals.peek().put(args.get(i), xs[i]);
                    xs = null;
                    Object result = eval_kl(body);
                    if (result instanceof Recur)
                        xs = ((Recur) result).args;
                    else return result;
                }
                throw new IllegalStateException();
            } finally {
                locals.pop();
            }
        };
        return lambda[0] = findSAM(defun).asVarargsCollector(Object[].class);
    }

    private static String unscramble(String s) {
        return s.replaceAll("_", "-").replaceAll("-p$", "?")
                .replaceAll("-ex$", "!").replaceAll("-?gt-?", "->")
                .replaceAll("-?lt-?", "<-").replaceAll("^kl-", "");
    }

    public static void main(String[] args) throws Throwable {
/*
        asList("sys", "writer", "core", "prolog", "yacc", "declarations"
                , "load",
                "macros", "reader", "sequent", "toplevel", "track", "t-star", "types"


        )
            .forEach(f -> {
                load(format("shen/klambda/%s.kl", f));
            });
*/

        out.println(let(intern("x"), 2, eval_kl(intern("x"))));
        out.println(eval_kl(intern("x")));
        out.println(readEval("(cons 2 3)"));
        out.println(readEval("(cons? (cons 2 '(3)))"));
        out.println(readEval("(absvector? (absvector 10))"));
        out.println(readEval("(absvector? ())"));
        out.println(readEval("'(1 2 3)"));
        out.println(readEval("(+ 1 2)"));
        out.println(readEval("(+ 1 2)"));
        out.println(readEval("(+ 1.0 2.0)"));
        out.println(readEval("(* 5 2)"));
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
        out.println(str(eval_kl(asList(intern("my-fun2"), 3, 5))));
        out.println(eval_kl(asList(intern("defun"), intern("my-fun3"), asList(), "Hello")));
        out.println(str(eval_kl(asList(intern("my-fun3")))));
    }

    private static Object load(String file) {
        try {
            out.println("LOADING " + file);
            //noinspection unchecked,RedundantCast
            return read(new File(file)).reduce(null, (BinaryOperator) (left, right) -> eval_kl(right));
        } catch (Exception e) {
            throw uncheck(e);
        }
    }

    private static Object readEval(String shen) throws Exception {
        return eval_kl(read(shen).getFirst());
    }

    private static String pprint(Object x) {
        return pprint(x, 0);
    }

    private static String pprint(Object x, int level) {
        if (x instanceof List)
            //noinspection unchecked,RedundantCast
            return format("%s(%s)\n", join("", nCopies(level, " ")),
                join(" ", ((List) x).map((Mapper) o -> pprint(o, level + 1))))
                  .replaceAll("\n\\s*\\)", ")").replaceAll(" +\\(", " (");
        return str(x);
    }

    private static List read(String s) throws Exception {
        return parse(new StringReader(s));
    }

    private static List read(File f) throws Exception {
        try (FileReader reader = new FileReader(f)) {
            return parse(reader);
        }
    }

    private static List parse(Reader reader) throws Exception {
        return tokenizeAll(new Scanner(reader).useDelimiter("(\\s|\\)|\")"));
    }

    public static Object kl_if(Object test, Object then, Object _else) throws Exception {
        return isTrue(eval_kl(test)) ? eval_kl(then) : eval_kl(_else);
    }

    public static <T> Object kl_if(Object test, Object then) throws Exception {
        return kl_if(test, then, false);
    }

    public static <T> Object cond(Object... clauses) throws Exception {
        for (Object clause : clauses)
            if (isTrue(eval_kl(((List) clause).getFirst())))
                return eval_kl(((List) clause).get(1));
        throw new IllegalArgumentException();
    }

    public static <T> boolean or(Object... clauses) throws Exception {
        for (Object clause : clauses)
            if (isTrue(eval_kl(clause))) return true;
        return false;
    }

    public static <T> boolean and(Object... clauses) throws Exception {
        for (Object clause : clauses)
            if (!isTrue(eval_kl(clause))) return false;
        return true;
    }

    public static MethodHandle lambda(final Symbol x, final Object y) {
        Map<Symbol, Object> scope = new HashMap<>();
        if (!locals.isEmpty()) scope.putAll(locals.peek());
        UnaryOperator<Object> lambda = (X) -> {
            locals.push(new HashMap<Symbol, Object>(scope) {{
                put(x, X);
            }});
            try {
                return eval_kl(y);
            } catch (Exception e) {
                throw uncheck(e);
            } finally {
                 locals.pop();
            }
        };
        return findSAM(lambda);
    }

    public static Object let(Symbol x, Object y, Object z) {
        try {
            return lambda(x, z).invoke(eval_kl(y));
        } catch (Throwable t) {
            throw uncheck(t);
        }
    }

    private static boolean isTrue(Object test) {
        return test != null && test != Boolean.FALSE;
    }

    private static Object tokenize(Scanner sc) throws Exception {
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

    private static boolean find(Scanner sc, String pattern) {
        return sc.findWithinHorizon(pattern, 1) != null;
    }

    private static Object nextString(Scanner sc) throws IOException {
        String s = sc.findWithinHorizon("(?s).*?\"", 0);
        return s.substring(0, s.length() - 1);
    }

    private static List tokenizeAll(Scanner sc) throws Exception {
        LinkedList<Object> list = new LinkedList<>();
        Object x;
        while ((x = tokenize(sc)) != null) list.add(x);
        return list;
    }

    public static class UncheckedException extends RuntimeException {
        public static Set<String> filteredPackages = new HashSet<>();

        static {
            filteredPackages.add("sun.reflect");
            filteredPackages.add("org.junit");
            filteredPackages.add("java.lang.reflect");
        }

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
            List<StackTraceElement> trace = new ArrayList<>();
            for (StackTraceElement element : stackTrace)
                if (!isFilteredPackage(element))
                    trace.add(element);
            return trace.toArray(new StackTraceElement[trace.size()]);
        }

        static boolean isFilteredPackage(StackTraceElement element) {
            for (String prefix : filteredPackages)
                if (element.getClassName().startsWith(prefix))
                    return true;
            return false;
        }

        public String toString() {
            String s = wrapped.getClass().getName();
            String message = getLocalizedMessage();
            return (message != null) ? (s + ": " + message) : s;
        }
    }
}
