package shen;

import org.objectweb.asm.*;
import org.objectweb.asm.commons.GeneratorAdapter;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.util.ASMifier;
import org.objectweb.asm.util.TraceClassVisitor;
import sun.invoke.anon.AnonymousClassLoader;
import sun.invoke.util.Wrapper;
import sun.misc.Unsafe;

import java.io.*;
import java.lang.invoke.*;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.function.*;
import java.util.jar.Manifest;
import java.util.stream.Collector;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.lang.Character.isUpperCase;
import static java.lang.ClassLoader.getSystemClassLoader;
import static java.lang.Math.floorMod;
import static java.lang.String.format;
import static java.lang.System.*;
import static java.lang.invoke.MethodHandleProxies.asInterfaceInstance;
import static java.lang.invoke.MethodHandles.*;
import static java.lang.invoke.MethodHandles.lookup;
import static java.lang.invoke.MethodType.genericMethodType;
import static java.lang.invoke.MethodType.methodType;
import static java.lang.invoke.SwitchPoint.invalidateAll;
import static java.lang.reflect.Modifier.isPublic;
import static java.util.Arrays.*;
import static java.util.Arrays.fill;
import static java.util.Arrays.stream;
import static java.util.Collections.*;
import static java.util.Objects.deepEquals;
import static java.util.function.Predicates.*;
import static java.util.jar.Attributes.Name.IMPLEMENTATION_VERSION;
import static java.util.stream.Collectors.toSet;
import static java.util.stream.Streams.*;
import static org.objectweb.asm.ClassReader.SKIP_DEBUG;
import static org.objectweb.asm.ClassWriter.COMPUTE_FRAMES;
import static org.objectweb.asm.Type.*;
import static shen.Shen.KLReader.lines;
import static shen.Shen.KLReader.read;
import static shen.Shen.Primitives.*;
import static shen.Shen.RT.*;
import static shen.Shen.RT.lookup;
import static sun.invoke.util.BytecodeName.toBytecodeName;
import static sun.invoke.util.BytecodeName.toSourceName;
import static sun.invoke.util.Wrapper.*;

@SuppressWarnings({"UnusedDeclaration", "Convert2Diamond"})
public class Shen {
    public static void main(String... args) throws Throwable {
        install();
        eval("(shen-shen)");
    }

    static final Map<String, Symbol> symbols = new HashMap<>();

    static {
        set("*language*", "Java");
        set("*implementation*", format("%s (build %s)", getProperty("java.runtime.name"), getProperty("java.runtime.version")));
        set("*porters*", "Håkan Råberg");
        set("*port*", version());
        set("*stinput*", in);
        set("*stoutput*", out);
        set("*debug*", Boolean.getBoolean("shen.debug"));
        set("*debug-asm*", Boolean.getBoolean("shen.debug.asm"));
        set("*compile-path*", getProperty("shen.compile.path", "target/classes"));
        set("*home-directory*", getProperty("user.dir"));

        register(Primitives.class, RT::defun);
        register(Overrides.class, RT::override);

        op("+", (LongBinaryOperator) (left, right) -> left + right,
                (DoubleBinaryOperator) (left, right) -> left + right);
        op("-", (LongBinaryOperator) (left, right) -> left - right,
                (DoubleBinaryOperator) (left, right) -> left - right);
        op("*", (LongBinaryOperator) (left, right) -> left * right,
                (DoubleBinaryOperator) (left, right) -> left * right);
        op("/", (DoubleBinaryOperator) (left, right) -> {
            if (right == 0) throw new ArithmeticException("Division by zero");
            return left / right;
        });

        op("<", (LLPredicate) (left, right) -> left < right,
                (DDPredicate) (left, right) -> left < right);
        op("<=",(LLPredicate) (left, right) -> left <= right,
                (DDPredicate) (left, right) -> left <= right);
        op(">", (LLPredicate) (left, right) -> left > right,
                (DDPredicate) (left, right) -> left > right);
        op(">=",(LLPredicate) (left, right) -> left >= right,
                (DDPredicate) (left, right) -> left >= right);

        asList(Math.class, System.class).forEach(Primitives::KL_import);
    }

    interface LLPredicate { boolean test(long a, long b); }
    interface DDPredicate { boolean test(double a, double b); }

    public final static class Symbol {
        public final String symbol;
        public List<MethodHandle> fn = new ArrayList<>();
        public SwitchPoint fnGuard;
        public Object var;

        Symbol(String symbol) {
            this.symbol = symbol.intern();
        }

        public String toString() {
            return symbol;
        }

        public <T> T value() {
            if (var == null) throw new IllegalArgumentException("variable " + this + " has no value");
            //noinspection unchecked
            return (T) var;
        }

        public boolean equals(Object o) {
            //noinspection StringEquality
            return o instanceof Symbol && symbol == ((Symbol) o).symbol;
        }

        public int hashCode() {
            return symbol.hashCode();
        }
    }

    public final static class Cons extends AbstractCollection {
        public final Object car, cdr;
        public final int size;

        public Cons(Object car, Object cdr) {
            this.car = car;
            this.cdr = cdr;
            this.size = cdr instanceof Cons ? 1 + (((Cons) cdr).size) : EMPTY_LIST.equals(cdr) ? 1 : 2;
        }

        public boolean equals(Object o) {
            if (this == o) return true;
            if (o instanceof List && isList()) return toList().equals(o);
            if (o == null || getClass() != o.getClass()) return false;
            //noinspection ConstantConditions
            Cons cons = (Cons) o;
            return car.equals(cons.car) && cdr.equals(cons.cdr);
        }

        boolean isList() {
            return cdr instanceof Cons || EMPTY_LIST.equals(cdr);
        }

        public int hashCode() {
            return 31 * car.hashCode() + cdr.hashCode();
        }

        @SuppressWarnings("NullableProblems")
        public Iterator iterator() {
            if (!isList()) throw new IllegalStateException("cons pair is not a list: " + this);
            return new ConsIterator();
        }

        public int size() {
            return size;
        }

        public String toString() {
            if (isList()) return super.toString();
            return "[" + car + " | " + cdr + "]";
        }

        public List toList() {
            return new ArrayList<Object>(this);
        }

        class ConsIterator implements Iterator {
            Cons cons = Cons.this;

            public boolean hasNext() {
                return cons != null;
            }

            public Object next() {
                if (cons == null) throw new NoSuchElementException();
                try {
                    return cons.car;
                } finally {
                    cons = EMPTY_LIST.equals(cons.cdr) ? null : (Cons) cons.cdr;
                }
            }
         }
    }

