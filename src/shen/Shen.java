package shen;

import org.objectweb.asm.*;
import org.objectweb.asm.commons.GeneratorAdapter;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

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
import java.util.stream.Stream;

import static java.lang.String.format;
import static java.lang.System.in;
import static java.lang.System.out;
import static java.lang.invoke.MethodHandles.*;
import static java.lang.invoke.MethodHandles.insertArguments;
import static java.lang.invoke.MethodHandles.lookup;
import static java.lang.invoke.MethodType.*;
import static java.lang.invoke.SwitchPoint.invalidateAll;
import static java.lang.reflect.Modifier.isPublic;
import static java.util.Arrays.*;
import static java.util.Objects.deepEquals;
import static org.objectweb.asm.ClassWriter.COMPUTE_FRAMES;
import static org.objectweb.asm.ClassWriter.COMPUTE_MAXS;
import static org.objectweb.asm.Type.*;
import static shen.Shen.Compiler.*;
import static shen.Shen.KLReader.read;
import static sun.invoke.util.BytecodeName.toBytecodeName;
import static sun.invoke.util.BytecodeName.toSourceName;

@SuppressWarnings({"UnusedDeclaration", "Convert2Diamond"})
public class Shen {
    public static void main(String[] args) throws Throwable {
        install();
        repl();
    }

    static Map<String, Symbol> symbols = new HashMap<>();
    static Compiler loader = new Compiler();
    static Map<String, Class> imports = new HashMap<String, Class>();

    static {
        set("*language*", "Java");
        set("*implementation*", format("[jvm %s]", System.getProperty("java.version")));
        set("*porters*", "Håkan Råberg");
        set("*stinput*", in);
        set("*stoutput*", out);
        set("*debug*", false);
        set("*home-directory*", System.getProperty("user.dir"));

        stream(Shen.class.getDeclaredMethods()).filter(m -> isPublic(m.getModifiers())).forEach(Shen::defun);

        op("=", (BiPredicate<Object, Object>) (left, right) -> left instanceof Number && right instanceof Number
                        ? ((Number) left).doubleValue() == ((Number) right).doubleValue()
                        : deepEquals(left, right));
        op("+", (IntBinaryOperator) (left, right) -> left + right);
        op("-", (IntBinaryOperator) (left, right) -> left - right);
        op("*", (IntBinaryOperator) (left, right) -> left * right);
        op("+", (LongBinaryOperator) (left, right) -> left + right);
        op("-", (LongBinaryOperator) (left, right) -> left - right);
        op("*", (LongBinaryOperator) (left, right) -> left * right);
        op("+", (DoubleBinaryOperator) (left, right) -> left + right);
        op("-", (DoubleBinaryOperator) (left, right) -> left - right);
        op("*", (DoubleBinaryOperator) (left, right) -> left * right);
        op("/", (DoubleBinaryOperator) (left, right) -> {
            if (right == 0) throw new ArithmeticException("Division by zero");
            return left / right;
        });

        op("<", (IIPredicate) (left, right) -> left < right);
        op("<=", (IIPredicate) (left, right) -> left <= right);
        op(">", (IIPredicate) (left, right) -> left > right);
        op(">=", (IIPredicate) (left, right) -> left >= right);
        op("<", (LLPredicate) (left, right) -> left < right);
        op("<=", (LLPredicate) (left, right) -> left <= right);
        op(">", (LLPredicate) (left, right) -> left > right);
        op(">=", (LLPredicate) (left, right) -> left >= right);
        op("<", (DDPredicate) (left, right) -> left < right);
        op("<=", (DDPredicate) (left, right) -> left <= right);
        op(">", (DDPredicate) (left, right) -> left > right);
        op(">=", (DDPredicate) (left, right) -> left >= right);

        KL_import(Math.class);
        KL_import(System.class);
    }

    interface IIPredicate { boolean test(int a, int b);}
    interface LLPredicate { boolean test(long a, long b);}
    interface DDPredicate { boolean test(double a, double b);}

    static void op(String name, Object op) {
        intern(name).fn.add(findSAM(op));
    }

