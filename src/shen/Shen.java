package shen;

import org.objectweb.asm.*;
import org.objectweb.asm.commons.GeneratorAdapter;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import sun.invoke.util.Wrapper;

import java.io.*;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.invoke.*;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.function.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.Streams;

import static java.lang.Double.doubleToLongBits;
import static java.lang.String.format;
import static java.lang.System.*;
import static java.lang.invoke.MethodHandles.*;
import static java.lang.invoke.MethodType.*;
import static java.lang.invoke.SwitchPoint.invalidateAll;
import static java.lang.reflect.Modifier.isPublic;
import static java.util.Arrays.*;
import static java.util.Objects.deepEquals;
import static java.util.function.Predicates.*;
import static org.objectweb.asm.ClassWriter.COMPUTE_FRAMES;
import static org.objectweb.asm.ClassWriter.COMPUTE_MAXS;
import static org.objectweb.asm.Type.*;
import static shen.Shen.Compiler.*;
import static shen.Shen.KLReader.read;
import static sun.invoke.util.BytecodeName.toBytecodeName;
import static sun.invoke.util.BytecodeName.toSourceName;
import static sun.invoke.util.Wrapper.*;

@SuppressWarnings({"UnusedDeclaration", "Convert2Diamond"})
public class Shen {
    public static void main(String... args) throws Throwable {
        install();
        repl();
    }

    static Compiler loader = new Compiler();
    static Lookup lookup = lookup();
    static Map<String, Symbol> symbols = new HashMap<>();
    static Map<String, Class> imports = new HashMap<>();

