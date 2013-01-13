package shen;

import org.objectweb.asm.*;
import org.objectweb.asm.commons.GeneratorAdapter;
import org.objectweb.asm.commons.Method;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.invoke.*;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static java.lang.invoke.MethodHandles.*;
import static java.lang.invoke.MethodType.*;
import static java.lang.reflect.Modifier.isPublic;
import static java.util.Arrays.asList;
import static java.util.Arrays.stream;
import static org.objectweb.asm.ClassWriter.COMPUTE_FRAMES;
import static org.objectweb.asm.ClassWriter.COMPUTE_MAXS;
import static org.objectweb.asm.Type.*;
import static shen.Shen.*;

@SuppressWarnings({"UnusedDeclaration", "Convert2Diamond"})
public class ShenCompiler implements Opcodes {
    public static class ShenLoader extends ClassLoader {
        public Class<?> define(ClassNode cn) {
            ClassWriter cw = new ClassWriter(COMPUTE_FRAMES | COMPUTE_MAXS);
            cn.accept(cw);
            byte[] bytes = cw.toByteArray();
            return super.defineClass(cn.name.replaceAll("/", "."), bytes, 0, bytes.length);
        }
    }

    static ShenLoader loader = new ShenLoader();

    public static Object link(MutableCallSite site, String name, Object... args) throws Throwable {
        MethodType type = site.type();
        name = unscramble(name);
        debug("LINKING: " + name + type + " " + Arrays.toString(args));
        Symbol symbol = intern(name);
        debug("candidates: " + symbol.fn);

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
        site.setTarget(match.asType(type));
        return match.invokeWithArguments(args);
    }

    static MethodHandle linker(MutableCallSite site, String name, int arity) {
        return insertArguments(link, 0, site, name).asCollector(Object[].class, arity);
    }

    public static CallSite bootstrap(Lookup lookup, String name, MethodType type) {
        MutableCallSite site = new MutableCallSite(type);
        site.setTarget(linker(site, name, type.parameterCount()).asType(type));
        return site;
    }

    static void debug(String msg, Object... xs) {
        if (true == value("*debug*"))
            System.err.println(String.format(msg, xs));
    }