    static Symbol defun(Method m) {
       try {
            Symbol name = intern(unscramble(m.getName()));
            name.fn.add(lookup().unreflect(m));
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
        if (y instanceof List) //noinspection unchecked
            return cons(x, (List) y);
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
        public List<SwitchPoint> usages = new ArrayList<>();
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

        public static Object link(MutableCallSite site, String name, Object... args) throws Throwable {
            MethodType type = site.type();
            name = unscramble(name);
            debug("LINKING: " + name + type + " " + Arrays.toString(args));
            Symbol symbol = intern(name);
            debug("candidates: " + symbol.fn);

            if (symbol.fn.isEmpty() && maybeJava(name)) {
                MethodHandle java = javaCall(name, type, args);
                if (java != null) {
                    debug("calling java: " + java);
                    site.setTarget(java.asType(type));
                    return java.invokeWithArguments(args);
                }
            }

            int arity = symbol.fn.get(0).type().parameterCount();
            if (arity > args.length) {
                MethodHandle partial = linker(new MutableCallSite(genericMethodType(arity)), name, arity);
                partial = insertArguments(partial, 0, args);
                debug("partial: " + partial);
                return partial;
            }

            final MethodType matchType = methodType(site.type().returnType(),
                    stream(args).map(Object::getClass).into(new ArrayList<Class<?>>()));
            debug("real args: " + Arrays.toString(args));

            MethodHandle match = some(symbol.fn.stream(), f -> canCast(matchType.parameterList(), f.type().parameterList()));
            debug("selected: " + match);

            SwitchPoint switchPoint = new SwitchPoint();
            symbol.usages.add(switchPoint);
            match = switchPoint.guardWithTest(match.asType(type), site.getTarget());
            site.setTarget(match);
            return match.invokeWithArguments(args);
        }

        static MethodHandle javaCall(String name, MethodType type, Object... args) throws IllegalAccessException {
            if (name.endsWith(".")) {
                String ctor = name.substring(0, name.length() - 1);
                Class aClass = imports.get(ctor);
                return lookup.unreflectConstructor(findJavaMethod(type, aClass.getName(), aClass.getConstructors()));
            }
            if (name.startsWith(".")) {
                String method = name.substring(1, name.length());
                return lookup.unreflect(findJavaMethod(type, method, args[0].getClass().getMethods()));
            }
            String[] classAndMethod = name.split("/");
            if (classAndMethod.length == 2) {
                Class aClass = imports.get(classAndMethod[0]);
                String method = classAndMethod[1];
                if (aClass != null)
                    return lookup.unreflect(findJavaMethod(type, method, aClass.getMethods()));
            }
            return null;
        }

        static <T extends Executable> T findJavaMethod(MethodType type, String method, T[] methods) {
            return some(stream(methods), m -> {
                try {
                    if (m.getName().equals(method)) {
                        ((m instanceof Method) ? lookup.unreflect((Method) m) : lookup.unreflectConstructor((Constructor) m)).asType(type);
                        return true;
                    }
                } catch (Exception ignore) {
                }
                return false;
            });
        }

        static boolean maybeJava(String name) {
            return name.contains(".") || name.contains("/") && name.length() > 1;
        }

        static MethodHandle linker(MutableCallSite site, String name, int arity) {
            return insertArguments(link, 0, site, name).asCollector(Object[].class, arity);
        }

        public static CallSite invokeBSM(Lookup lookup, String name, MethodType type) {
            MutableCallSite site = new MutableCallSite(type);
            site.setTarget(linker(site, name, type.parameterCount()).asType(type));
            return site;
        }

        public static CallSite symbolBSM(Lookup lookup, String name, MethodType type) {
            return new ConstantCallSite(constant(Symbol.class, intern(unscramble(name))));
        }

        static void debug(String msg, Object... xs) {
            if (true == value("*debug*")) System.err.println(format(msg, xs));
        }

        static MethodHandle insertArguments;
        static MethodHandle link;
        static Lookup lookup = lookup();

        static {
            try {
                insertArguments = lookup.findStatic(MethodHandles.class, "insertArguments",
                        methodType(MethodHandle.class, asList(MethodHandle.class, int.class, Object[].class)));
                link = lookup.findStatic(Compiler.class, "link",
                        methodType(Object.class, asList(MutableCallSite.class, String.class, Object[].class)));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        static String bootstrapDesc = desc(CallSite.class, Lookup.class, String.class, MethodType.class);
        static Handle invokeBSM = staticMH(Compiler.class, "invokeBSM", bootstrapDesc);
        static Handle symbolBSM = staticMH(Compiler.class, "symbolBSM", bootstrapDesc);

        static String desc(Class<?> returnType, Class<?>... argumentTypes ) {
            return methodType(returnType, argumentTypes).toMethodDescriptorString();
        }

        static String desc(Type returnType, List<Type> argumentTypes) {
            return getMethodDescriptor(returnType, argumentTypes.toArray(new Type[argumentTypes.size()]));
        }

        static Handle staticMH(Class aClass, String name, String desc) {
            return staticMH(getInternalName(aClass), name, desc);
        }

        static Handle staticMH(String className, String name, String desc) {
            return new Handle(H_INVOKESTATIC, className, name, desc);
        }

        static Object uncurry(Object chain, Object... args) throws Throwable {
            for (Object arg : args)
                chain = ((MethodHandle) chain).invoke(arg);
            return chain;
        }

        static boolean isLambda(MethodHandle fn) {
            return fn.type().parameterCount() == 1 && !fn.isVarargsCollector();
        }

        static Type boxedType(Type type) {
            try {
                java.lang.reflect.Method getBoxedType = GeneratorAdapter.class.getDeclaredMethod("getBoxedType", Type.class);
                getBoxedType.setAccessible(true);
                return (Type) getBoxedType.invoke(null, type);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        static Class boxedType(Class type) {
            try {
                return Class.forName(boxedType(getType(type)).getClassName());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        static boolean canCast(Class<?> a, Class<?> b) {
            return b.isAssignableFrom(a) || canWiden(a, b);
        }

        static List<? extends Class<?>> numbers = asList(Double.class, Long.class, Integer.class);

        static boolean canWiden(Class a, Class b) {
            if (a.isPrimitive()) a = boxedType(a);
            if (b.isPrimitive()) b = boxedType(b);
            return Number.class.isAssignableFrom(a) &&  Number.class.isAssignableFrom(b)
                    && numbers.indexOf(a) >= numbers.indexOf(b);
        }

        static boolean canCast(List<Class<?>> as, List<Class<?>> bs) {
            for (int i = 0; i < as.size(); i++)
                if (!canCast(as.get(i), bs.get(i))) return false;
            return true;
        }

        public static Symbol defun(Symbol name, MethodHandle fn) throws Throwable {
            name.fn.clear();
            name.fn.add(fn);
            invalidateAll(name.usages.toArray(new SwitchPoint[name.usages.size()]));
            name.usages.clear();
            return name;
        }

        public static Object apply(MethodHandle fn, Object...  args) throws Throwable {
            if (isLambda(fn)) return uncurry(fn, args);

            MethodType targetType = methodType(Object.class, stream(args).map(Object::getClass).into(new ArrayList<Class<?>>()));

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

        static <T> T some(Stream<T> stream, Predicate<? super T> predicate) {
            return stream.filter(predicate).findAny().orElse((T) null);
        }

        @SafeVarargs
        static <T> List<T> list(T... elements) {
            return asList(elements).stream().into(new ArrayList<T>());
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

        static java.lang.reflect.Method findSAM(Class<?> lambda) {
            return some(stream(lambda.getDeclaredMethods()), m -> !m.isSynthetic());
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

            static void macro(java.lang.reflect.Method m)  {
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
                    Class literalClass = some(literals.stream(), c -> c.isInstance(kl));
                    if (literalClass != null) push(literalClass, kl);
                    else if (kl instanceof Symbol) symbol((Symbol) kl);
                    else if (kl instanceof List) {
                        @SuppressWarnings("unchecked")
                        List<Object> list = (List) kl;
                        if (list.isEmpty())
                            emptyList();
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

            void indy(Symbol s, List<Object> args, boolean tail) {
                List<Type> argumentTypes = args.stream().map(o -> compile(o, false)).into(new ArrayList<Type>());

                if (isSelfCall(s, args)) {
                    if (tail) {
                        debug("recur: "  + s);
                        recur();
                        return;
                    } else debug("can only recur from tail position: "  + s);
                }
                MethodType type = asMethodType(s.fn.size() == 1
                        ? getType(s.fn.stream().findAny().get().type().returnType())
                        : getType(Object.class), argumentTypes);
                mv.invokeDynamic(scramble(s.symbol), type.toMethodDescriptorString(), invokeBSM);
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
                mv.checkCast(getType(MethodHandle.class));

                loadArgArray(args);

                mv.invokeStatic(getType(Compiler.class), new org.objectweb.asm.commons.Method("apply", desc(Object.class, MethodHandle.class, Object[].class)));
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
                mv.checkCast(getType(MethodHandle.class));
                mv.swap();
                bindTo();

                mv.invokeVirtual(getType(MethodHandle.class), new org.objectweb.asm.commons.Method("invoke", desc(Object.class)));
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
                mv.goTo(end);

                mv.visitLabel(elseStart);
                compile(_else, tail);
                box();

                mv.visitLabel(end);
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
                    bindTo(staticMH(Compiler.class, "or", desc(boolean.class, boolean.class, boolean[].class)), x);
                else
                    KL_if(tail, x, true, (clauses.length > 1 ? cons(intern("or"), list(clauses)) : clauses[0]));
            }

            @Macro
            public void and(boolean tail, Object x, Object... clauses) throws Exception {
                if (clauses.length == 0)
                    bindTo(staticMH(Compiler.class, "and", desc(boolean.class, boolean.class, boolean[].class)), x);
                else
                    KL_if(tail, x, (clauses.length > 1 ? cons(intern("and"), list(clauses)) : clauses[0]), false);
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
                mv.invokeStatic(getType(Compiler.class), new org.objectweb.asm.commons.Method("defun", desc(Symbol.class, Symbol.class, MethodHandle.class)));
                topOfStack(Symbol.class);
            }

            void fn(String name, Object shen, Symbol... args) throws Throwable {
                List<Type> types = locals.values().stream().map(mv::getLocalType).into(new ArrayList<Type>());
                types.addAll(this.argTypes);
                for (Symbol arg : args) types.add(getType(Object.class));

                List<Symbol> scope = new ArrayList<>(locals.keySet());
                scope.addAll(this.args);
                scope.addAll(asList(args));

                Code fn = new Code(cn, shen, scope.toArray(new Symbol[scope.size()]));
                fn.method(ACC_PUBLIC | ACC_STATIC, name, getType(Object.class), types);

                insertArgs(staticMH(cn.name, name, desc(getType(Object.class), types)), 0, scope.subList(0, scope.size() - args.length));
            }

            @Macro
            public void let(boolean tail, Symbol x, Object y, Object z) throws Throwable {
                compile(y, false);
                int let = mv.newLocal(topOfStack);
                mv.storeLocal(let);
                locals.put(x, let);
                compile(z, tail);
                locals.remove(x);
            }

            void emptyList() {
                mv.getStatic(getType(Collections.class), "EMPTY_LIST", getType(List.class));
                topOfStack(List.class);
            }

            void symbol(Symbol s) {
                if (locals.containsKey(s)) {
                    int local = locals.get(s);
                    mv.loadLocal(local);
                    topOfStack = mv.getLocalType(local);
                } else if (args.contains(s)) {
                    int arg = args.indexOf(s);
                    mv.loadArg(arg);
                    topOfStack = argTypes.get(arg);
                } else
                    push(s);
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

            void push(Symbol kl) {
                mv.invokeDynamic(scramble(kl.symbol), methodType(Symbol.class).toMethodDescriptorString(), symbolBSM);
                topOfStack(Symbol.class);
            }

            void push(Class<?> aClass, Object kl) throws Exception {
                aClass = maybePrimitive(aClass);
                mv.getClass().getMethod("push", aClass).invoke(mv, kl);
                topOfStack(aClass);
            }

            void topOfStack(Class<?> aClass) {
                topOfStack = getType(aClass);
            }

            Class<?> maybePrimitive(Class<?> aClass) throws IllegalAccessException {
                try {
                    return (Class<?>) aClass.getField("TYPE").get(null);
                } catch (NoSuchFieldException ignore) {
                    return aClass;
                }
            }

            public <T> Class<T> load(Class<T> anInterface) throws Exception {
                cn = classNode(anInterface);
                constructor();
                java.lang.reflect.Method sam = findSAM(anInterface);
                List<Type> types = stream(sam.getParameterTypes()).map(Type::getType).into(new ArrayList<Type>());
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

            boolean isPrimitive(Type type) {
                return type.getSort() != OBJECT;
            }

            void box() {
                Type maybePrimitive = topOfStack;
                mv.box(maybePrimitive);
                topOfStack = boxedType(maybePrimitive);
            }

            void constructor() {
                GeneratorAdapter ctor = generator(cn.visitMethod(ACC_PUBLIC, "<init>", desc(void.class), null, null));
                ctor.loadThis();
                ctor.invokeConstructor(getType(Object.class), new org.objectweb.asm.commons.Method("<init>", desc(void.class)));
                ctor.returnValue();
            }


            void bindTo(Handle handle, Object arg) {
                mv.push(handle);
                compile(arg, false);
                box();
                bindTo();
            }

            void bindTo() {
                mv.invokeStatic(getType(Compiler.class), new org.objectweb.asm.commons.Method("bindTo", desc(MethodHandle.class, MethodHandle.class, Object.class)));
                topOfStack(MethodHandle.class);
            }

            void insertArgs(Handle handle, int pos, List<?> args) {
                mv.push(handle);
                mv.push(pos);
                loadArgArray(args);
                mv.invokeStatic(getType(MethodHandles.class), new org.objectweb.asm.commons.Method("insertArguments", desc(MethodHandle.class, MethodHandle.class, int.class, Object[].class)));
                topOfStack(MethodHandle.class);
            }
        }
    }
}
