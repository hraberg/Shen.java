package shen;

import org.objectweb.asm.*;
import org.objectweb.asm.commons.GeneratorAdapter;
import org.objectweb.asm.commons.Method;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.lang.invoke.*;
import java.util.ArrayList;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.function.BiPredicate;

import static java.lang.invoke.MethodHandles.*;
import static java.lang.invoke.MethodType.fromMethodDescriptorString;
import static java.lang.invoke.MethodType.methodType;
import static java.lang.reflect.Modifier.isPublic;
import static java.util.Arrays.*;
import static org.objectweb.asm.ClassWriter.COMPUTE_FRAMES;
import static org.objectweb.asm.ClassWriter.COMPUTE_MAXS;
import static org.objectweb.asm.Type.*;
import static shen.Shen.*;
import static shen.Shen.lookup;

@SuppressWarnings({"UnusedDeclaration", "Convert2Diamond", "SuspiciousNameCombination"})
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

    public static Object eval(String shen) throws Throwable {
        return eval(read(shen).get(0));
    }

    public static Object eval(Object code) throws Throwable {
        return new ShenCode(code).load(Callable.class).newInstance().call();
    }

    public static CallSite bootstrap(Lookup lookup, String name, MethodType type) {
        name = unscramble(name);
        System.out.println("BOOTSTRAP: " + name + type);
        Symbol symbol = intern(name);
        System.out.println("candidates: " + symbol.fn);
        MethodHandle match = some(symbol.fn.stream(), f -> hasMatchingSignature(f, type, Class::isAssignableFrom));
        try {
            if (match == null) {
                match = some(symbol.fn.stream(), f -> {
                    try {
                        f.asType(type);
                        return true;
                    } catch (WrongMethodTypeException _) {
                        return false;
                    }
                });
            }
            if (match == null) match = symbol.fn.stream().findAny().get();
            if (match.type().parameterCount() > type.parameterCount()) {
                try {
                    System.out.println("partial: " + type.parameterCount() + " out of " + match.type().parameterCount());
                    match = insertArguments(insertArguments, 0, match, 0).asCollector(Object[].class, type.parameterCount());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
            match = match.asType(type);
        } catch (Throwable t) {
            System.out.println("Saving and Compiling an Exception " + t);
            match = dropArguments(throwException(type.returnType(), Throwable.class).bindTo(t), 0, type.parameterList());
        }
        System.out.println("selected: " + match);
        return new MutableCallSite(match);
    }

    static MethodHandle insertArguments;
    static {
        try {
            insertArguments = lookup.findStatic(MethodHandles.class, "insertArguments", methodType(MethodHandle.class, new Class[]{MethodHandle.class, int.class, Object[].class}));
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

    static boolean hasMatchingSignature(MethodHandle h, MethodType args, BiPredicate<Class, Class> match) {
        int last = h.type().parameterCount() - 1;
        if (h.isVarargsCollector() && args.parameterCount() - last > 0)
            h = h.asCollector(h.type().parameterType(last), args.parameterCount() - last);
        if (args.parameterCount() > h.type().parameterCount()) return false;

        Class<?>[] classes = h.type().parameterArray();
        for (int i = 0; i < args.parameterCount(); i++)
            if (!match.test(classes[i], args.parameterType(i))) return false;
        return true;
    }

    static Handle staticMH(Class aClass, String name, String desc) {
        return staticMH(getInternalName(aClass), name, desc);
    }

    static Handle staticMH(String className, String name, String desc) {
        return new Handle(H_INVOKESTATIC, className, name, desc);
    }

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
                macros.put(intern(unscramble(m.getName())), lookup.unreflect(m));
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            }
        }

        Type compile(final Object kl) {
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

        void macroExpand(Symbol s, List<Object> args) throws Throwable {
            bindTo(macros.get(s), this).invokeWithArguments(args);
        }

        void indy(Symbol s, List<Object> args) {
            List<Type> argumentTypes = args.stream().map(this::compile).into(new ArrayList<Type>());

            MethodType type = asMethodType(s.fn.size() == 1
                    ? getType(s.fn.stream().findAny().get().type().returnType())
                    : getType(Object.class), argumentTypes);
            mv.invokeDynamic(scramble(s.symbol), type.toMethodDescriptorString(), bootstrap);
            topOfStack(type.returnType());
        }

        void apply(List<Object> args) {
            box();
            mv.checkCast(getType(MethodHandle.class));

            loadArgArray(args);

            mv.invokeStatic(getType(ShenCode.class), new Method("apply", desc(Object.class, MethodHandle.class, Object[].class)));
            topOfStack = getType(Object.class);
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

        Type boxedType(Type type) {
            try {
                java.lang.reflect.Method getBoxedType = GeneratorAdapter.class.getDeclaredMethod("getBoxedType", Type.class);
                getBoxedType.setAccessible(true);
                return (Type) getBoxedType.invoke(null, type);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        void constructor() {
            GeneratorAdapter ctor = generator(cn.visitMethod(ACC_PUBLIC, "<init>", desc(void.class), null, null));
            ctor.loadThis();
            ctor.invokeConstructor(getType(Object.class), new Method("<init>", desc(void.class)));
            ctor.returnValue();
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
                bindTo(staticMH(ShenCode.class, "or", desc(boolean.class, boolean.class, boolean[].class)), x);
            else
                kl_if(x, true, (clauses.length > 1 ? cons(intern("or"), list(clauses)) : clauses[0]));
        }

        @Macro
        public void and(Object x, Object... clauses) throws Exception {
            if (clauses.length == 0)
                bindTo(staticMH(ShenCode.class, "and", desc(boolean.class, boolean.class, boolean[].class)), x);
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
            mv.invokeStatic(getType(ShenCode.class), new Method("defun", desc(Symbol.class, Symbol.class, MethodHandle.class)));
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

        Class[] fillArray(Class value, int elements) {
            Class[] args = new Class[elements];
            fill(args, value);
            return args;
        }

        void bindTo(Handle handle, Object arg) {
            mv.push(handle);
            compile(arg);
            box();
            bindTo();
        }

        void bindTo() {
            mv.invokeStatic(getType(ShenCode.class), new Method("bindTo", desc(MethodHandle.class, MethodHandle.class, Object.class)));
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

        // RT
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

        static Object uncurry(Object chain, Object... args) throws Throwable {
            for (Object arg : args)
                chain = ((MethodHandle) chain).invoke(arg);
            return chain;
        }

        static boolean isLambda(MethodHandle fn) {
            return fn.type().parameterCount() == 1 && !fn.isVarargsCollector();
        }

        public static Symbol defun(Symbol name, MethodHandle fn) throws Throwable {
            name.fn.clear();
            name.fn.add(fn);
            return name;
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
    }
}