    static MethodHandle insertArguments;
    static MethodHandle link;
    static {
        try {
            insertArguments = lookup().findStatic(MethodHandles.class, "insertArguments",
                    methodType(MethodHandle.class, new Class[]{MethodHandle.class, int.class, Object[].class}));
            link = lookup().findStatic(ShenCompiler.class, "link",
                    methodType(Object.class, new Class[]{MutableCallSite.class, String.class, Object[].class}));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static Handle bootstrap = staticMH(getInternalName(ShenCompiler.class), "bootstrap",
            desc(CallSite.class, Lookup.class, String.class, MethodType.class));

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
        return s.replaceAll("_", "-").replaceAll("EQ", "=").replaceAll("P$", "?")
                .replaceAll("EX$", "!").replaceAll("GT", ">")
                .replaceAll("LT", "<").replaceAll("SLASH", "/").replaceAll("^kl-", "");
    }

    static String scramble(String s) {
        return s.replaceAll("-", "_").replaceAll("=", "EQ").replaceAll(">", "GT")
                .replaceAll("<", "LT").replaceAll("/", "SLASH");
    }


    static MethodHandle findSAM(Object lambda) {
        try {
            return lookup().unreflect(findSAM(lambda.getClass())).bindTo(lambda);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    static java.lang.reflect.Method findSAM(Class<?> lambda) {
        return some(stream(lambda.getDeclaredMethods()), m -> !m.isSynthetic());
    }

    @Retention(RetentionPolicy.RUNTIME)
    public @interface Macro {}

    public static class ShenCode {
        static Map<Symbol, MethodHandle> macros = new HashMap<>();
        static List<Class<?>> literals =
                asList(Double.class, Integer.class, Long.class, String.class, Boolean.class, Handle.class);

        static {
            stream(ShenCode.class.getDeclaredMethods())
                    .filter(m -> isPublic(m.getModifiers()) && m.isAnnotationPresent(Macro.class))
                    .forEach(ShenCode::macro);
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

        public ShenCode(Object shen, Symbol... args) throws Throwable {
            this(null, shen, args);
        }

        public ShenCode(ClassNode cn, Object shen, Symbol... args) throws Throwable {
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
                macros.put(intern(unscramble(m.getName())), lookup().unreflect(m));
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            }
        }

        Type compile(Object kl) {
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
                            if (macros.containsKey(s)) macroExpand(s, tl(list));
                            else indy(s, tl(list));

                        } else {
                            compile(first);
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

        void macroExpand(Symbol s, List<Object> args) throws Throwable {
            ShenCompiler.bindTo(macros.get(s), this).invokeWithArguments(args);
        }

        void indy(Symbol s, List<Object> args) {
            List<Type> argumentTypes = args.stream().map(this::compile).into(new ArrayList<Type>());

            if (isSelfCall(s, args) && isTailPosition()) {
                debug("recur: "  + s);
                recur();
                return;
            }
            MethodType type = asMethodType(s.fn.size() == 1
                    ? getType(s.fn.stream().findAny().get().type().returnType())
                    : getType(Object.class), argumentTypes);
            mv.invokeDynamic(scramble(s.symbol), type.toMethodDescriptorString(), bootstrap);
            topOfStack(type.returnType());
        }

        boolean isTailPosition() {
            return false;
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

            mv.invokeStatic(getType(ShenCompiler.class), new Method("apply", desc(Object.class, MethodHandle.class, Object[].class)));
            topOfStack = getType(Object.class);
        }

        @Macro
        public void trap_error(Object x, Object f) throws Throwable {
            Label start = mv.newLabel();
            Label end = mv.newLabel();
            Label after = mv.newLabel();

            mv.visitLabel(start);
            compile(x);
            box();
            mv.goTo(after);
            mv.visitLabel(end);

            mv.catchException(start, end, getType(Exception.class));
            compile(f);
            mv.checkCast(getType(MethodHandle.class));
            mv.swap();
            bindTo();

            mv.invokeVirtual(getType(MethodHandle.class), new Method("invoke", desc(Object.class)));
            mv.visitLabel(after);
            topOfStack(Object.class);
        }

        @Macro
        public void kl_if(Object test, Object then, Object _else) throws Exception {
            Label elseStart = mv.newLabel();
            Label end = mv.newLabel();

            compile(test);
            if (isPrimitive(topOfStack) && topOfStack != getType(boolean.class)) box();
            if (!isPrimitive(topOfStack)) mv.unbox(getType(boolean.class));
            mv.visitJumpInsn(IFEQ, elseStart);

            compile(then);
            box();
            mv.goTo(end);

            mv.visitLabel(elseStart);
            compile(_else);
            box();

            mv.visitLabel(end);
        }

        @Macro
        public void cond(List... clauses) throws Exception {
            if (clauses.length == 0)
                mv.throwException(getType(IllegalArgumentException.class), "condition failure");
            else
                kl_if(hd(clauses).get(0), hd(clauses).get(1), cons(intern("cond"), list((Object[]) tl(clauses))));
        }

        @Macro
        public void or(Object x, Object... clauses) throws Exception {
            if (clauses.length == 0)
                bindTo(staticMH(ShenCompiler.class, "or", desc(boolean.class, boolean.class, boolean[].class)), x);
            else
                kl_if(x, true, (clauses.length > 1 ? cons(intern("or"), list(clauses)) : clauses[0]));
        }

        @Macro
        public void and(Object x, Object... clauses) throws Exception {
            if (clauses.length == 0)
                bindTo(staticMH(ShenCompiler.class, "and", desc(boolean.class, boolean.class, boolean[].class)), x);
            else
                kl_if(x, (clauses.length > 1 ? cons(intern("and"), list(clauses)) : clauses[0]), false);
        }

        @Macro
        public void lambda(Symbol x, Object y) throws Throwable {
            fn("lambda$" + id++, y, x);
        }

        @Macro
        public void defun(Symbol name, final List<Symbol> args, Object body) throws Throwable {
            push(name);
            fn(scramble(name.symbol), body, args.toArray(new Symbol[args.size()]));
            mv.invokeStatic(getType(ShenCompiler.class), new Method("defun", desc(Symbol.class, Symbol.class, MethodHandle.class)));
            topOfStack(Symbol.class);
        }

        void fn(String name, Object shen, Symbol... args) throws Throwable {
            List<Type> types = locals.values().stream().map(mv::getLocalType).into(new ArrayList<Type>());
            types.addAll(this.argTypes);
            for (Symbol arg : args) types.add(getType(Object.class));

            List<Symbol> scope = new ArrayList<>(locals.keySet());
            scope.addAll(this.args);
            scope.addAll(asList(args));

            ShenCode fn = new ShenCode(cn, shen, scope.toArray(new Symbol[scope.size()]));
            fn.compileMethod(ACC_PUBLIC | ACC_STATIC, name, getType(Object.class), types);

            insertArgs(staticMH(cn.name, name, desc(getType(Object.class), types)), 0, scope.subList(0, scope.size() - args.length));
        }

        @Macro
        public void let(Symbol x, Object y, Object z) throws Throwable {
            compile(y);
            int let = mv.newLocal(topOfStack);
            mv.storeLocal(let);
            locals.put(x, let);
            compile(z);
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
                compile(args.get(i));
                box();
                mv.arrayStore(getType(Object.class));
            }
            topOfStack = getType(Object[].class);
        }

        MethodType asMethodType(Type returnType, List<Type> argumentTypes) {
            return fromMethodDescriptorString(desc(returnType, argumentTypes), loader);
        }

        void push(Symbol kl) {
            mv.push(kl.symbol);
            mv.invokeStatic(getType(Shen.class), new Method("intern", desc(Symbol.class, String.class)));
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
            compileMethod(ACC_PUBLIC, sam.getName(), getType(sam.getReturnType()), types);
            //noinspection unchecked
            return (Class<T>) loader.define(cn);
        }

        void compileMethod(int modifier, String name, Type returnType, List<Type> argumentTypes) {
            this.name = intern(unscramble(name));
            this.argTypes = argumentTypes;
            mv = generator(cn.visitMethod(modifier, name, desc(returnType, argumentTypes), null, null));
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
            ctor.invokeConstructor(getType(Object.class), new Method("<init>", desc(void.class)));
            ctor.returnValue();
        }


        void bindTo(Handle handle, Object arg) {
            mv.push(handle);
            compile(arg);
            box();
            bindTo();
        }

        void bindTo() {
            mv.invokeStatic(getType(ShenCompiler.class), new Method("bindTo", desc(MethodHandle.class, MethodHandle.class, Object.class)));
            topOfStack(MethodHandle.class);
        }

        void insertArgs(Handle handle, int pos, List<?> args) {
            mv.push(handle);
            mv.push(pos);
            loadArgArray(args);
            insertArgs();
        }

        void insertArgs() {
            mv.invokeStatic(getType(MethodHandles.class), new Method("insertArguments", desc(MethodHandle.class, MethodHandle.class, int.class, Object[].class)));
            topOfStack(MethodHandle.class);
        }
    }
}
