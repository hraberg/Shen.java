package shen;

import org.objectweb.asm.*;
import org.objectweb.asm.commons.GeneratorAdapter;
import org.objectweb.asm.commons.Method;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.lang.invoke.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import static java.lang.System.out;
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
        return new ShenCode(read(shen).get(0)).load(Callable.class).newInstance().call();
    }

    public static CallSite bootstrap(Lookup lookup, String name, MethodType type) {
        name = unscramble(name);
        System.out.println("BOOTSTRAP: " + name + type);
        Symbol symbol = intern(name);
        System.out.println("candidates: " + symbol.fn);
        MethodHandle match = some(symbol.fn.stream(), f -> hasMatchingSignature(f, type, Class::isAssignableFrom));
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
        if (match.type().parameterCount() > type.parameterCount()) {
            try {
                System.out.println("Partial " + type.parameterCount() + " out of " + match.type().parameterCount());
                match = insertArguments(insertArguments, 0, match, 0).asCollector(Object[].class, type.parameterCount());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        System.out.println("selected: " + match);
        return new MutableCallSite(explicitCastArguments(match, type));
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
            this.locals = new HashMap<>();
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

                    Object first = list.get(0);
                    if (first instanceof Symbol) {
                        Symbol s = (Symbol) first;
                        if (macros.containsKey(s)) macroExpand(s, tl(list));
                        else indy(s, tl(list));

                    } else {
                        compile(first);
                        apply(tl(list));
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

        void symbol(Symbol s) {
            if (locals.containsKey(s)) {
                int local = locals.get(s);
                mv.loadLocal(local);
                topOfStack = mv.getLocalType(local);
            } else if (args.contains(s)) {
                mv.loadArg(args.indexOf(s));
                topOfStack(Object.class);
            } else
                push(s);
        }

        void macroExpand(Symbol s, List<Object> args) throws Throwable {
            bindTo(macros.get(s), this).invokeWithArguments(args);
        }

        void indy(Symbol s, List<Object> args) {
            List<Type> argumentTypes = args.stream().map(this::compile).into(new ArrayList<Type>());

            MethodType type = asMethodType(getType(Object.class), argumentTypes);
            mv.invokeDynamic(scramble(s.symbol), type.toMethodDescriptorString(), bootstrap);
            topOfStack(type.returnType());
        }

        void apply(List<Object> args) {
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
            return fromMethodDescriptorString(getMethodDescriptor(returnType,
                    argumentTypes.toArray(new Type[argumentTypes.size()])), loader);
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
            compileMethod(ACC_PUBLIC, sam.getName(), sam.getReturnType(), sam.getParameterTypes());
            //noinspection unchecked
            return (Class<T>) loader.define(cn);
        }

        void compileMethod(int modifier, String name, Class<?> returnType, Class<?>... argumentTypes) {
            mv = generator(cn.visitMethod(modifier, name, desc(returnType, argumentTypes), null, null));
            recur = mv.newLabel();
            mv.visitLabel(recur);
            compile(shen);
            if (!returnType.isPrimitive()) box();
            mv.returnValue();
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
            int e = mv.newLocal(getType(Exception.class));
            mv.storeLocal(e);

            compile(f);
            mv.checkCast(getType(MethodHandle.class));

            mv.loadLocal(e);
            bindTo();

            mv.invokeVirtual(getType(MethodHandle.class), new Method("invoke", desc(Object.class)));

            mv.visitLabel(after);
        }

        @Macro
        public void kl_if(Object test, Object then, Object _else) throws Exception {
            Label elseStart = mv.newLabel();
            Label end = mv.newLabel();

            compile(test);
            if (!topOfStack.equals(getType(boolean.class)))
                mv.unbox(getType(Boolean.class));
            mv.visitJumpInsn(IFEQ, elseStart);

            compile(then);
            mv.goTo(end);

            mv.visitLabel(elseStart);
            compile(_else);

            mv.visitLabel(end);
        }

        @Macro
        public void cond(List... clauses) throws Exception {
            if (clauses.length == 0) {
                mv.throwException(getType(IllegalArgumentException.class), "condition failure");
            } else {
                kl_if(hd(clauses).get(0), hd(clauses).get(1), cons(intern("cond"), list((Object[]) tl(clauses))));
            }
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

        void fn(String name, Object shen, Symbol... args) throws Throwable {
            List<Symbol> scope = locals.keySet().stream().into(new ArrayList<>(this.args));
            scope.addAll(asList(args));

            ShenCode fn = new ShenCode(cn, shen, scope.toArray(new Symbol[scope.size()]));

            Class[] argumentTypes = fillArray(Object.class, scope.size());
            fn.compileMethod(ACC_PUBLIC | ACC_STATIC, name, Object.class, argumentTypes);

            insertArgs(staticMH(cn.name, name, desc(Object.class, argumentTypes)), 0, scope.subList(0, scope.size() - args.length));
        }

        @Macro
        public void let(Symbol x, Object y, Object z) throws Throwable {
            compile(y);
            box();
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
            return Shen.apply(fn, asList(args));
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

    public static void main(String[] args) throws Throwable {
        out.println(eval("(trap-error my-symbol my-handler)"));
        out.println(eval("(trap-error (simple-error \"!\") (lambda x x))"));
        out.println(eval("(if true \"true\" \"false\")"));
        out.println(eval("(if false \"true\" \"false\")"));
        out.println(eval("(cond (false 1) (true 2))"));
        out.println(eval("(cond (false 1) ((or true false) 3))"));
        out.println(eval("(or false)"));
        out.println(((MethodHandle) eval("(or false)")).invokeWithArguments(false, true));
        out.println(eval("((or false) false)"));
        out.println(eval("(or false false)"));
        out.println(eval("(or false true false)"));
        out.println(eval("(and true true)"));
        out.println(eval("(and true true (or false false))"));
        out.println(eval("(and true false)"));
        out.println(eval("(and true)"));
        out.println(eval("(lambda x x)"));
        out.println(eval("((lambda x x) 2)"));
        out.println(eval("(let x \"str\" x)"));
        out.println(eval("(let x 10 x)"));
        out.println(eval("(let x 10 (let y 5 x))"));
        out.println(eval("((let x 42 (lambda y x)) 0)"));
        out.println(eval("((lambda x ((lambda y x) 42)) 0)"));
        out.println(eval("(get-time unix)"));
        out.println(eval("(value *language*)"));
        out.println(eval("(+ 1 1)"));
        out.println(eval("(+ 1.2 1.1)"));
        out.println(eval("(+ 1.2 1)"));
        out.println(eval("(+ 1 1.3)"));
        out.println(eval("(cons x y)"));
        out.println(eval("(cons x)"));
        out.println(eval("((cons x) z)"));
        out.println(eval("(cons x y)"));
        out.println(eval("(hd (cons x y))"));
        out.println(eval("(absvector? (absvector 10))"));
        out.println(eval("(trap-error (/ 1 0) (lambda x x))"));
    }
}