    public static final class Primitives {
        public static boolean EQ(Object x, Object y) {
            return Objects.equals(x, y) || x instanceof Number && y instanceof Number
                    && ((Number) x).doubleValue() == ((Number) y).doubleValue();
        }

        public static Class KL_import(Symbol s) throws ClassNotFoundException {
            Class aClass = Class.forName(s.symbol);
            return set(intern(aClass.getSimpleName()), aClass);
        }

        static Class KL_import(Class type) {
            try {
                return KL_import(intern(type.getName()));
            } catch (ClassNotFoundException e) {
                throw uncheck(e);
            }
        }

        public static Cons cons(Object x, Object y) {
            return new Cons(x, y);
        }

        public static boolean consP(Object x) {
            return x instanceof Cons;
        }

        public static Object simple_error(String s) {
            throw new RuntimeException(s, null, false, false) {};
        }

       public static String error_to_string(Throwable e) {
            return e.getMessage() == null ? e.toString() : e.getMessage();
        }

        public static Object hd(Cons cons) {
            return cons.car;
        }

        public static Object tl(Cons cons) {
            return cons.cdr;
        }

        public static String str(Object x) {
            if (consP(x)) throw new IllegalArgumentException(x + " is not an atom; str cannot convert it to a string.");
            if (x != null && x.getClass().isArray()) return deepToString((Object[]) x);
            return String.valueOf(x);
        }

        public static String pos(String x, long n) {
            return str(x.charAt((int) n));
        }

        public static String tlstr(String x) {
            return x.substring(1);
        }

        public static Class type(Object x) {
            return x.getClass();
        }

        public static Object[] absvector(long n) {
            Object[] objects = new Object[(int) n];
            fill(objects, intern("fail!"));
            return objects;
        }

        public static boolean absvectorP(Object x) {
            return x != null && x.getClass() == Object[].class;
        }

        public static Object LT_address(Object[] vector, long n) {
            return vector[((int) n)];
        }

        public static Object[] address_GT(Object[] vector, long n, Object value) {
            vector[((int) n)] = value;
            return vector;
        }

        public static boolean numberP(Object x) {
            return x instanceof Number;
        }

        public static boolean stringP(Object x) {
            return x instanceof String;
        }

        public static String n_GTstring(long n) {
            if (n < 0) throw new IllegalArgumentException(n + " is not a valid character");
            return Character.toString((char) n);
        }

        public static String byte_GTstring(long n) {
            return n_GTstring(n);
        }

        public static long string_GTn(String s) {
            return (int) s.charAt(0);
        }

        public static long read_byte(InputStream s) throws IOException {
            return s.read();
        }

        public static long read_byte(Reader s) throws IOException {
            return s.read();
        }

        public static <T> T pr(T x, OutputStream s) throws IOException {
            return pr(x, new OutputStreamWriter(s));
        }

        public static <T> T pr(T x, Writer s) throws IOException {
            s.write(str(x));
            s.flush();
            return x;
        }

        public static Closeable open(Symbol type, String string, Symbol direction) throws IOException {
            if (!"file".equals(type.symbol)) throw new IllegalArgumentException("invalid stream type");
            File file = new File((String) intern("*home-directory*").value(), string);
            switch (direction.symbol) {
                case "in": return new BufferedInputStream(new FileInputStream(file));
                case "out": return new BufferedOutputStream(new FileOutputStream(file));
            }
            throw new IllegalArgumentException("invalid direction");
        }

        public static Object close(Closeable stream) throws IOException {
            stream.close();
            return EMPTY_LIST;
        }

        static long startTime = System.currentTimeMillis();
        public static double get_time(Symbol time) {
            switch (time.symbol) {
                case "run": return (currentTimeMillis() - startTime) / 1000.0;
                case "unix": return currentTimeMillis() / 1000;
            }
            throw new IllegalArgumentException("get-time does not understand the parameter " + time);
        }

        public static String cn(String s1, String s2) {
            return s1 + s2;
        }

        public static Symbol intern(String string) {
            if (!symbols.containsKey(string)) symbols.put(string, new Symbol(string));
            return symbols.get(string);
        }

        @SuppressWarnings("unchecked")
        public static <T> T set(Symbol x, T y) {
            return (T) (x.var = y);
        }

        static <T> T set(String x, T y) {
            return set(intern(x), y);
        }

        public static MethodHandle function(Symbol x) throws IllegalAccessException {
            if (x.fn.isEmpty()) return reLinker(x.symbol, 0);
            MethodHandle fn = x.fn.get(0);
            if (x.fn.size() > 1) return reLinker(x.symbol, fn.type().parameterCount());
            return fn;
        }

        static MethodHandle function(String x) throws IllegalAccessException {
            return function(intern(x));
        }

        public static Object eval_kl(Object kl) throws Throwable {
            return new Compiler(kl).load("__eval__", Callable.class).newInstance().call();
        }

        public static boolean or(boolean x, boolean y) {
            return x || y;
        }

        public static boolean and(boolean x, boolean y) {
            return x && y;
        }
    }

    public static final class Overrides {
        static final Symbol _true = intern("true"), _false = intern("false"), shen_tuple = intern("shen-tuple");

        public static boolean variableP(Object x) {
            return x instanceof Symbol && isUpperCase(((Symbol) x).symbol.charAt(0));
        }

        public static boolean booleanP(Object x) {
            return x instanceof Boolean || _true == x || _false == x;
        }

        public static boolean symbolP(Object x) {
            return x instanceof Symbol && !booleanP(x);
        }

        public static long length(Collection x) {
            return x.size();
        }

        public static Object[] ATp(Object x, Object y) {
            return new Object[] {shen_tuple, x, y};
        }

        public static long hash(Object s, long limit) {
            long hash = s.hashCode();
            if (hash == 0) return 1;
            return floorMod(hash, limit);
        }

        public static Object[] shen_fillvector(Object[] vector, long counter, long n, Object x) {
            fill(vector, (int) counter, (int) n + 1, x);
            return vector;
        }
    }

    static boolean isDebug() {
        return booleanProperty("*debug*");
    }

    static boolean booleanProperty(String property) {
        return intern(property).var == Boolean.TRUE;
    }

    public static Object eval(String kl) throws Throwable {
        return eval_kl(read(new StringReader(kl)).get(0));
    }

    static void install() throws Throwable {
        set("shen-*installing-kl*", true);
        for (String file : asList("toplevel", "core", "sys", "sequent", "yacc", "reader",
                "prolog", "track", "load", "writer", "macros", "declarations", "types", "t-star"))
            load("klambda/" + file, Callable.class).newInstance().call();
    }

