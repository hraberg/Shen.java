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
import static java.lang.invoke.MethodHandles.insertArguments;
import static java.lang.invoke.MethodHandles.lookup;
import static java.lang.invoke.MethodType.methodType;
import static java.lang.reflect.Modifier.isPublic;
import static java.util.Arrays.*;
import static java.util.Collections.EMPTY_LIST;
import static java.util.Collections.nCopies;
import static java.util.Iterables.into;
import static java.util.functions.Mappers.constant;

public class Shen {
    private static final MethodHandles.Lookup lookup = lookup();
    private static final Map<String, Symbol> symbols = new HashMap<>();

    private static Set<Symbol> specialForms = asList("let", "lambda", "cond", "quote",
            "if", "and", "or", "defun", "trap-error").map(Shen::intern).into(new HashSet());
    private static List<Class<? extends Serializable>> literals = asList(Number.class, String.class, Boolean.class);

    static {
        set("*language*", "Java");
        set("*implementation*", format("[jvm %s]", System.getProperty("java.version")));
        set("*porters*", "Håkan Råberg");
        set("*stinput*", System.in);
        set("*stoutput*", System.out);
        set("*home-directory*", System.getProperty("user.dir"));

        iterable(Shen.class.getDeclaredMethods())
                .filter(m -> isPublic(m.getModifiers()))
                .forEach(m -> { defunInternal(intern(unscramble(m.getName())), m); });

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
        try {
            Symbol symbol = intern(name);
            symbol.fn.add(findSAM(op).bindTo(op));
            return symbol;
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    private static MethodHandle findSAM(Object lambda) throws IllegalAccessException {
        return lookup.unreflect(asList(lambda.getClass().getDeclaredMethods())
                .filter(m -> !m.isSynthetic()).getFirst());
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
        return false;
    }

    public static boolean cons_p(List x) {
        return !x.isEmpty();
    }

    public static boolean cons_p(Cons x) {
        return true;
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
        return false;
    }

    public static boolean absvector_p(Object[] x) {
        return true;
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
        if (type.symbol != "file") throw new IllegalArgumentException();

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
            for (HashMap map : locals)
                if (map.containsKey(this))
                    return map.get(this);
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

    public static Object set(String x, Object y) {
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

    public static MethodHandle function(String x) {
        return function(intern(x));
    }

    private static final Stack<HashMap<Symbol,Object>> locals = new Stack<>();

    public static Object eval_kl(Object kl) {
        try {
            if (literals.anyMatch((c -> c.isInstance(kl)))) return kl;
            if (EMPTY_LIST.equals(kl)) return kl;
            if (kl instanceof Symbol) return ((Symbol) kl).resolve();
            if (kl instanceof List) {
                List<Object> list = (List) kl;

                Object hd = hd(list);
                MethodHandle fn =  (hd instanceof Symbol) ? ((Symbol) hd).fn.getFirst() : (MethodHandle) eval_kl(hd);

                List<Object> args = tl(list);
                args = specialForms.contains(hd) ? args : into(args.map(Shen::eval_kl), new ArrayList<Object>());

                if (fn.type().parameterCount() == 0) return (MethodHandle) fn.invoke();


                if (fn.type().parameterCount() == 1 && fn.type().parameterArray()[0] != Object[].class) {
                    Object result = fn;
                    for (Object arg : args)
                        result = ((MethodHandle) result).invoke(arg);
                    return result;
                }
                MethodType targetType = methodType(Object.class, args.map(Object::getClass)
                                                                        .into(new ArrayList<>())
                                                                        .toArray(new Class[args.size()]));
                for (MethodHandle h : ((Symbol) hd).fn)
                    try {
                        return insertArguments(h.asType(targetType), 0, args.toArray()).invokeExact();
                    } catch (WrongMethodTypeException ignore) {
                        System.out.println(ignore);
/*
                    } catch (NoSuchMethodException ignore) {
                    } catch (ClassCastException ignore) {
                    } catch (IncompatibleClassChangeError ignore) {
*/
                    } catch (Throwable t) {
                        System.out.println(hd + " " + h  + " " + args);
                        throw t;
                    }
            }
        } catch (Throwable throwable) {
            throw new RuntimeException(throwable);
        }
        throw new IllegalArgumentException();
    }

    public static Symbol defunInternal(Symbol name, Method m) {
        try {
            name.fn.add(lookup.unreflect(m));
            return name;
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    public static Symbol defun(Symbol name, List<Symbol> args, Object body) {
        Collections.reverse(args);
        name.fn.clear();
        MethodHandle fn;
        if (args.isEmpty()) {
            fn = (MethodHandle) eval_kl(asList(intern("lambda"), intern("_"), body));
            fn.bindTo(null);
        } else {
            for (Symbol arg : args)
                body = asList(intern("lambda"), arg, body);
            fn = (MethodHandle) eval_kl(body);
        }
        name.fn.add(fn);
        return name;
    }

    private static String unscramble(String s) {
        return s.replaceAll("_", "-").replaceAll("-p$", "?")
                .replaceAll("-ex$", "!").replaceAll("-?gt-?", "->")
                .replaceAll("-?lt-?", "<-").replaceAll("^kl-", "");
    }

    public static void main(String[] args) throws Throwable {

/*
        asList("sys", "writer", "core", "prolog", "yacc", "load",
                "macros", "reader", "sequent", "toplevel", "track", "t-star", "types")
            .forEach(f -> {
                load(format("shen/klambda/%s.kl", f));
            });
*/

        System.out.println(let(intern("x"), 2, eval_kl(intern("x"))));
        System.out.println(eval_kl(intern("x")));
        System.out.println(readEval("'(1 2 3)"));
        System.out.println(readEval("(+ 1 2)"));
        System.out.println(readEval("(+ 1.0 2.0)"));
        System.out.println(readEval("(* 5 2)"));
        System.out.println(readEval("(tl '(1 2 3))"));
        System.out.println(readEval("(let x 42 x)"));
        System.out.println(readEval("(let x 42 (let y 2 (cons x y)))"));
        System.out.println(readEval("((lambda x (lambda y (cons x y))) 2 3)"));
        System.out.println(readEval("((lambda x (lambda y (cons x y))) 2)"));
        System.out.println(readEval("((let x 3 (lambda y (cons x y))) 2)"));
        System.out.println(readEval("(cond (false 1) ((> 10 3) 3))"));
        System.out.println(eval_kl(asList(intern("quote"), asList(1, 2, 3))));
        System.out.println(eval_kl(asList(intern("hd"), asList(intern("quote"), asList(1, 2, 3)))));
        System.out.println(eval_kl(asList(intern("let"), intern("x"), 2, asList(intern("tl"), asList(intern("quote"), asList(1, 2, intern("x")))))));
        System.out.println(eval_kl(asList(intern("lambda"), intern("x"), intern("x"))));
        System.out.println(eval_kl(asList(intern("defun"), intern("my-fun"), asList(intern("x")), intern("x"))));
        System.out.println(str(eval_kl(asList(intern("my-fun"), 3))));
        System.out.println(eval_kl(asList(intern("defun"), intern("my-fun2"), asList(intern("x"), intern("y")), asList(intern("cons"), intern("y"), asList(intern("cons"), intern("x"), new LinkedList())))));
        System.out.println(str(eval_kl(asList(intern("my-fun2"), 3, 5))));
    }

    private static Object load(String file) {
        try {
            return read(new File(file)).reduce(null, (BinaryOperator) (left, right) -> eval_kl(right));
        } catch (Exception e) {
            throw new RuntimeException(e);
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

    public static <T> Object kl_if(Object test, Object then) throws Exception {
        return kl_if(test, then, false);
    }

    public static <T> Object kl_if(Object test, Object then, Object _else) throws Exception {
        return isTrue(eval_kl(test)) ? eval_kl(then) : eval_kl(_else);
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
        try {
            HashMap<Symbol, Object> scope = locals.reduce(new HashMap<>(), (left, right) -> right.into(left));
            Mapper<Object, Object> lambda = (X) -> {
                locals.push(new HashMap<Symbol, Object>(scope) {{
                    put(x, X);
                }});
                try {
                    return eval_kl(y);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                     locals.pop();
                }
            };
            return findSAM(lambda).bindTo(lambda);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    public static Object let(Symbol x, Object y, Object z) {
        try {
            return lambda(x, z).invoke(eval_kl(y));
        } catch (Throwable t) {
            throw new RuntimeException(t);
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
}
