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
import java.util.ArrayList;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.function.*;
import java.util.jar.Manifest;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.Streams;

import static java.lang.Character.isUpperCase;
import static java.lang.ClassLoader.getSystemClassLoader;
import static java.lang.Double.doubleToLongBits;
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
import static java.util.Objects.deepEquals;
import static java.util.function.Predicates.*;
import static java.util.jar.Attributes.Name.IMPLEMENTATION_VERSION;
import static org.objectweb.asm.ClassReader.SKIP_DEBUG;
import static org.objectweb.asm.ClassWriter.COMPUTE_FRAMES;
import static org.objectweb.asm.Type.*;
import static shen.Shen.KLReader.lines;
import static shen.Shen.KLReader.read;
import static shen.Shen.Primitives.cons;
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

    static Map<String, Symbol> symbols = new HashMap<>();

    static {
        set("*language*", "Java");
        set("*implementation*", format("[jvm %s]", getProperty("java.version")));
        set("*porters*", "Håkan Råberg");
        set("*port*", version());
        set("*stinput*", in);
        set("*stoutput*", out);
        set("*debug*", Boolean.valueOf(getProperty("shen.debug", "false")));
        set("*debug-asm*", Boolean.valueOf(getProperty("shen.debug.asm", "false")));
        set("*compile-path*", getProperty("shen.compile.path", "target/classes"));
        set("*home-directory*", getProperty("user.dir"));

        register(Primitives.class, RT::defun);
        register(Overrides.class, RT::override);

        op("=", (BiPredicate) Objects::equals,
                (IIPredicate) (left, right) -> left == right,
                (LLPredicate) (left, right) -> left == right,
                (DDPredicate) (left, right) -> left == right);
        op("+", (IntBinaryOperator) (left, right) -> left + right,
                (LongBinaryOperator) (left, right) -> left + right,
                (DoubleBinaryOperator) (left, right) -> left + right);
        op("-", (IntBinaryOperator) (left, right) -> left - right,
                (LongBinaryOperator) (left, right) -> left - right,
                (DoubleBinaryOperator) (left, right) -> left - right);
        op("*", (IntBinaryOperator) (left, right) -> left * right,
                (LongBinaryOperator) (left, right) -> left * right,
                (DoubleBinaryOperator) (left, right) -> left * right);
        op("/", (DoubleBinaryOperator) (left, right) -> {
            if (right == 0) throw new ArithmeticException("Division by zero");
            return left / right;
        });

        op("<", (IIPredicate) (left, right) -> left < right,
                (LLPredicate) (left, right) -> left < right,
                (DDPredicate) (left, right) -> left < right);
        op("<=", (IIPredicate) (left, right) -> left <= right,
                (LLPredicate) (left, right) -> left <= right,
                (DDPredicate) (left, right) -> left <= right);
        op(">", (IIPredicate) (left, right) -> left > right,
                (LLPredicate) (left, right) -> left > right,
                (DDPredicate) (left, right) -> left > right);
        op(">=", (IIPredicate) (left, right) -> left >= right,
                (LLPredicate) (left, right) -> left >= right,
                (DDPredicate) (left, right) -> left >= right);

        asList(Math.class, System.class).forEach(Primitives::KL_import);
    }

    interface IIPredicate { boolean test(int a, int b); }
    interface LLPredicate { boolean test(long a, long b); }
    interface DDPredicate { boolean test(double a, double b); }

    public static class Symbol {
        public final String symbol;
        public List<MethodHandle> fn = new ArrayList<>();
        public SwitchPoint fnGuard = new SwitchPoint();
        public Object var;
        public long primVar;
        public int tag = Type.OBJECT;

        public Symbol(String symbol) {
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

        public void tag(int tag) {
            if (this.tag != tag) {
                debug("retagging %s from %s to %s", this, this.tag,  tag);
                this.tag = tag;
                if (tag != Type.OBJECT) var = null;
            }
        }

        public boolean hasTag(int tag) {
            return this.tag == tag;
        }

        public boolean equals(Object o) {
            //noinspection StringEquality
            return o instanceof Symbol && symbol == ((Symbol) o).symbol;

        }

        public int hashCode() {
            return symbol.hashCode();
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

        public String toString() {
            return "[" + car + " | " + cdr + "]";
        }
    }

    public static class Primitives {
        public static Class KL_import(Symbol s) throws ClassNotFoundException {
            Class aClass = Class.forName(s.symbol);
            return set(intern(aClass.getSimpleName()), aClass);
        }

        static Class KL_import(Class type) {
            try {
                return KL_import(intern(type.getName()));
            } catch (ClassNotFoundException e) {
                throw new RuntimeException(e);
            }
        }

        public static Object cons(Object x, Object y) {
            return new Cons(x, y);
        }

        public static List<Object> cons(Object x, List<Object> y) {
            y = new ArrayList<Object>(y);
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

        public static Object hd(List list) {
            return list.isEmpty() ? list : list.get(0);
        }

        public static <T> List<T> tl(List<T> list) {
            if (list.isEmpty()) return list;
            list = new ArrayList<>(list);
            list.remove(0);
            return list;
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
            if (array.length == 0) return array;
            return copyOfRange(array, 1, array.length);
        }

        public static String str(Object x) {
            if (consP(x)) throw new IllegalArgumentException(x + " is not an atom; str cannot convert it to a string.");
            if (x != null && x.getClass() == Object[].class) return deepToString((Object[]) x);
            return String.valueOf(x);
        }

        public static String pos(String x, int n) {
            return str(x.charAt(n));
        }

        public static String tlstr(String x) {
            return x.substring(1);
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
            return list();
        }

        static long startTime = System.currentTimeMillis();
        public static double get_time(Symbol time) {
            switch (time.symbol) {
                case "run": return (currentTimeMillis() - startTime) / 1000;
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
            x.tag(Type.OBJECT);
            return (T) (x.var = y);
        }

        public static boolean set(Symbol x, boolean y) {
            x.tag(Type.BOOLEAN);
            x.primVar = y ? 1 : 0;
            return y;
        }

        public static int set(Symbol x, int y) {
            x.tag(Type.INT);
            x.primVar = y;
            return y;
        }

        public static long set(Symbol x, long y) {
            x.tag(Type.LONG);
            return x.primVar = y;
        }

        public static double set(Symbol x, double y) {
            x.tag(Type.DOUBLE);
            x.primVar = doubleToLongBits(y);
            return y;
        }

        static <T> T set(String x, T y) {
            return set(intern(x), y);
        }

        static Boolean set(String x, Boolean y) {
            return set(intern(x), y);
        }

        public static MethodHandle function(Symbol x) throws IllegalAccessException {
            MethodHandle fn = x.fn.get(0);
            if (x.fn.size() > 1) {
                int arity = fn.type().parameterCount();
                return linker(new MutableCallSite(genericMethodType(arity)), toBytecodeName(x.symbol), arity);
            }
            return fn;
        }

        static MethodHandle function(String x) throws IllegalAccessException {
            return function(intern(x));
        }

        public static Object eval_kl(Object kl) throws Throwable {
            return new Compiler(kl).load("__eval__", Callable.class).newInstance().call();
        }
    }

    public static class Overrides {
        public static boolean variableP(Object x) {
            return x instanceof Symbol && isUpperCase(((Symbol) x).symbol.charAt(0));
        }

        public static boolean booleanP(Object x) {
            return x instanceof Boolean;
        }

        public static boolean elementP(Object x, Collection z) {
            return z.contains(x);
        }

        public static Object[] ATp(Object x, Object y) {
            return new Object[] {intern("shen-tuple"), x, y};
        }

        public static Object shen_compose(List fs, Object x) throws Throwable {
            for (Object f : fs)
                x = apply(f, x);
            return x;
        }

        public static Object shen_walk(Object x, Object y) throws Throwable {
            if (y instanceof List)
                //noinspection unchecked
                return apply(x, toList(((List<Object>) y).stream().map((Object z) -> {
                    try {
                        return shen_walk(x, z);
                    } catch (Throwable t) {
                        throw new RuntimeException(t);
                    }
                })));
            return apply(x, y);
        }

        public static Object macroexpand(Object x) throws Throwable {
            Object y = shen_compose((List) intern("*macros*").value(), x);
            if (x.equals(y)) return x;
            return shen_walk(intern("macroexpand"), y);
        }
    }

    static boolean isDebug() {
        return booleanProperty("*debug*");
    }

    static boolean booleanProperty(String property) {
        return intern(property).primVar == 1;
    }

    public static Object eval(String shen) throws Throwable {
        return eval_kl(read(new StringReader(shen)).get(0));
    }

    static void install() throws Throwable {
        long start = currentTimeMillis();
        for (String file : asList("sys", "writer", "core", "prolog", "yacc", "declarations", "load",
                "macros", "reader", "sequent", "toplevel", "track", "t-star", "types"))
            load("klambda/" + file, Callable.class).newInstance().call();
        debug("install took %f s", (currentTimeMillis() - start) / 1000.0);
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
            if (!compilePath.isDirectory() || compilePath.mkdirs()) throw new IOException("could not make directory: " + compilePath);
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
        } catch (Exception ignored) {
        }
        return version != null ? version : "<unknown>";
    }

    public static class KLReader {
        static Map<Object, Integer> lines = new IdentityHashMap<>();
        static int currentLine;

        static List<Object> read(Reader reader) throws Exception {
            lines.clear();
            currentLine = 1;
            //noinspection unchecked
            return tokenizeAll(new Scanner(reader).useDelimiter("(\\s|\\)|\")"));
        }

        static Object tokenize(Scanner sc) throws Exception {
            whitespace(sc);
            if (find(sc, "\\(")) return tokenizeAll(sc);
            if (find(sc, "\"")) return nextString(sc);
            if (find(sc, "\\)")) return null;
            if (sc.hasNextBoolean()) return sc.nextBoolean();
            if (sc.hasNextInt()) return sc.nextInt();
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

        static List tokenizeAll(Scanner sc) throws Exception {
            List<Object> list = list();
            lines.put(list, currentLine);
            Object x;
            while ((x = tokenize(sc)) != null) list.add(x);
            return list;
        }
    }

    public static class RT {
        static Lookup lookup = lookup();
        static Map<Symbol, MethodHandle> overrides = new HashMap<>();

        static MethodHandle
                link = mh(RT.class, "link"), proxy = mh(RT.class, "proxy"), hasTag = mh(Symbol.class, "hasTag"),
                value = mh(Symbol.class, "value"), primVar = field(Symbol.class, "primVar"),
                booleanValue = explicitCastArguments(primVar, methodType(boolean.class, Symbol.class)),
                intValue = explicitCastArguments(primVar, methodType(int.class, Symbol.class)),
                doubleValue = filterReturnValue(primVar, mh(Double.class, "longBitsToDouble"));

        @SafeVarargs
        public static <T> List<T> list(T... elements) {
            return new ArrayList<>(asList(elements));
        }

        public static Object value(MutableCallSite site, Symbol symbol) throws Throwable {
            MethodHandle hasTag = insertArguments(RT.hasTag, 1, symbol.tag);
            site.setTarget(guardWithTest(hasTag, value(symbol).asType(site.type()), site.getTarget()));
            return site.getTarget().invoke(symbol);
        }

        static MethodHandle value(Symbol symbol) throws Exception {
            switch (symbol.tag) {
                case Type.BOOLEAN: return booleanValue;
                case Type.INT: return intValue;
                case Type.LONG: return primVar;
                case Type.DOUBLE: return doubleValue;
            }
            return value;
        }

        public static Object link(MutableCallSite site, String name, Object... args) throws Throwable {
            name = toSourceName(name);
            debug("LINKING: %s%s %s", name, site.type(), args);
            List<Class<?>> actualTypes = toList(stream(args).map(Object::getClass));
            debug("actual types: %s", actualTypes);
            Symbol symbol = intern(name);
            debug("candidates: %s", symbol.fn);

            if (symbol.fn.isEmpty()) {
                MethodHandle java = javaCall(site, name, site.type(), args);
                if (java != null) {
                    debug("calling java: %s", java);
                    site.setTarget(java.asType(site.type()));
                    return java.invokeWithArguments(args);
                }
                throw new NoSuchMethodException("undefined function " + name + site.type()
                        + (symbol.fn.isEmpty() ?  "" : " in " + toList(symbol.fn.stream().map(MethodHandle::type))));
            }

            int arity = symbol.fn.get(0).type().parameterCount();
            if (arity > args.length) {
                MethodHandle partial = linker(new MutableCallSite(genericMethodType(arity)), name, arity);
                partial = insertArguments(partial, 0, args);
                debug("partial: %s", partial);
                return partial;
            }

            MethodHandle match = find(symbol.fn.stream(), f -> f.type().wrap().parameterList().equals(actualTypes));
            if (match == null)
                match = symbol.fn.stream()
                    .filter(f -> canCast(actualTypes, f.type().parameterList()))
                    .min((x, y) -> without(y.type().parameterList(), Object.class).size()
                            - without(x.type().parameterList(), Object.class).size()).get();
            debug("selected: %s", match);
            site.setTarget(symbol.fnGuard.guardWithTest(relinkOnClassCast(site, match), site.getTarget()));
            return insertArguments(match.asType(match.type().changeReturnType(Object.class)), 0, args).invokeExact();
        }

        static MethodHandle relinkOnClassCast(MutableCallSite site, MethodHandle fn) {
            return catchException(fn.asType(site.type()), ClassCastException.class, dropArguments(site.getTarget(), 0, Exception.class));
        }

        static MethodHandle javaCall(MutableCallSite site, String name, MethodType type, Object... args) throws Exception {
            if (name.endsWith(".")) {
                Class aClass = intern(name.substring(0, name.length() - 1)).value();
                if (aClass != null)
                    return findJavaMethod(type, aClass.getName(), aClass.getConstructors());
            }
            if (name.startsWith("."))
                return relinkOnClassCast(site, findJavaMethod(type, name.substring(1, name.length()), args[0].getClass().getMethods()));
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

        static MethodHandle convertMethodHandles(MethodHandle method) throws IllegalAccessException {
            MethodHandle[] filters = new MethodHandle[method.type().parameterCount()];
            for (int i = 0; i < method.type().parameterCount() - (method.isVarargsCollector() ? 1 : 0); i++)
                if (isSAM(method.type().parameterType(i)))
                    filters[i] = proxy.bindTo(findSAM(method.type().parameterType(i)))
                            .asType(methodType(method.type().parameterType(i), Object.class));
            return filterArguments(method, 0, filters);
        }

        static <T extends Executable> MethodHandle findJavaMethod(MethodType type, String method, T[] methods) {
            return some(stream(methods), m -> {
                try {
                    if (m.getName().equals(method)) {
                        MethodHandle mh = (m instanceof Method) ? lookup.unreflect((Method) m) : lookup.unreflectConstructor((Constructor) m);
                        mh.asType(type);
                        return convertMethodHandles(mh);
                    }
                } catch (Exception ignore) {
                }
                return null;
            });
        }

        static MethodHandle linker(MutableCallSite site, String name, int arity) throws IllegalAccessException {
            return insertArguments(link, 0, site, name).asCollector(Object[].class, arity);
        }

        public static CallSite invokeBSM(Lookup lookup, String name, MethodType type) throws IllegalAccessException {
            MutableCallSite site = new MutableCallSite(type);
            site.setTarget(linker(site, name, type.parameterCount()).asType(type));
            return site;
        }

        public static CallSite symbolBSM(Lookup lookup, String name, MethodType type) {
            return new ConstantCallSite(constant(Symbol.class, intern(toSourceName(name))));
        }

        public static CallSite valueBSM(Lookup lookup, String name, MethodType type) throws Exception {
            MutableCallSite site = new MutableCallSite(type);
            site.setTarget(mh(RT.class, "value").bindTo(site).asType(type));
            return site;
        }

        static void debug(String format, Object... args) {
            if (isDebug()) System.err.println(format(format,
                    stream(args).map(o -> o.getClass() == Object[].class ? deepToString((Object[]) o) : o).toArray()));
        }

        static MethodHandle mh(Class<?> aClass, String name, Class... types) {
            try {
                return lookup.unreflect(find(stream(aClass.getMethods()), m -> m.getName().equals(name)
                        && (types.length == 0 || deepEquals(m.getParameterTypes(), types))));
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            }
        }

        static MethodHandle field(Class<?> aClass, String name) {
            try {
                return lookup.unreflectGetter(aClass.getField(name));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        static String desc(Class<?> returnType, Class<?>... argumentTypes) {
            return methodType(returnType, argumentTypes).toMethodDescriptorString();
        }

        static String desc(Type returnType, List<Type> argumentTypes) {
            return getMethodDescriptor(returnType, argumentTypes.toArray(new Type[argumentTypes.size()]));
        }

        static Handle handle(MethodHandle handle) throws ReflectiveOperationException {
            MethodHandleInfo info = new MethodHandleInfo(handle);
            return handle(getInternalName(info.getDeclaringClass()), info.getName(), handle.type().toMethodDescriptorString());
        }

        static Handle handle(String className, String name, String desc) {
            return new Handle(Opcodes.H_INVOKESTATIC, className, name, desc);
        }

        static Object uncurry(Object chain, Object... args) throws Throwable {
            for (Object arg : args)
                chain = ((MethodHandle) chain).invokeExact(arg);
            return chain;
        }

        static boolean isLambda(MethodHandle fn) {
            return fn.type().parameterCount() == 1 && !fn.isVarargsCollector() && Object.class == fn.type().parameterType(0);
        }

        static Type boxedType(Type type) {
            if (!isPrimitive(type)) return type;
            return getType(forBasicType(type.getDescriptor().charAt(0)).wrapperType());
        }

        static boolean isPrimitive(Type type) {
            return type.getSort() < ARRAY;
        }

        static boolean canCast(Class<?> a, Class<?> b) {
            return a == b || b.isAssignableFrom(a) || canWiden(a, b);
        }

        static boolean canWiden(Class<?> a, Class<?> b) {
            return wrapper(b).isNumeric() && wrapper(b).isConvertibleFrom(wrapper(a));
        }

        static Wrapper wrapper(Class<?> type) {
            if (isPrimitiveType(type)) return forPrimitiveType(type);
            if (isWrapperType(type)) return forWrapperType(type);
            return Wrapper.OBJECT;
        }

        static boolean canCast(List<Class<?>> as, List<Class<?>> bs) {
            for (int i = 0; i < as.size(); i++)
                if (!canCast(as.get(i), bs.get(i))) return false;
            return true;
        }

        public static Symbol defun(Symbol name, MethodHandle fn) throws Throwable {
            if (overrides.containsKey(name))
                fn  = overrides.get(name);
            name.fn.clear();
            name.fn.add(fn);
            invalidateAll(new SwitchPoint[] {name.fnGuard});
            name.fnGuard = new SwitchPoint();
            return name;
        }

        static void op(String name, Object... op) {
            intern(name).fn.addAll(toList(stream(op).map(RT::findSAM)));
        }

        static void register(Class<?> aClass, Block<? super Method> hook) {
            stream(aClass.getDeclaredMethods()).filter(m -> isPublic(m.getModifiers())).forEach(hook);
        }

        static void override(Method m) {
            try {
                overrides.put(intern(unscramble(m.getName())), lookup.unreflect(m));
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
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

        public static Object apply(Object target, Object... args) throws Throwable {
            return apply(target instanceof Symbol ? function((Symbol) target) : (MethodHandle) target, args);
        }

        public static Object apply(MethodHandle fn, Object... args) throws Throwable {
            if (isLambda(fn)) return uncurry(fn, args);

            MethodType targetType = methodType(Object.class, toList(stream(args).map(Object::getClass)));
            int nonVarargs = fn.isVarargsCollector() ? fn.type().parameterCount() - 1 : fn.type().parameterCount();
            if (nonVarargs > args.length) {
                MethodHandle partial = insertArguments(fn.asType(fn.type()
                        .dropParameterTypes(0, targetType.parameterCount())
                        .insertParameterTypes(0, targetType.parameterArray())), 0, args);
                return fn.isVarargsCollector() ? partial.asVarargsCollector(fn.type().parameterType(nonVarargs)) : partial;
            }
            return insertArguments(fn.asType(targetType), 0, args).invokeExact();
        }

        public static MethodHandle bindTo(MethodHandle fn, Object arg) {
            return fn.isVarargsCollector() ?
                    insertArguments(fn, 0, arg).asVarargsCollector(fn.type().parameterType(fn.type().parameterCount() - 1)) :
                    insertArguments(fn, 0, arg);
        }

        public static boolean or(boolean x, boolean... clauses) throws Exception {
            if (x) return true;
            for (boolean b : clauses) if (b) return true;
            return false;
        }

        public static boolean and(boolean x, boolean... clauses) throws Exception {
            if (!x) return false;
            for (boolean b : clauses) if (!b) return false;
            return true;
        }

        static String unscramble(String s) {
            return toSourceName(s).replaceAll("_", "-").replaceAll("^KL-", "")
                    .replaceAll("GT", ">").replaceAll("LT", "<")
                    .replaceAll("EX$", "!").replaceAll("P$", "?").replaceAll("^AT", "@");
        }

        static MethodHandle findSAM(Object lambda) {
            try {
                return lookup.unreflect(findSAM(lambda.getClass())).bindTo(lambda);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }

        static Method findSAM(Class<?> lambda) {
            List<Method> methods = toList(stream(lambda.getDeclaredMethods()).filter(m -> !m.isSynthetic()));
            return methods.size() == 1 ? methods.get(0) : null;
        }

        static boolean isSAM(Class<?> aClass) {
            return findSAM(aClass) != null;
        }
    }

    public static class Compiler implements Opcodes {
        static AnonymousClassLoader loader = AnonymousClassLoader.make(unsafe(), RT.class);
        static Map<Symbol, MethodHandle> macros = new HashMap<>();
        static List<Class<?>> literals =
                asList(Double.class, Integer.class, Long.class, String.class, Boolean.class, Handle.class);

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
            cw.visit(V1_7, ACC_PUBLIC, name, null, getInternalName(Object.class), new String[] {getInternalName(anInterface)});
            return cw;
        }

        static org.objectweb.asm.commons.Method method(String name, String desc) {
            return new org.objectweb.asm.commons.Method(name, desc);
        }

        static void macro(Method m) {
            try {
                macros.put(intern(unscramble(m.getName())), lookup.unreflect(m));
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
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
            try {
                Class literalClass = find(literals.stream(), c -> c.isInstance(kl));
                if (literalClass != null) push(literalClass, kl);
                else if (kl instanceof Symbol) symbol((Symbol) kl);
                else if (kl instanceof List) {
                    @SuppressWarnings("unchecked")
                    List<Object> list = (List) kl;
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
                            apply(tl(list));
                        }
                    }
                } else
                    throw new IllegalArgumentException("Cannot compile: " + kl + " (" + kl.getClass() + ")");
                handlePrimitives(returnType);
                return topOfStack;
            } catch (RuntimeException | Error e) {
                throw e;
            } catch (Throwable t) {
                throw new RuntimeException(t);
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
            RT.bindTo(RT.bindTo(RT.bindTo(macros.get(s), new Macros()), tail), returnType).invokeWithArguments(args);
        }

        void indy(Symbol s, List<Object> args, Type returnType, boolean tail) throws ReflectiveOperationException {
            List<Type> argumentTypes = toList(args.stream().map(o -> compile(o, false)));

            if (isSelfCall(s, args)) {
                if (tail) {
                    debug("recur: %s", s);
                    recur(argumentTypes);
                    return;
                } else debug("can only recur from tail position: %s", s);
            }
            mv.invokeDynamic(toBytecodeName(s.symbol), desc(returnType, argumentTypes), handle(mh(RT.class, "invokeBSM")));
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

        void apply(List<Object> args) {
            box();
            loadArgArray(args);
            mv.invokeStatic(getType(RT.class), method("apply", desc(Object.class, Object.class, Object[].class)));
            topOfStack(Object.class);
        }

        class Macros {
            public void trap_error(boolean tail, Type returnType, Object x, Object f) throws Throwable {
                Label start = mv.newLabel();
                Label end = mv.newLabel();
                Label after = mv.newLabel();

                mv.visitLabel(start);
                compile(x, returnType, false);
                mv.goTo(after);
                mv.visitLabel(end);

                mv.catchException(start, end, getType(Exception.class));
                compile(f, false);
                maybeCast(MethodHandle.class);
                mv.swap();
                bindTo();

                mv.invokeVirtual(getType(MethodHandle.class), method("invoke", desc(Object.class)));
                if (isPrimitive(returnType)) unbox(returnType);
                else topOfStack(Object.class);
                mv.visitLabel(after);
            }

            public void KL_if(boolean tail, Type returnType, Object test, Object then, Object _else) throws Exception {
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
                    bindTo(handle(RT.mh(RT.class, "or")), x);
                else {
                    KL_if(tail, BOOLEAN_TYPE, x, true, (clauses.length > 1 ? cons(intern("or"), list(clauses)) : clauses[0]));
                    if (!isPrimitive(returnType)) mv.box(returnType);
                }
            }

            public void and(boolean tail, Type returnType, Object x, Object... clauses) throws Exception {
                if (clauses.length == 0)
                    bindTo(handle(RT.mh(RT.class, "and")), x);
                else {
                    KL_if(tail, BOOLEAN_TYPE, x, (clauses.length > 1 ? cons(intern("and"), list(clauses)) : clauses[0]), false);
                    if (!isPrimitive(returnType)) mv.box(returnType);
                }
            }

            public void value(boolean tail, Type returnType, Object x) throws Throwable {
                compile(x, false);
                maybeCast(Symbol.class);
                mv.invokeDynamic("value", desc(Object.class, Symbol.class), handle(mh(RT.class, "valueBSM")));
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
        }

        void fn(String name, Object shen, Symbol... args) throws Throwable {
            String bytecodeName = toBytecodeName(name) + "_" + id++;
            List<Symbol> scope = closesOver(new HashSet<>(asList(args)), shen);
            scope.retainAll(concat(locals.keySet(), this.args));

            List<Type> types = toList(scope.stream().map(this::typeOf));
            for (Symbol ignore : args) types.add(getType(Object.class));

            push(handle(className, bytecodeName, desc(getType(Object.class), types)));
            insertArgs(0, scope);

            scope.addAll(asList(args));
            Compiler fn = new Compiler(cw, className, shen, scope.toArray(new Symbol[scope.size()]));
            fn.method(ACC_PUBLIC | ACC_STATIC, intern(name), bytecodeName, getType(Object.class), types);
        }

        @SuppressWarnings({"unchecked"})
        List<Symbol> closesOver(Set<Symbol> scope, Object shen) {
            if (shen instanceof Symbol && !scope.contains(shen))
                return list((Symbol) shen);
            if (shen instanceof List) {
                List<Object> list = (List) shen;
                if (!list.isEmpty())
                    if (intern("let").equals(hd(list)))
                        return concat(closesOver(new HashSet<>(scope), list.get(2)),
                                closesOver(new HashSet<>(concat(asList((Symbol) list.get(1)), scope)), list.get(3)));
                    if (intern("lambda").equals(hd(list)))
                        return closesOver(new HashSet<>(concat(asList((Symbol) list.get(1)), scope)), list.get(2));
                    if (intern("defun").equals(hd(list)))
                        return closesOver(new HashSet<>(concat((List<Symbol>) list.get(2), scope)), list.get(3));
                    return toList(mapcat(list.stream(), o -> closesOver(scope, o)));
            }
            return list();
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

        void push(Symbol kl) throws ReflectiveOperationException {
            mv.invokeDynamic(toBytecodeName(kl.symbol), desc(Symbol.class), handle(mh(RT.class, "symbolBSM")));
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
                List<Type> types = toList(stream(sam.getParameterTypes()).map(Type::getType));
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
            mv = generator(modifiers, method(bytecodeName, desc(returnType, argumentTypes)));
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
            PrintWriter pw = new PrintWriter(System.err);
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
                throw new RuntimeException(e);
            }
        }
    }

    @SuppressWarnings("unchecked")
    static <T> List<T> toList(Stream<T> stream) {
        return (List<T>) stream.collect(Collectors.toList());
    }

    static <T> T find(Stream<T> stream, Predicate<? super T> predicate) {
        return stream.filter(predicate).findFirst().orElse((T) null);
    }

    static <T, R> R some(Stream<T> stream, Function<? super T, ? extends R> mapper) {
        return stream.map(mapper).filter(nonNull().or(isSame(true))).findFirst().orElse((R) null);
    }

    static <T, R> Stream<R> mapcat(Stream<? extends T> source, Function<? super T, ? extends Collection<R>> mapper) {
        //noinspection Convert2MethodRef
        return source.map(mapper).reduce(new ArrayList<R>(), (x, y) -> concat(x, y)).stream();
    }

    static <T> List<T> concat(Collection<? extends T> a, Collection<? extends T> b) {
        return toList(Streams.concat(a.stream(), b.stream()));
    }

    static <T> List<T> without(Collection<T> x, T y) {
        return toList(x.stream().filter(isEqual(y).negate()));
    }
}