    @SuppressWarnings("unchecked")
    static <T> Class<T> load(String file, Class<T> aClass) throws Throwable {
        try {
            return (Class<T>) getSystemClassLoader().loadClass(file.replaceAll("/", "."));
        } catch (ClassNotFoundException e) {
            debug("compiling: %s", file);
            return compile(file, aClass);
        }
    }

    static <T> Class<T> compile(String file, Class<T> aClass) throws Throwable {
        try (Reader in = resource(format("%s.kl", file))) {
            debug("loading: %s", file);
            Compiler compiler = new Compiler(null, file, cons(intern("do"), read(in)));
            File compilePath = new File((String) intern("*compile-path*").value());
            File classFile = new File(compilePath, file + ".class");
            if (!(compilePath.mkdirs() || compilePath.isDirectory())) throw new IOException("could not make directory: " + compilePath);
            try {
                return compiler.load(classFile.getName().replaceAll(".class$", ".kl"), aClass);
            } finally {
                lines.clear();
                try (OutputStream out = new FileOutputStream(classFile)) {
                    out.write(compiler.bytes);
                }
            }
        }
    }

    static Reader resource(String resource) {
        return new BufferedReader(new InputStreamReader(getSystemClassLoader().getResourceAsStream(resource)));
    }

    static String version() {
        String version = null;
        try (InputStream manifest = getSystemClassLoader().getResourceAsStream("META-INF/MANIFEST.MF")) {
                version = new Manifest(manifest).getMainAttributes().getValue(IMPLEMENTATION_VERSION);
        } catch (IOException ignored) {
        }
        return version != null ? version : "<unknown>";
    }

    public static class KLReader {
        static Map<Object, Integer> lines = new IdentityHashMap<>();
        static int currentLine;

        static List<Object> read(Reader reader) throws Exception {
            lines.clear();
            currentLine = 1;
            return tokenizeAll(new Scanner(reader).useDelimiter("(\\s|\\)|\")"));
        }

        static Object tokenize(Scanner sc) throws Exception {
            whitespace(sc);
            if (find(sc, "\\(")) return tokenizeAll(sc);
            if (find(sc, "\"")) return nextString(sc);
            if (find(sc, "\\)")) return null;
            if (sc.hasNextBoolean()) return sc.nextBoolean();
            if (sc.hasNextLong()) return sc.nextLong();
            if (sc.hasNextDouble()) return sc.nextDouble();
            if (sc.hasNext()) return intern(sc.next());
            return null;
        }

        static void whitespace(Scanner sc) {
            sc.skip("[^\\S\\n]*");
            while (find(sc, "\\n")) {
                currentLine++;
                sc.skip("[^\\S\\n]*");
            }
        }

        static boolean find(Scanner sc, String pattern) {
            return sc.findWithinHorizon(pattern, 1) != null;
        }

        static Object nextString(Scanner sc) throws IOException {
            String s = sc.findWithinHorizon("(?s).*?\"", 0);
            currentLine += s.replaceAll("[^\n]", "").length();
            return s.substring(0, s.length() - 1);
        }

        static List<Object> tokenizeAll(Scanner sc) throws Exception {
            List<Object> list = list();
            lines.put(list, currentLine);
            Object x;
            while ((x = tokenize(sc)) != null) list.add(x);
            return list;
        }
    }

    public static class RT {
        static final Lookup lookup = lookup();
        static final Set<Symbol> overrides = new HashSet<>();
        static final Map<String, CallSite> sites = new HashMap<>();

        static final MethodHandle
                link = mh(RT.class, "link"), proxy = mh(RT.class, "proxy"),
                checkClass = mh(RT.class, "checkClass"), toIntExact = mh(Math.class, "toIntExact"),
                partial = mh(RT.class, "partial"), arityCheck = mh(RT.class, "arityCheck");

        public static Object link(MutableCallSite site, String name, Object... args) throws Throwable {
            name = toSourceName(name);
            MethodType type = site.type();
            debug("LINKING: %s%s %s", name, type, args);
            List<Class<?>> actualTypes = vec(stream(args).map(Object::getClass));
            debug("actual types: %s", actualTypes);
            Symbol symbol = intern(name);
            debug("candidates: %s", symbol.fn);

            if (symbol.fn.isEmpty()) {
                MethodHandle java = javaCall(site, name, type, args);
                if (java != null) {
                    debug("calling java: %s", java);
                    site.setTarget(java.asType(type));
                    return java.invokeWithArguments(args);
                }
                throw new NoSuchMethodException("undefined function " + name + type
                        + (symbol.fn.isEmpty() ?  "" : " in " + vec(symbol.fn.stream().map(MethodHandle::type))));
            }

            int arity = symbol.fn.get(0).type().parameterCount();
            if (arity > args.length) {
                MethodHandle partial = insertArguments(reLinker(name, arity), 0, args);
                debug("partial: %s", partial);
                return partial;
            }

            MethodHandle match = find(symbol.fn.stream(), f -> all(actualTypes, f.type().parameterList(), RT::canCastStrict));
            if (match == null) throw new NoSuchMethodException("undefined function " + name + type);
            debug("match based on argument types: %s", match);

            MethodHandle fallback = linker(site, toBytecodeName(name)).asType(type);
            if (symbol.fn.size() >  1) {
                if (match.type().changeReturnType(Object.class).hasPrimitives()) {
                    debug("falling back to exception guard for %s", name);
                    match = relinkOn(ClassCastException.class, match, fallback);
                } else
                    match = guard(name, type, symbol.fn);
                debug("selected: %s", match);
            }

            synchronized (symbol.symbol) {
                if (symbol.fnGuard == null) symbol.fnGuard = new SwitchPoint();
                site.setTarget(symbol.fnGuard.guardWithTest(match.asType(type), fallback));
            }

            return match.invokeWithArguments(args);
        }

        static Map<List, MethodHandle> guards = new HashMap<>();