    static {
        set("*language*", "Java");
        set("*implementation*", format("[jvm %s]", getProperty("java.version")));
        set("*porters*", "Håkan Råberg");
        set("*stinput*", in);
        set("*stoutput*", out);
        set("*debug*", false);
        set("*home-directory*", getProperty("user.dir"));

        stream(Shen.class.getDeclaredMethods()).filter(m -> isPublic(m.getModifiers())).forEach(Shen::defun);

        op("=", (BiPredicate) Objects::deepEquals,
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

        asList(Math.class, System.class).forEach(Shen::KL_import);
    }

    interface IIPredicate { boolean test(int a, int b); }
    interface LLPredicate { boolean test(long a, long b); }
    interface DDPredicate { boolean test(double a, double b); }

    static void op(String name, Object... op) {
        intern(name).fn.addAll(toList(stream(op).map(Compiler::findSAM)));
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

    public static Class KL_import(Symbol s) throws ClassNotFoundException {
        return KL_import(Class.forName(s.symbol));
    }

    static Class KL_import(Class type) {
        imports.put(type.getSimpleName(), type);
        return type;
    }

    public static Object cons(Object x, Object y) {
        return new Cons(x, y);
    }

    public static List<Object> cons(Object x, List<Object> y) {
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
        return list.isEmpty() ? list : new ArrayList<>(list.subList(1, list.size()));
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
        return dropArguments(constant(x.getClass(), x), 0, Object.class);
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

        public Object value() {
            var.getClass();
            return var;
        }

        public void tag(int tag) {
            if (this.tag != tag) {
                debug("retagging " + this + " from " + this.tag + " to " + tag);
                this.tag = tag;
                if (tag != Type.OBJECT) var = null;
            }
        }

        public boolean hasTag(int tag) {
            return this.tag == tag;
        }
    }

    public static Symbol intern(String string) {
        if (!symbols.containsKey(string)) symbols.put(string, new Symbol(string));
        return symbols.get(string);
    }

    public static Object set(Symbol x, Object y) {
        x.tag(Type.OBJECT);
        return x.var = y;
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

    static Object set(String x, Object y) {
        return set(intern(x), y);
    }

    static Object value(String x) {
        return value(intern(x));
    }

    static Object value(Symbol x) {
        try {
            return Compiler.value(x).invoke(x);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public static MethodHandle function(Symbol x) throws IllegalAccessException {
        MethodHandle fn = x.fn.stream().findFirst().get();
        if (x.fn.size() > 1) {
            int arity = fn.type().parameterCount();
            return linker(new MutableCallSite(genericMethodType(arity)), scramble(x.symbol), arity);
        }
        return fn;
    }

    static MethodHandle function(String x) throws IllegalAccessException {
        return function(intern(x));
    }

    public static Object eval_kl(Object kl) {
        try {
            return new Code(kl).load(Callable.class).newInstance().call();
        } catch (Throwable t) {
            throw new IllegalArgumentException(kl.toString(), t);
        }
    }

    static Object load(Reader reader) throws Exception {
        debug("LOADING " + reader);
        //noinspection unchecked,RedundantCast
        return read(reader).stream().reduce(null, (BinaryOperator) (left, right) -> eval_kl(right));
    }

    static Object eval(String shen) throws Exception {
        return eval_kl(read(new StringReader(shen)).get(0));
    }

    static Object repl() throws Exception {
        return eval("(shen-shen)");
    }

    static void install() throws Exception {
        for (String file : asList("sys", "writer", "core", "prolog", "yacc", "declarations", "load",
                "macros", "reader", "sequent", "toplevel", "track", "t-star", "types"))
            try (Reader in = new InputStreamReader(loader.getResourceAsStream(file + ".kl"))) {
                load(in);
            }
    }

    public static class KLReader {
        static List read(Reader reader) throws Exception {
            return tokenizeAll(new Scanner(reader).useDelimiter("(\\s|\\)|\")"));
        }

        static Object tokenize(Scanner sc) throws Exception {
            if (find(sc, "\\(")) return tokenizeAll(sc);
            if (find(sc, "\"")) return nextString(sc);
            if (find(sc, "\\s")) return tokenize(sc);
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
    }

    public static class Compiler extends ClassLoader implements Opcodes {
        public Class<?> define(ClassNode cn) {
            ClassWriter cw = new ClassWriter(COMPUTE_FRAMES | COMPUTE_MAXS);
            cn.accept(cw);
            byte[] bytes = cw.toByteArray();
            return super.defineClass(cn.name.replaceAll("/", "."), bytes, 0, bytes.length);
        }

        public static Object value(MutableCallSite site, Symbol symbol) throws Throwable {
            MethodHandle hasTag = insertArguments(mh(Symbol.class, "hasTag"), 1, symbol.tag);
            site.setTarget(guardWithTest(hasTag, value(symbol).asType(site.type()), site.getTarget()));
            return site.getTarget().invoke(symbol);
        }

        static MethodHandle value(Symbol symbol) throws Exception {
            switch (symbol.tag) {
                case Type.INT: return explicitCastArguments(field(Symbol.class, "primVar"), methodType(int.class, Symbol.class));
                case Type.LONG: return field(Symbol.class, "primVar");
                case Type.DOUBLE: return filterReturnValue(field(Symbol.class, "primVar"), mh(Double.class, "longBitsToDouble"));
            }
            return mh(Symbol.class, "value");
        }

        public static Object link(MutableCallSite site, String name, Object... args) throws Throwable {
            name = unscramble(name);
            debug("LINKING: " + name + site.type() + " " + Arrays.toString(args));
            Symbol symbol = intern(name);
            debug("candidates: " + symbol.fn);

            MethodHandle java = javaCall(site, name, site.type(), args);
            if (java != null) {
                debug("calling java: " + java);
                site.setTarget(java.asType(site.type()));
                return java.invokeWithArguments(args);
            }
            if (symbol.fn.isEmpty()) throw new NoSuchMethodException(name + site.type());

            int arity = symbol.fn.get(0).type().parameterCount();
            if (arity > args.length) {
                MethodHandle partial = linker(new MutableCallSite(genericMethodType(arity)), name, arity);
                partial = insertArguments(partial, 0, args);
                debug("partial: " + partial);
                return partial;
            }

            final MethodType actualType = methodType(site.type().returnType(), toList(stream(args).map(Object::getClass)));
            debug("real args: " + Arrays.toString(args) + " " + actualType);

            MethodHandle match = find(symbol.fn.stream(),
                    f -> f.type().wrap().changeReturnType(actualType.returnType()).equals(actualType));
            debug("exact match: " + match);
            if (match == null)
                match = symbol.fn.stream()
                        .filter(f -> canCast(actualType.parameterList(), f.type().parameterList()))
                        .min((x, y) -> without(y.type().parameterList(), Object.class).size()
                                - without(x.type().parameterList(), Object.class).size()).get();
            debug("selected: " + match);

            site.setTarget(symbol.fnGuard.guardWithTest(relinkOnClassCast(site, match), site.getTarget()));
            return match.invokeWithArguments(args);
        }

        static MethodHandle relinkOnClassCast(MutableCallSite site, MethodHandle fn) {
            return catchException(fn.asType(site.type()), ClassCastException.class, dropArguments(site.getTarget(), 0, Exception.class));
        }

        static MethodHandle javaCall(MutableCallSite site, String name, MethodType type, Object... args) throws Exception {
            if (name.endsWith(".")) {
                Class aClass = imports.get(name.substring(0, name.length() - 1));
                if (aClass != null)
                    return findJavaMethod(type, aClass.getName(), aClass.getConstructors());
            }
            if (name.startsWith("."))
                return relinkOnClassCast(site, findJavaMethod(type, name.substring(1, name.length()), args[0].getClass().getMethods()));
            String[] classAndMethod = name.split("/");
            if (classAndMethod.length == 2 && imports.containsKey(classAndMethod[0]))
                return findJavaMethod(type, classAndMethod[1], imports.get(classAndMethod[0]).getMethods());
            return null;
        }

        static <T extends Executable> MethodHandle findJavaMethod(MethodType type, String method, T[] methods) {
            return some(stream(methods), m -> {
                try {
                    if (m.getName().equals(method)) {
                        return ((m instanceof Method) ? lookup.unreflect((Method) m) : lookup.unreflectConstructor((Constructor) m)).asType(type);
                    }
                } catch (Exception ignore) {
                }
                return null;
            });
        }

        static MethodHandle linker(MutableCallSite site, String name, int arity) throws IllegalAccessException {
            return insertArguments(mh(Compiler.class, "link"), 0, site, name).asCollector(Object[].class, arity);
        }

        public static CallSite invokeBSM(Lookup lookup, String name, MethodType type) throws IllegalAccessException {
            MutableCallSite site = new MutableCallSite(type);
            site.setTarget(linker(site, name, type.parameterCount()).asType(type));
            return site;
        }

        public static CallSite symbolBSM(Lookup lookup, String name, MethodType type) {
            return new ConstantCallSite(constant(Symbol.class, intern(unscramble(name))));
        }

        public static CallSite valueBSM(Lookup lookup, String name, MethodType type) throws Exception {
            MutableCallSite site = new MutableCallSite(type);
            site.setTarget(mh(Compiler.class, "value").bindTo(site).asType(type));
            return site;
        }

        static void debug(String msg, Object... xs) {
            if (true == Shen.value("*debug*")) System.err.println(format(msg, xs));
        }

        static MethodHandle mh(Class<?> aClass, String name, Class... types) throws IllegalAccessException {
            return lookup.unreflect(find(stream(aClass.getMethods()), m -> m.getName().equals(name)
                    && (types.length == 0 || deepEquals(m.getParameterTypes(), types))));
        }

        static MethodHandle field(Class<?> aClass, String name) throws Exception {
            return lookup.unreflectGetter(aClass.getField(name));
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
            return new Handle(H_INVOKESTATIC, className, name, desc);
        }

        static Object uncurry(Object chain, Object... args) throws Throwable {
            for (Object arg : args)
                chain = ((MethodHandle) chain).invoke(arg);
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
            return b.isAssignableFrom(a) || canWiden(a, b);
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
            name.fn.clear();
            name.fn.add(fn);
            invalidateAll(new SwitchPoint[] {name.fnGuard});
            name.fnGuard = new SwitchPoint();
            return name;
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

        @SafeVarargs
        static <T> List<T> list(T... elements) {
            return new ArrayList<>(asList(elements));
        }

        static String unscramble(String s) {
            return toSourceName(s).replaceAll("_", "-").replaceAll("^KL-", "")
                    .replaceAll("GT", ">").replaceAll("LT", "<")
                    .replaceAll("EX$", "!").replaceAll("P$", "?");
        }

        static String scramble(String s) {
            return toBytecodeName(s);
        }

        static MethodHandle findSAM(Object lambda) {
            try {
                return lookup.unreflect(findSAM(lambda.getClass())).bindTo(lambda);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }

        static Method findSAM(Class<?> lambda) {
            return find(stream(lambda.getDeclaredMethods()), m -> !m.isSynthetic());
        }

        @Retention(RetentionPolicy.RUNTIME)
        @interface Macro {}

        static class Code {
            static Map<Symbol, MethodHandle> macros = new HashMap<>();
            static List<Class<?>> literals =
                    asList(Double.class, Integer.class, Long.class, String.class, Boolean.class, Handle.class);

            static {
                stream(Code.class.getDeclaredMethods())
                        .filter(m -> isPublic(m.getModifiers()) && m.isAnnotationPresent(Macro.class))
                        .forEach(Code::macro);
            }

            static int id = 1;

            Object shen;
            Symbol name;
            List<Symbol> args;
            List<Type> argTypes;
            Map<Symbol, Integer> locals;
            GeneratorAdapter mv;
            Type topOfStack;
            ClassNode cn;
            Label recur;

            public Code(Object shen, Symbol... args) throws Throwable {
                this(null, shen, args);
            }

            public Code(ClassNode cn, Object shen, Symbol... args) throws Throwable {
                this.cn = cn;
                this.shen = shen;
                this.args = list(args);
                this.locals = new LinkedHashMap<>();
            }

            ClassNode classNode(Class<?> anInterface) {
                ClassNode cn = new ClassNode();
                cn.version = V1_7;
                cn.access = ACC_PUBLIC;
                cn.name = "shen/ShenEval" + id++;
                cn.superName = getInternalName(Object.class);
                cn.interfaces = asList(getInternalName(anInterface));
                return cn;
            }

            GeneratorAdapter generator(MethodVisitor mv) {
                return generator((MethodNode) mv);
            }

            GeneratorAdapter generator(MethodNode mn) {
                return new GeneratorAdapter(mn, mn.access, mn.name, mn.desc);
            }

            org.objectweb.asm.commons.Method method(String name, String desc) {
                return new org.objectweb.asm.commons.Method(name, desc);
            }

            static void macro(Method m) {
                try {
                    macros.put(intern(unscramble(m.getName())), lookup.unreflect(m));
                } catch (IllegalAccessException e) {
                    throw new RuntimeException(e);
                }
            }

            Type compile(Object kl) {
                return compile(kl, true);
            }

            Type compile(Object kl, boolean tail) {
                try {
                    Class literalClass = find(literals.stream(), c -> c.isInstance(kl));
                    if (literalClass != null) push(literalClass, kl);
                    else if (kl instanceof Symbol) symbol((Symbol) kl);
                    else if (kl instanceof List) {
                        @SuppressWarnings("unchecked")
                        List<Object> list = (List) kl;
                        if (list.isEmpty()) emptyList();
                        else {
                            Object first = list.get(0);
                            if (first instanceof Symbol) {
                                Symbol s = (Symbol) first;
                                if (macros.containsKey(s)) macroExpand(s, tl(list), tail);
                                else indy(s, tl(list), tail);

                            } else {
                                compile(first, tail);
                                apply(tl(list));
                            }
                        }
                    } else
                        throw new IllegalArgumentException("Cannot compile: " + kl + " (" + kl.getClass() + ")");
                } catch (RuntimeException | Error e) {
                    throw e;
                } catch (Throwable t) {
                    throw new RuntimeException(t);
                }
                return topOfStack;
            }

            void macroExpand(Symbol s, List<Object> args, boolean tail) throws Throwable {
                MethodHandle macro = Compiler.bindTo(macros.get(s), this);
                Compiler.bindTo(macro, tail).invokeWithArguments(args);
            }

            void indy(Symbol s, List<Object> args, boolean tail) throws ReflectiveOperationException {
                List<Type> argumentTypes = toList(args.stream().map(o -> compile(o, false)));

                if (isSelfCall(s, args)) {
                    if (tail) {
                        debug("recur: " + s);
                        recur();
                        return;
                    } else debug("can only recur from tail position: " + s);
                }
                MethodType type = asMethodType(s.fn.size() == 1
                        ? getType(s.fn.stream().findAny().get().type().returnType())
                        : getType(Object.class), argumentTypes);
                mv.invokeDynamic(scramble(s.symbol), type.toMethodDescriptorString(), handle(mh(Compiler.class, "invokeBSM")));
                topOfStack(type.returnType());
            }

            void recur() {
                for (int i = this.args.size() - 1; i >= 0; i--)
                    mv.storeArg(i);
                mv.goTo(recur);
            }

            boolean isSelfCall(Symbol s, List<Object> args) {
                return s.equals(name) && args.size() == this.args.size();
            }

            void apply(List<Object> args) {
                box();
                maybeCast(MethodHandle.class);

                loadArgArray(args);

                mv.invokeStatic(getType(Compiler.class), method("apply", desc(Object.class, MethodHandle.class, Object[].class)));
                topOfStack = getType(Object.class);
            }

            @Macro
            public void trap_error(boolean tail, Object x, Object f) throws Throwable {
                Label start = mv.newLabel();
                Label end = mv.newLabel();
                Label after = mv.newLabel();

                mv.visitLabel(start);
                compile(x, false);
                box();
                mv.goTo(after);
                mv.visitLabel(end);

                mv.catchException(start, end, getType(Exception.class));
                compile(f, false);
                maybeCast(MethodHandle.class);
                mv.swap();
                bindTo();

                mv.invokeVirtual(getType(MethodHandle.class), method("invoke", desc(Object.class)));
                mv.visitLabel(after);
                topOfStack(Object.class);
            }

            @Macro
            public void KL_if(boolean tail, Object test, Object then, Object _else) throws Exception {
                Label elseStart = mv.newLabel();
                Label end = mv.newLabel();

                compile(test, false);
                if (isPrimitive(topOfStack) && topOfStack != getType(boolean.class)) box();
                if (!isPrimitive(topOfStack)) mv.unbox(getType(boolean.class));
                mv.visitJumpInsn(IFEQ, elseStart);

                compile(then, tail);
                box();
                Type typeOfThenBranch = topOfStack;
                mv.goTo(end);

                mv.visitLabel(elseStart);
                compile(_else, tail);
                box();

                mv.visitLabel(end);
                if (!typeOfThenBranch.equals(topOfStack))
                    topOfStack(Object.class);
            }

            @Macro
            public void cond(boolean tail, List... clauses) throws Exception {
                if (clauses.length == 0)
                    mv.throwException(getType(IllegalArgumentException.class), "condition failure");
                else
                    KL_if(tail, hd(clauses).get(0), hd(clauses).get(1), cons(intern("cond"), list((Object[]) tl(clauses))));
            }

            @Macro
            public void or(boolean tail, Object x, Object... clauses) throws Exception {
                if (clauses.length == 0)
                    bindTo(handle(Compiler.mh(Compiler.class, "or")), x);
                else
                    KL_if(tail, x, true, (clauses.length > 1 ? cons(intern("or"), list(clauses)) : clauses[0]));
            }

            @Macro
            public void and(boolean tail, Object x, Object... clauses) throws Exception {
                if (clauses.length == 0)
                    bindTo(handle(Compiler.mh(Compiler.class, "and")), x);
                else
                    KL_if(tail, x, (clauses.length > 1 ? cons(intern("and"), list(clauses)) : clauses[0]), false);
            }

            @Macro
            public void value(boolean tail, Object x) throws Throwable {
                compile(x, false);
                maybeCast(Symbol.class);
                mv.invokeDynamic("value", desc(Object.class, Symbol.class), handle(mh(Compiler.class, "valueBSM")));
                topOfStack(Object.class);
            }

            void maybeCast(Class<?> type) {
                if (!getType(type).equals(topOfStack)) mv.checkCast(getType(type));
                topOfStack(type);
            }

            @Macro
            public void lambda(boolean tail, Symbol x, Object y) throws Throwable {
                fn("lambda_" + id++, y, x);
            }

            @Macro
            public void defun(boolean tail, Symbol name, final List<Symbol> args, Object body) throws Throwable {
                push(name);
                debug("compiling: " + name + args + " in " + getObjectType(cn.name).getClassName());
                fn(scramble(name.symbol), body, args.toArray(new Symbol[args.size()]));
                mv.invokeStatic(getType(Compiler.class), method("defun", desc(Symbol.class, Symbol.class, MethodHandle.class)));
                topOfStack(Symbol.class);
            }

            void fn(String name, Object shen, Symbol... args) throws Throwable {
                List<Symbol> scope = closesOver(new HashSet<>(asList(args)), shen);
                scope.retainAll(concat(locals.keySet(), this.args));

                List<Type> types = toList(scope.stream().map(this::typeOf));
                for (Symbol ignore : args) types.add(getType(Object.class));

                insertArgs(handle(cn.name, name, desc(getType(Object.class), types)), 0, scope);

                scope.addAll(asList(args));
                Code fn = new Code(cn, shen, scope.toArray(new Symbol[scope.size()]));
                fn.method(ACC_PUBLIC | ACC_STATIC, name, getType(Object.class), types);
            }

            @SuppressWarnings({"SuspiciousMethodCalls", "unchecked"})
            List<Symbol> closesOver(Set<Symbol> scope, Object shen) {
                if (shen instanceof Symbol && !scope.contains(shen))
                    return list((Symbol) shen);
                if (shen instanceof List) {
                    List<Object> list = (List) shen;
                    if (list.size() > 1)
                        if (intern("let").equals(hd(list)))
                            return concat(closesOver(new HashSet<>(scope), list.get(2)),
                                    closesOver(new HashSet<>(concat(asList((Symbol) list.get(1)), scope)),
                                            list.subList(2, list.size())));
                        else if (!asList(intern("lambda"), intern("defun")).contains(hd(list)))
                            return toList(mapcat(tl(list).stream(), o -> closesOver(scope, o)));
                }
                return list();
            }

            @Macro
            public void let(boolean tail, Symbol x, Object y, Object z) throws Throwable {
                compile(y, false);
                int let = mv.newLocal(topOfStack);
                mv.storeLocal(let);
                Integer hidden = locals.put(x, let);
                compile(z, tail);
                if (hidden != null) locals.put(x, hidden);
                else locals.remove(x);
            }

            void emptyList() {
                mv.getStatic(getType(Collections.class), "EMPTY_LIST", getType(List.class));
                topOfStack(List.class);
            }

            void symbol(Symbol s) throws ReflectiveOperationException {
                if (locals.containsKey(s)) mv.loadLocal(locals.get(s));
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
                topOfStack = getType(Object[].class);
            }

            MethodType asMethodType(Type returnType, List<Type> argumentTypes) {
                return fromMethodDescriptorString(desc(returnType, argumentTypes), loader);
            }

            void push(Symbol kl) throws ReflectiveOperationException {
                mv.invokeDynamic(scramble(kl.symbol), desc(Symbol.class), handle(mh(Compiler.class, "symbolBSM")));
                topOfStack(Symbol.class);
            }

            void push(Class<?> aClass, Object kl) throws Throwable {
                aClass = asPrimitiveType(aClass);
                mh(mv.getClass(), "push", aClass).invoke(mv, kl);
                topOfStack(aClass);
            }

            void topOfStack(Class<?> aClass) {
                topOfStack = getType(aClass);
            }

            public <T> Class<T> load(Class<T> anInterface) throws Exception {
                cn = classNode(anInterface);
                constructor();
                Method sam = findSAM(anInterface);
                List<Type> types = toList(stream(sam.getParameterTypes()).map(Type::getType));
                method(ACC_PUBLIC, sam.getName(), getType(sam.getReturnType()), types);
                //noinspection unchecked
                return (Class<T>) loader.define(cn);
            }

            void method(int modifiers, String name, Type returnType, List<Type> argumentTypes) {
                this.name = intern(unscramble(name));
                this.argTypes = argumentTypes;
                mv = generator(cn.visitMethod(modifiers, name, desc(returnType, argumentTypes), null, null));
                recur = mv.newLabel();
                mv.visitLabel(recur);
                compile(shen);
                if (!isPrimitive(returnType)) box();
                mv.returnValue();
            }

            void box() {
                Type maybePrimitive = topOfStack;
                mv.box(maybePrimitive);
                topOfStack = boxedType(maybePrimitive);
            }

            void constructor() {
                GeneratorAdapter ctor = generator(cn.visitMethod(ACC_PUBLIC, "<init>", desc(void.class), null, null));
                ctor.loadThis();
                ctor.invokeConstructor(getType(Object.class), method("<init>", desc(void.class)));
                ctor.returnValue();
            }

            void bindTo(Handle handle, Object arg) {
                mv.push(handle);
                compile(arg, false);
                box();
                bindTo();
            }

            void bindTo() {
                mv.invokeStatic(getType(Compiler.class), method("bindTo",
                        desc(MethodHandle.class, MethodHandle.class, Object.class)));
                topOfStack(MethodHandle.class);
            }

            void insertArgs(Handle handle, int pos, List<?> args) {
                mv.push(handle);
                mv.push(pos);
                loadArgArray(args);
                mv.invokeStatic(getType(MethodHandles.class), method("insertArguments",
                        desc(MethodHandle.class, MethodHandle.class, int.class, Object[].class)));
                topOfStack(MethodHandle.class);
            }
        }
    }
}
