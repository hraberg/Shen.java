package shen;

import java.io.*;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.*;
import java.util.function.*;
import java.util.stream.Stream;

import static java.lang.String.format;
import static java.lang.System.in;
import static java.lang.System.out;
import static java.lang.invoke.MethodHandles.constant;
import static java.lang.invoke.MethodHandles.dropArguments;
import static java.lang.invoke.MethodHandles.lookup;
import static java.lang.reflect.Modifier.isPublic;
import static java.util.Arrays.*;
import static java.util.Objects.deepEquals;

@SuppressWarnings({"UnusedDeclaration", "Convert2Diamond", "SuspiciousNameCombination"})
public class Shen {
    static final boolean debug = false;

    static MethodHandles.Lookup lookup = lookup();
    static Map<String, Symbol> symbols = new HashMap<>();

    @Retention(RetentionPolicy.RUNTIME)
    public @interface Macro {}

    static {
        set("*language*", "Java");
        set("*implementation*", format("[jvm %s]", System.getProperty("java.version")));
        set("*porters*", "Håkan Råberg");
        set("*stinput*", in);
        set("*stoutput*", out);
        set("*home-directory*", System.getProperty("user.dir"));

        stream(Shen.class.getDeclaredMethods())
              .filter(m -> isPublic(m.getModifiers()))
              .forEach(Shen::defun);

        op("=", (BiPredicate<Object, Object>)
                (left, right) -> left instanceof Number && right instanceof Number
                        ? ((Number) left).doubleValue() == ((Number) right).doubleValue()
                        : deepEquals(left, right));
        op("+", (IntBinaryOperator) (left, right) -> left + right);
        op("-", (IntBinaryOperator) (left, right) -> left - right);
        op("*", (IntBinaryOperator) (left, right) -> left * right);
        op("/", (IntBinaryOperator) (left, right) -> left / right);
        op("/", (BiFunction<Integer, Integer, Number>)
                (left, right) -> left % right == 0 ? left / right : left / (double) right);
        op("+", (LongBinaryOperator) (left, right) -> left + right);
        op("-", (LongBinaryOperator) (left, right) -> left - right);
        op("*", (LongBinaryOperator) (left, right) -> left * right);
        op("/", (LongBinaryOperator) (left, right) -> left / right);
        op("/", (BiFunction<Long, Long, Number>)
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

    static Symbol defun(Method m) {
       try {
            Symbol name = intern(unscramble(m.getName()));
            name.fn.add(lookup.unreflect(m));
           return name;
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    static MethodHandle findSAM(Object lambda) {
        try {
            return lookup.unreflect(findSAM(lambda.getClass())).bindTo(lambda);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    static Method findSAM(Class<?> lambda) {
        return some(stream(lambda.getDeclaredMethods()), m -> !m.isSynthetic());
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
        if (y == Collections.EMPTY_LIST)
            y = new ArrayList<>();
        y.add(0, x);
        return y;
    }

    public static boolean consP(Object x) {
        return x instanceof Cons || x instanceof List && !((List) x).isEmpty();
    }

    public static Object failEX() {
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
        return list.isEmpty() ? list : list.subList(1, list.size()).stream().into(new ArrayList<T>());
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
        if (consP(x)) throw new IllegalArgumentException();
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
        MethodHandle constant = constant(x.getClass(), x);
        return dropArguments(constant, 0, Object.class);
    }

    public static Class type(Object x) {
        return x.getClass();
    }

    public static Object[] absvector(int n) {
        Object[] objects = new Object[n];
        fill(objects, intern("fail!"));
        return objects;
    }

    public static boolean absvectorP(Object x) {
        return x != null && x.getClass() == Object[].class;
    }

    public static Object LT_address(Object[] vector, int n) {
        return vector[n];
    }

    public static Object[] address_GT(Object[] vector, int n, Object value) {
        vector[n] = value;
        return vector;
    }

    public static boolean numberP(Object x) {
        return x instanceof Number;
    }

    public static boolean stringP(Object x) {
        return x instanceof String;
    }

    public static String n_GTstring(int n) {
        if (n < 0) throw new IllegalArgumentException();
        return Character.toString((char) n);
    }

    public static String byte_GTstring(byte n) {
        return n_GTstring(n);
    }

    public static int string_GTn(String s) {
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

    public static class Symbol {
        public final String symbol;
        public Set<MethodHandle> fn = new HashSet<>();
        public Object var;

        public Symbol(String symbol) {
            this.symbol = symbol.intern();
        }

        public String toString() {
            return symbol;
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
        return x.fn.stream().findFirst().get();
    }

    static MethodHandle function(String x) {
        return function(intern(x));
    }

    public static Object eval_kl(Object kl) {
        try {
            return ShenCompiler.eval(kl);
        } catch (Throwable t) {
            throw new IllegalArgumentException(kl.toString(), t);
        }
    }

    static <T> T some(Stream<T> stream, Predicate<? super T> predicate) {
        return stream.filter(predicate).findAny().orElse((T) null);
    }

    @SafeVarargs
    static <T> List<T> list(T... elements) {
        return asList(elements).stream().into(new ArrayList<T>());
    }

    static String unscramble(String s) {
        return s.replaceAll("_", "-").replaceAll("P$", "?")
                .replaceAll("EX$", "!").replaceAll("GT", ">")
                .replaceAll("LT", "<").replaceAll("SLASH", "/").replaceAll("^kl-", "");
    }

    static String scramble(String s) {
        return s.replaceAll("-", "_").replaceAll(">", "GT")
                .replaceAll("<", "LT").replaceAll("/", "SLASH");
    }

    static Object load(String file) {
        try {
            out.println("LOADING " + file);
            //noinspection unchecked,RedundantCast
            return read(new File(file)).stream().reduce(null, (BinaryOperator) (left, right) -> eval_kl(right));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static Object readEval(String shen) throws Exception {
        return eval_kl(read(shen).get(0));
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
//        repl();
    }

    static Object repl() throws Exception {
        return readEval("(shen-shen)");
    }

    static void install() {
        for (String file : asList("sys", "writer", "core", "prolog", "yacc", "declarations", "load",
                "macros", "reader", "sequent", "toplevel", "track", "t-star", "types"))
            load(format("shen/klambda/%s.kl", file));
    }
}