        static MethodHandle guard(String name, MethodType type, List<MethodHandle> candidates) {
            List key = asList(name, type, candidates);
            if (guards.containsKey(key)) return guards.get(key);
            candidates = bestMatchingMethods(type, candidates);
            debug("applicable candidates: %s", candidates);
            MethodHandle match = candidates.get(candidates.size() - 1).asType(type);
            for (int i = candidates.size() - 1; i > 0; i--) {
                MethodHandle fallback = candidates.get(i);
                MethodHandle target = candidates.get(i - 1);
                Class<?> differentType = find(target.type().parameterList(), fallback.type().parameterList(), (x, y) -> !x.equals(y));
                int firstDifferent = target.type().parameterList().indexOf(differentType);
                debug("switching %s on %d argument type %s", name, firstDifferent, differentType);
                debug("target: %s ; fallback: %s", target, fallback);
                MethodHandle test = checkClass.bindTo(differentType);
                test = dropArguments(test, 0, type.dropParameterTypes(firstDifferent, type.parameterCount()).parameterList());
                test = test.asType(test.type().changeParameterType(firstDifferent, type.parameterType(firstDifferent)));
                match = guardWithTest(test, target.asType(type), match);
            }
            guards.put(key, match);
            return match;
        }

        static List<MethodHandle> bestMatchingMethods(MethodType type, List<MethodHandle> candidates) {
            return vec(candidates.stream()
                    .filter(f -> all(type.parameterList(), f.type().parameterList(), RT::canCast))
                    .sorted((x, y) -> y.type().changeReturnType(type.returnType()).equals(y.type().erase()) ? -1 : 1)
                    .sorted((x, y) -> all(y.type().parameterList(), x.type().parameterList(), RT::canCast) ? -1 : 1));
        }

        public static boolean checkClass(Class<?> xClass, Object x) {
            return canCastStrict(x.getClass(), xClass);
        }

        static MethodHandle relinkOn(Class<? extends Throwable> exception, MethodHandle fn, MethodHandle fallback) {
            return catchException(fn.asType(fallback.type()), exception, dropArguments(fallback, 0, Exception.class));
        }

        static MethodHandle javaCall(MutableCallSite site, String name, MethodType type, Object... args) throws Exception {
            if (name.endsWith(".")) {
                Class aClass = intern(name.substring(0, name.length() - 1)).value();
                if (aClass != null)
                    return findJavaMethod(type, aClass.getName(), aClass.getConstructors());
            }
            if (name.startsWith("."))
                return relinkOn(ClassCastException.class, findJavaMethod(type, name.substring(1), args[0].getClass().getMethods()),
                        linker(site, toBytecodeName(name)));
            String[] classAndMethod = name.split("/");
            if (classAndMethod.length == 2 && intern(classAndMethod[0]).var instanceof Class)
                return findJavaMethod(type, classAndMethod[1], ((Class) intern(classAndMethod[0]).value()).getMethods());
            return null;
        }

        public static Object proxy(Method sam, Object x) throws Throwable {
            if (x instanceof MethodHandle) {
                MethodHandle target = (MethodHandle) x;
                int arity = sam.getParameterTypes().length;
                int actual = target.type().parameterCount();
                if (arity < actual) target = insertArguments(target, arity, new Object[actual - arity]);
                if (arity > actual) target = dropArguments(target, actual, asList(sam.getParameterTypes()).subList(actual, arity));
                return asInterfaceInstance(sam.getDeclaringClass(), target);
            }
            return null;
        }

        static MethodHandle filterJavaTypes(MethodHandle method) throws IllegalAccessException {
            MethodHandle[] filters = new MethodHandle[method.type().parameterCount()];
            for (int i = 0; i < method.type().parameterCount() - (method.isVarargsCollector() ? 1 : 0); i++)
                if (isSAM(method.type().parameterType(i)))
                    filters[i] = proxy.bindTo(findSAM(method.type().parameterType(i)))
                            .asType(methodType(method.type().parameterType(i), Object.class));
                else  if (canCast(method.type().parameterType(i), int.class))
                    filters[i] = toIntExact.asType(methodType(method.type().parameterType(i), Object.class));
            if (canCast(method.type().returnType(), int.class))
                method = method.asType(method.type()
                        .changeReturnType(method.type().returnType().isPrimitive() ? long.class : Long.class));
            return filterArguments(method, 0, filters);
        }

        static <T extends Executable> MethodHandle findJavaMethod(MethodType type, String method, T[] methods) {
            return some(stream(methods), m -> {
                try {
                    if (m.getName().equals(method)) {
                        m.setAccessible(true);
                        MethodHandle mh = (m instanceof Method) ? lookup.unreflect((Method) m) : lookup.unreflectConstructor((Constructor) m);
                        mh.asType(methodType(type.returnType(), vec(type.parameterList().stream()
                                .map(c -> c.equals(Long.class) ? Integer.class : c.equals(long.class) ? int.class : c))));
                        return filterJavaTypes(mh);
                    }
                } catch (WrongMethodTypeException | IllegalAccessException ignored) {
                }
                return null;
            });
        }

        public static MethodHandle function(Object target) throws IllegalAccessException {
            return target instanceof Symbol ? Primitives.function((Symbol) target) : (MethodHandle) target;
        }

        static MethodHandle linker(MutableCallSite site, String name) throws IllegalAccessException {
            return insertArguments(link, 0, site, name).asCollector(Object[].class, site.type().parameterCount());
        }

        static MethodHandle reLinker(String name, int arity) throws IllegalAccessException {
            MutableCallSite reLinker = new MutableCallSite(genericMethodType(arity));
            return relinkOn(IllegalStateException.class, reLinker.dynamicInvoker(), linker(reLinker, toBytecodeName(name)));
        }

        public static CallSite invokeBSM(Lookup lookup, String name, MethodType type) throws IllegalAccessException {
            if (isOverloadedInternalFunction(name)) return invokeCallSite(name, type);
            String key = name + type;
            if (!sites.containsKey(key)) sites.put(key, invokeCallSite(name, type));
            return sites.get(key);
        }

        static boolean isOverloadedInternalFunction(String name) {
            return intern(toSourceName(name)).fn.size() > 1;
        }

        static CallSite invokeCallSite(String name, MethodType type) throws IllegalAccessException {
            MutableCallSite site = new MutableCallSite(type);
            site.setTarget(linker(site, name).asType(type));
            return site;
        }

        public static CallSite symbolBSM(Lookup lookup, String name, MethodType type) {
            return new ConstantCallSite(constant(Symbol.class, intern(toSourceName(name))));
        }

        public static CallSite applyBSM(Lookup lookup, String name, MethodType type) throws Exception {
            String key = name + type;
            if (!sites.containsKey(key)) sites.put(key, applyCallSite(type));
            return sites.get(key);
        }

        public static Object partial(MethodHandle target, Object... args) throws Throwable {
            if (args.length > target.type().parameterCount()) return uncurry(target, args);
            return insertArguments(target, 0, args);
        }

        public static boolean arityCheck(int arity, MethodHandle target) throws Throwable {
            return target.type().parameterCount() == arity;
        }

        static CallSite applyCallSite(MethodType type) {
            MethodHandle apply = invoker(type.dropParameterTypes(0, 1));
            MethodHandle test = insertArguments(arityCheck, 0, type.parameterCount() - 1);
            return new ConstantCallSite(guardWithTest(test, apply, partial.asType(type)).asType(type));
        }

        static MethodHandle mh(Class<?> aClass, String name, Class... types) {
            try {
                return lookup.unreflect(find(stream(aClass.getMethods()), m -> m.getName().equals(name)
                        && (types.length == 0 || deepEquals(m.getParameterTypes(), types))));
            } catch (IllegalAccessException e) {
                throw uncheck(e);
            }
        }

        static MethodHandle field(Class<?> aClass, String name) {
            try {
                return lookup.unreflectGetter(aClass.getField(name));
            } catch (Exception e) {
                throw uncheck(e);
            }
        }

        static boolean canCast(Class<?> a, Class<?> b) {
            return a == Object.class  || b == Object.class || canCastStrict(a, b);
        }

        static boolean canCastStrict(Class<?> a, Class<?> b) {
            return a == b || b.isAssignableFrom(a) || canWiden(a, b);
        }

        static boolean canWiden(Class<?> a, Class<?> b) {
            return wrapper(b).isNumeric() && wrapper(b).isConvertibleFrom(wrapper(a));
        }

        static Wrapper wrapper(Class<?> type) {
            if (isPrimitiveType(type)) return forPrimitiveType(type);
            if (isWrapperType(type)) return forWrapperType(type);
            return forBasicType(type);
        }

        public static Symbol defun(Symbol name, MethodHandle fn) throws Throwable {
            if (overrides.contains(name)) return name;
            synchronized (name.symbol) {
                SwitchPoint guard = name.fnGuard;
                name.fn.clear();
                name.fn.add(fn);
                name.fnGuard = new SwitchPoint();
                if (guard != null) invalidateAll(new SwitchPoint[] {guard});
                return name;
            }
        }

        static void op(String name, Object... op) {
            intern(name).fn.addAll(vec(stream(op).map(RT::findSAM)));
        }

        static void register(Class<?> aClass, Block<? super Method> hook) {
            stream(aClass.getDeclaredMethods()).filter(m -> isPublic(m.getModifiers())).forEach(hook);
        }

        static void override(Method m) {
            overrides.add(defun(m));
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

        static Object uncurry(Object chain, Object... args) throws Throwable {
            for (Object arg : args)
                chain = ((MethodHandle) chain).invokeExact(arg);
            return chain;
        }

        public static MethodHandle bindTo(MethodHandle fn, Object arg) {
            return fn.isVarargsCollector() ?
                    insertArguments(fn, 0, arg).asVarargsCollector(fn.type().parameterType(fn.type().parameterCount() - 1)) :
                    insertArguments(fn, 0, arg);
        }

        static String unscramble(String s) {
            return toSourceName(s).replaceAll("_", "-").replaceAll("^KL-", "") .replaceAll("GT", ">").replaceAll("EQ", "=")
                    .replaceAll("LT", "<").replaceAll("EX$", "!").replaceAll("P$", "?").replaceAll("^AT", "@");
        }

        static MethodHandle findSAM(Object lambda) {
            try {
                return lookup.unreflect(findSAM(lambda.getClass())).bindTo(lambda);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }

        static Method findSAM(Class<?> lambda) {
            List<Method> methods = vec(stream(lambda.getDeclaredMethods()).filter(m -> !m.isSynthetic()));
            return methods.size() == 1 ? methods.get(0) : null;
        }

        static boolean isSAM(Class<?> aClass) {
            return findSAM(aClass) != null;
        }
    }

    public static class Compiler implements Opcodes {
        static final AnonymousClassLoader loader = AnonymousClassLoader.make(unsafe(), RT.class);
        static final Map<Symbol, MethodHandle> macros = new HashMap<>();
        static final List<Class<?>> literals =
                asList(Double.class, Integer.class, Long.class, String.class, Boolean.class, Handle.class);
        static final Handle
                applyBSM = handle(mh(RT.class, "applyBSM")), invokeBSM = handle(mh(RT.class, "invokeBSM")),
                symbolBSM = handle(mh(RT.class, "symbolBSM")), or = handle(RT.mh(Primitives.class, "or")),
                and = handle(RT.mh(Primitives.class, "and"));
        static {
            register(Macros.class, Compiler::macro);
        }

        static int id = 1;

        String className;

        ClassWriter cw;

        byte[] bytes;
        GeneratorAdapter mv;
        Object kl;

        Symbol self;
        org.objectweb.asm.commons.Method method;
        Map<Symbol, Integer> locals;
        List<Symbol> args;
        List<Type> argTypes;
        Type topOfStack;
        Label recur;

        public Compiler(Object kl, Symbol... args) throws Throwable {
            this(null, "shen/ShenEval" + id++, kl, args);
        }

        public Compiler(ClassWriter cn, String className, Object kl, Symbol... args) throws Throwable {
            this.cw = cn;
            this.className = className;
            this.kl = kl;
            this.args = list(args);
            this.locals = new HashMap<>();
        }

        static ClassWriter classWriter(String name, Class<?> anInterface) {
            ClassWriter cw = new ClassWriter(COMPUTE_FRAMES);
            cw.visit(V1_7, ACC_PUBLIC | ACC_FINAL, name, null, getInternalName(Object.class), new String[] {getInternalName(anInterface)});
            return cw;
        }

        static org.objectweb.asm.commons.Method method(String name, String desc) {
            return new org.objectweb.asm.commons.Method(name, desc);
        }


        static String desc(Class<?> returnType, Class<?>... argumentTypes) {
            return methodType(returnType, argumentTypes).toMethodDescriptorString();
        }

        static String desc(Type returnType, List<Type> argumentTypes) {
            return getMethodDescriptor(returnType, argumentTypes.toArray(new Type[argumentTypes.size()]));
        }

        static Handle handle(MethodHandle handle) {
            try {
                MethodHandleInfo info = new MethodHandleInfo(handle);
                return handle(getInternalName(info.getDeclaringClass()), info.getName(), handle.type().toMethodDescriptorString());
            } catch (ReflectiveOperationException e) {
                throw uncheck(e);
            }
        }

        static Handle handle(String className, String name, String desc) {
            return new Handle(Opcodes.H_INVOKESTATIC, className, name, desc);
        }

        static Type boxedType(Type type) {
            if (!isPrimitive(type)) return type;
            return getType(forBasicType(type.getDescriptor().charAt(0)).wrapperType());
        }

        static boolean isPrimitive(Type type) {
            return type.getSort() < ARRAY;
        }

        static void macro(Method m) {
            try {
                macros.put(intern(unscramble(m.getName())), lookup.unreflect(m));
            } catch (IllegalAccessException e) {
                throw uncheck(e);
            }
        }

        GeneratorAdapter generator(int access, org.objectweb.asm.commons.Method method) {
            return new GeneratorAdapter(access, method, cw.visitMethod(access, method.getName(), method.getDescriptor(), null, null));
        }

        Type compile(Object kl) {
            return compile(kl, true);
        }

        Type compile(Object kl, boolean tail) {
            return compile(kl, getType(Object.class), tail);
        }

        Type compile(Object kl, Type returnType, boolean tail) {
            return compile(kl, returnType, true, tail);
        }

        Type compile(Object kl, Type returnType, boolean handlePrimitives, boolean tail) {
            try {
                Class literalClass = find(literals.stream(), c -> c.isInstance(kl));
                if (literalClass != null) push(literalClass, kl);
                else if (kl instanceof Symbol) symbol((Symbol) kl);
                else if (kl instanceof Collection) {
                    @SuppressWarnings("unchecked")
                    List<Object> list = new ArrayList<>((Collection<?>) kl);
                    lineNumber(list);
                    if (list.isEmpty()) emptyList();
                    else {
                        Object first = list.get(0);
                        if (first instanceof Symbol && !inScope((Symbol) first)) {
                            Symbol s = (Symbol) first;
                            if (macros.containsKey(s)) macroExpand(s, tl(list), returnType, tail);
                            else indy(s, tl(list), returnType, tail);

                        } else {
                            compile(first, tail);
                            apply(returnType, tl(list));
                        }
                    }
                } else
                    throw new IllegalArgumentException("Cannot compile: " + kl + " (" + kl.getClass() + ")");
                if (handlePrimitives) handlePrimitives(returnType);
                return topOfStack;
            } catch (RuntimeException | Error e) {
                throw e;
            } catch (Throwable t) {
                throw uncheck(t);
            }
        }

        void handlePrimitives(Type returnType) {
            if (isPrimitive(returnType) && !isPrimitive(topOfStack)) unbox(returnType);
            else if (!isPrimitive(returnType) && isPrimitive(topOfStack)) box();
        }

        void lineNumber(List<Object> list) {
            if (lines.containsKey(list)) {
                Label line = mv.newLabel();
                mv.visitLabel(line);
                mv.visitLineNumber(lines.get(list), line);
            }
        }

        boolean inScope(Symbol x) {
            return (locals.containsKey(x) || args.contains(x));
        }

        void macroExpand(Symbol s, List<Object> args, Type returnType, boolean tail) throws Throwable {
            macros.get(s).invokeWithArguments(into(asList(new Macros(), tail, returnType),
                    vec(args.stream().map(x -> x instanceof Cons ? ((Cons) x).toList() : x))));
        }

        void indy(Symbol s, List<Object> args, Type returnType, boolean tail) throws ReflectiveOperationException {
            List<Type> argumentTypes = vec(args.stream().map(o -> compile(o, getType(Object.class), false, false)));

            if (isSelfCall(s, args)) {
                if (tail) {
                    debug("recur: %s", s);
                    recur(argumentTypes);
                } else {
                    debug("can only recur from tail position: %s", s);
                    mv.invokeStatic(getType(className), method);
                    topOfStack = method.getReturnType();
                    handlePrimitives(returnType);
                }
            } else
                mv.invokeDynamic(toBytecodeName(s.symbol), desc(returnType, argumentTypes), invokeBSM);
            topOfStack = returnType;
        }

        void recur(List<Type> argumentTypes) {
            for (int i = args.size() - 1; i >= 0; i--) {
                mv.valueOf(argumentTypes.get(i));
                mv.storeArg(i);
            }
            mv.goTo(recur);
        }

        boolean isSelfCall(Symbol s, List<Object> args) {
            return self.equals(s) && args.size() == this.args.size();
        }

        void apply(Type returnType, List<Object> args) throws ReflectiveOperationException {
            if (!topOfStack.equals(getType(MethodHandle.class)))
                mv.invokeStatic(getType(RT.class), method("function", desc(MethodHandle.class, Object.class)));
            List<Type> argumentTypes = cons(getType(MethodHandle.class), vec(args.stream().map(o -> compile(o, false))));
            mv.invokeDynamic("__apply__", desc(returnType, argumentTypes), applyBSM);
            topOfStack = returnType;
        }

        class Macros {
            public void trap_error(boolean tail, Type returnType, Object x, Object f) throws Throwable {
                Label start = mv.newLabel();
                Label end = mv.newLabel();
                Label after = mv.newLabel();

                mv.visitLabel(start);
                compile(x, returnType, tail);
                mv.goTo(after);
                mv.visitLabel(end);

                mv.catchException(start, end, getType(Throwable.class));
                compile(f, false);
                maybeCast(MethodHandle.class);
                mv.swap();
                bindTo();

                mv.invokeVirtual(getType(MethodHandle.class), method("invokeExact", desc(Object.class)));
                if (isPrimitive(returnType)) unbox(returnType);
                else topOfStack(Object.class);
                mv.visitLabel(after);
            }

            public void KL_if(boolean tail, Type returnType, Object test, Object then, Object _else) throws Exception {
                if (test == Boolean.TRUE || test == intern("true")) {
                    compile(then, returnType, tail);
                    return;
                }
                if (test == Boolean.FALSE || test == intern("false")) {
                    compile(_else, returnType, tail);
                    return;
                }

                Label elseStart = mv.newLabel();
                Label end = mv.newLabel();

                compile(test, BOOLEAN_TYPE, false);
                if (!BOOLEAN_TYPE.equals(topOfStack)) {
                    popStack();
                    mv.throwException(getType(IllegalArgumentException.class), "boolean expected");
                    return;
                }
                mv.visitJumpInsn(IFEQ, elseStart);

                compile(then, returnType, tail);
                Type typeOfThenBranch = topOfStack;
                mv.goTo(end);

                mv.visitLabel(elseStart);
                compile(_else, returnType, tail);

                mv.visitLabel(end);
                if (!typeOfThenBranch.equals(topOfStack) && !isPrimitive(returnType))
                    topOfStack(Object.class);
            }

            public void cond(boolean tail, Type returnType, List... clauses) throws Exception {
                if (clauses.length == 0)
                    mv.throwException(getType(IllegalArgumentException.class), "condition failure");
                else
                    KL_if(tail, returnType, hd(clauses).get(0), hd(clauses).get(1), cons(intern("cond"), list((Object[]) tl(clauses))));
            }

            public void or(boolean tail, Type returnType, Object x, Object... clauses) throws Exception {
                if (clauses.length == 0)
                    bindTo(or, x);
                else {
                    KL_if(tail, BOOLEAN_TYPE, x, true, (clauses.length > 1 ? cons(intern("or"), list(clauses)) : clauses[0]));
                    if (!isPrimitive(returnType)) mv.box(returnType);
                }
            }

            public void and(boolean tail, Type returnType, Object x, Object... clauses) throws Exception {
                if (clauses.length == 0)
                    bindTo(and, x);
                else {
                    KL_if(tail, BOOLEAN_TYPE, x, (clauses.length > 1 ? cons(intern("and"), list(clauses)) : clauses[0]), false);
                    if (!isPrimitive(returnType)) mv.box(returnType);
                }
            }

            public void value(boolean tail, Type returnType, Object x) throws Throwable {
                compile(x, false);
                maybeCast(Symbol.class);
                mv.invokeVirtual(getType(Symbol.class), method("value", desc(Object.class)));
                topOfStack(Object.class);
            }

            public void lambda(boolean tail, Type returnType, Symbol x, Object y) throws Throwable {
                fn("__lambda__", y, x);
            }

            public void freeze(boolean tail, Type returnType, Object x) throws Throwable {
                fn("__freeze__", x);
            }

            public void defun(boolean tail, Type returnType, Symbol name, final List<Symbol> args, Object body) throws Throwable {
                push(name);
                debug("compiling: %s%s in %s", name, args, getObjectType(className).getClassName());
                fn(name.symbol, body, args.toArray(new Symbol[args.size()]));
                mv.invokeStatic(getType(RT.class), method("defun", desc(Symbol.class, Symbol.class, MethodHandle.class)));
                topOfStack(Symbol.class);
            }

            public void let(boolean tail, Type returnType, Symbol x, Object y, Object z) throws Throwable {
                compile(y, false);
                int let = mv.newLocal(topOfStack);
                mv.storeLocal(let);
                Integer hidden = locals.put(x, let);
                compile(z, returnType, tail);
                if (hidden != null) locals.put(x, hidden);
                else locals.remove(x);
            }

            public void KL_do(boolean tail, Type returnType, Object... xs) throws Throwable {
                for (int i = 0; i < xs.length; i++) {
                    boolean last = i == xs.length - 1;
                    compile(xs[i], last ? returnType : getType(Object.class), last && tail);
                    if (!last) popStack();
                }
            }

            public void thaw(boolean tail, Type returnType, Object f) throws Throwable {
                compile(f, false);
                maybeCast(MethodHandle.class);
                mv.invokeVirtual(getType(MethodHandle.class), method("invokeExact", desc(Object.class)));
                topOfStack(Object.class);
            }
        }

        void fn(String name, Object kl, Symbol... args) throws Throwable {
            String bytecodeName = toBytecodeName(name) + "_" + id++;
            List<Symbol> scope = vec(closesOver(new HashSet<>(asList(args)), kl).uniqueElements());
            scope.retainAll(into(locals.keySet(), this.args));

            List<Type> types = into(vec(scope.stream().map(this::typeOf)), nCopies(args.length, getType(Object.class)));
            push(handle(className, bytecodeName, desc(getType(Object.class), types)));
            insertArgs(0, scope);

            scope.addAll(asList(args));
            Compiler fn = new Compiler(cw, className, kl, scope.toArray(new Symbol[scope.size()]));
            fn.method(ACC_PUBLIC | ACC_STATIC | ACC_FINAL, intern(name), bytecodeName, getType(Object.class), types);
        }

        @SuppressWarnings({"unchecked"})
        Stream<Symbol> closesOver(Set<Symbol> scope, Object kl) {
            if (kl instanceof Symbol && !scope.contains(kl))
                return singleton((Symbol) kl).stream();
            if (kl instanceof Collection) {
                List<Object> list = new ArrayList<>((Collection<?>) kl);
                if (!list.isEmpty())
                    switch (hd(list).toString()) {
                        case "let": return concat(closesOver(scope, list.get(2)), closesOver(conj(scope, list.get(2)), list.get(3)));
                        case "lambda": return closesOver(conj(scope, list.get(2)), list.get(2));
                        case "defun": return closesOver(into(scope, (Collection) list.get(2)), list.get(3));
                    }
                    return mapcat(list.stream(), o -> closesOver(scope, o));
            }
            return emptyStream();
        }

        void emptyList() {
            mv.getStatic(getType(Collections.class), "EMPTY_LIST", getType(List.class));
            topOfStack(List.class);
        }

        void symbol(Symbol s) throws Throwable {
            if (asList("true", "false").contains(s.symbol)) {
                push(Boolean.class, Boolean.valueOf(s.symbol));
                return;
            }
            else if (locals.containsKey(s)) mv.loadLocal(locals.get(s));
            else if (args.contains(s)) mv.loadArg(args.indexOf(s));
            else push(s);
            topOfStack = typeOf(s);
        }

        Type typeOf(Symbol s) {
            if (locals.containsKey(s)) return mv.getLocalType(locals.get(s));
            else if (args.contains(s)) return argTypes.get(args.indexOf(s));
            return getType(Symbol.class);
        }

        void loadArgArray(List<?> args) {
            mv.push(args.size());
            mv.newArray(getType(Object.class));

            for (int i = 0; i < args.size(); i++) {
                mv.dup();
                mv.push(i);
                compile(args.get(i), false);
                box();
                mv.arrayStore(getType(Object.class));
            }
            topOfStack(Object[].class);
        }

        void push(Symbol kl) {
            mv.invokeDynamic(toBytecodeName(kl.symbol), desc(Symbol.class), symbolBSM);
            topOfStack(Symbol.class);
        }

        void push(Handle handle) {
            mv.push(handle);
            topOfStack(MethodHandle.class);
        }

        void push(Class<?> aClass, Object kl) throws Throwable {
            aClass = asPrimitiveType(aClass);
            mh(mv.getClass(), "push", aClass).invoke(mv, kl);
            topOfStack(aClass);
        }

        void box() {
            Type maybePrimitive = topOfStack;
            mv.valueOf(maybePrimitive);
            topOfStack = boxedType(maybePrimitive);
        }

        void unbox(Type type) {
            mv.unbox(type);
            topOfStack = type;
        }

        void popStack() {
            if (topOfStack.getSize() == 1) mv.pop(); else mv.pop2();
        }

        void maybeCast(Class<?> type) {
            if (!getType(type).equals(topOfStack)) mv.checkCast(getType(type));
            topOfStack(type);
        }

        void topOfStack(Class<?> aClass) {
            topOfStack = getType(aClass);
        }

        public <T> Class<T> load(String source, Class<T> anInterface) throws Exception {
            try {
                cw = classWriter(className, anInterface);
                cw.visitSource(source, null);
                constructor();
                Method sam = findSAM(anInterface);
                List<Type> types = vec(stream(sam.getParameterTypes()).map(Type::getType));
                method(ACC_PUBLIC, intern(sam.getName()), toBytecodeName(sam.getName()), getType(sam.getReturnType()), types);
                bytes = cw.toByteArray();
                if (booleanProperty("*debug-asm*")) printASM(bytes, sam);
                //noinspection unchecked
                return (Class<T>) loader.loadClass(bytes);
            } catch (VerifyError e) {
                printASM(bytes, null);
                throw e;
            }
        }

        void method(int modifiers, Symbol name, String bytecodeName, Type returnType, List<Type> argumentTypes) {
            this.self = name;
            this.argTypes = argumentTypes;
            this.method = method(bytecodeName, desc(returnType, argumentTypes));
            mv = generator(modifiers, method);
            recur = mv.newLabel();
            mv.visitLabel(recur);
            compile(kl);
            if (isPrimitive(topOfStack)) box();
            mv.returnValue();
            mv.endMethod();
        }

        void constructor() {
            GeneratorAdapter ctor = generator(ACC_PUBLIC, method("<init>", desc(void.class)));
            ctor.loadThis();
            ctor.invokeConstructor(getType(Object.class), method("<init>", desc(void.class)));
            ctor.returnValue();
            ctor.endMethod();
        }

        void bindTo(Handle handle, Object arg) {
            push(handle);
            compile(arg, false);
            box();
            bindTo();
        }

        void bindTo() {
            mv.invokeStatic(getType(RT.class), method("bindTo",
                    desc(MethodHandle.class, MethodHandle.class, Object.class)));
            topOfStack(MethodHandle.class);
        }

        void insertArgs(int pos, List<?> args) {
            if (args.isEmpty()) return;
            mv.push(pos);
            loadArgArray(args);
            mv.invokeStatic(getType(MethodHandles.class), method("insertArguments",
                    desc(MethodHandle.class, MethodHandle.class, int.class, Object[].class)));
            topOfStack(MethodHandle.class);
        }

        static void printASM(byte[] bytes, Method method) {
            ASMifier asm = new ASMifier();
            PrintWriter pw = new PrintWriter(err);
            TraceClassVisitor printer = new TraceClassVisitor(null, asm, pw);
            if (method == null)
               new ClassReader(bytes).accept(printer, SKIP_DEBUG);
            else {
                ClassNode cn = new ClassNode();
                new ClassReader(bytes).accept(cn, SKIP_DEBUG);
                find(cn.methods.stream(), mn -> mn.name.equals(method.getName())).accept(printer);
                asm.print(pw);
                pw.flush();
            }
        }

        static Unsafe unsafe() {
            try {
                Field unsafe = Unsafe.class.getDeclaredField("theUnsafe");
                unsafe.setAccessible(true);
                return (Unsafe) unsafe.get(null);
            } catch (Exception e) {
                throw uncheck(e);
            }
        }
    }

    static void debug(String format, Object... args) {
        if (isDebug()) err.println(format(format,
                stream(args).map(o -> o.getClass() == Object[].class ? deepToString((Object[]) o) : o).toArray()));
    }

    @SafeVarargs
    static <T> List<T> list(T... elements) {
        return new ArrayList<>(asList(elements));
    }

    @SuppressWarnings("unchecked")
    static <T> List<T> vec(Stream<T> stream) {
        return (List<T>) stream.collect(Collectors.toList());
    }

    @SuppressWarnings("unchecked")
    static <T> Set<T> hashSet(Stream<T> stream) {
        return (Set<T>) stream.collect(toSet());
    }

    static <T> T find(Stream<T> stream, Predicate<? super T> predicate) {
        return stream.filter(predicate).findFirst().orElse((T) null);
    }

    static <T, R> R some(Stream<T> stream, Function<? super T, ? extends R> mapper) {
        return stream.map(mapper).filter(nonNull().or(isSame(true))).findFirst().orElse((R) null);
    }

    static <T, R> Stream<R> mapcat(Stream<? extends T> source, Function<? super T, ? extends Stream<R>> mapper) {
        return source.explode((Stream.Downstream<R> downstream, T x) ->  downstream.send(mapper.apply(x)));
    }

    static <T, C extends Collection<T>> C into(C a, Collection<? extends T> b) {
        Collector<Object,? extends Collection<Object>> collector = a instanceof Set ? toSet() : Collectors.toList();
        //noinspection unchecked
        return (C) concat(a.stream(), b.stream()).collect(collector);
    }

    static <T, C extends Collection<T>> C conj(C c, Object x) {
        //noinspection unchecked
        return into(c, singleton((T) x));
    }

    static <T> List<T> cons(T x, List<T> y) {
        return into(singletonList(x), y);
    }

    static <T> boolean all(Collection<T> as, Collection<T> bs, BiPredicate<T, T> predicate) {
        return zip(as.stream(), bs.stream(), predicate::test).allMatch(isEqual(true));
    }

    static <T> T find(Collection<T> as, Collection<T> bs, BiPredicate<T, T> predicate) {
        return zip(as.stream(), bs.stream(), (x, y) -> predicate.test(x, y) ? x : null)
                .filter(nonNull()).findFirst().orElse((T) null);
    }

    static Object hd(List list) {
        return list.isEmpty() ? list : list.get(0);
    }

    static <T> List<T> tl(List<T> list) {
        return list.isEmpty() ? list : list.subList(1, list.size());
    }

    static <T> T hd(T[] array) {
        return array[0];
    }

    static <T> T[] tl(T[] array) {
        if (array.length == 0) return array;
        return copyOfRange(array, 1, array.length);
    }

    static RuntimeException uncheck(Throwable t) {
        return uncheckAndThrow(t);
   }

    static <T extends Throwable> T uncheckAndThrow(Throwable t) throws T {
        //noinspection unchecked
        throw (T) t;
    }
}
