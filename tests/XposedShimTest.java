import com.satori.qq.xp.XC_MethodHook;
import com.satori.qq.xp.XC_MethodReplacement;
import com.satori.qq.xp.Xp;
import com.satori.qq.xp.XposedBridge;
import com.satori.qq.xp.XposedHelpers;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

import java.lang.reflect.Executable;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Contract of the libxposed compat layer: the legacy before/after hook shape must behave exactly
 * as the old bridge did, and the reflection facade must resolve the calls {@code Ref} makes.
 * Runs on the JVM with a fake {@link XposedInterface.Chain}, no device needed.
 */
public final class XposedShimTest {

    // ------------------------------------------------------------ fixtures

    private static class Base {
        private String inherited = "base";
        private long counter = 7L;
    }

    private static final class Sample extends Base {
        private String name = "sample";

        public String greet(String who) { return "hi " + who; }

        public int add(int a, int b) { return a + b; }

        public String describe(String prefix, Object extra) { return prefix + ":" + extra; }

        public static String staticGreet(String who) { return "static " + who; }

        public static Object fail() { throw new IllegalStateException("boom"); }

        public Sample() {}

        public Sample(String name) { this.name = name; }
    }

    /** Minimal stand-in for the framework chain; records what the hook actually did. */
    private static final class FakeChain implements XposedInterface.Chain {
        final Object self;
        final Object[] args;
        Object result;
        Throwable error;
        int proceeds;
        Object[] seenArgs;

        FakeChain(Object self, Object[] args, Object result) {
            this.self = self;
            this.args = args == null ? new Object[0] : args;
            this.result = result;
        }

        @Override public Executable getExecutable() {
            try {
                return Sample.class.getDeclaredMethod("greet", String.class);
            } catch (NoSuchMethodException e) {
                throw new AssertionError(e);
            }
        }

        @Override public Object getThisObject() { return self; }

        @Override public List<Object> getArgs() {
            return Collections.unmodifiableList(Arrays.asList(args));
        }

        @Override public Object getArg(int index) { return args[index]; }

        @Override public Object proceed() throws Throwable { return proceed(args); }

        @Override public Object proceed(Object[] a) throws Throwable {
            proceeds++;
            seenArgs = a;
            if (error != null) throw error;
            return result;
        }

        @Override public Object proceedWith(Object thisObject) throws Throwable {
            return proceed(args);
        }

        @Override public Object proceedWith(Object thisObject, Object[] a) throws Throwable {
            return proceed(a);
        }
    }

    // ------------------------------------------------------------ tests

    public static void main(String[] args) throws Throwable {
        afterHookSeesAndCanReplaceResult();
        beforeSetResultSkipsTheOriginal();
        argWritesReachTheOriginal();
        originalThrowableIsVisibleThenRethrown();
        beforeSetThrowableSkipsTheOriginal();
        replacementSkipsTheOriginal();
        thisObjectIsVisible();
        fieldsResolveUpTheHierarchy();
        methodResolutionMatchesBoxedAndInherited();
        staticCallsAndConstructors();
        invokedExceptionsKeepTheirType();
        missingClassAndMethodReportCleanly();
        injectedThrowablesUsePassthroughMode();
        System.out.println("XposedShimTest OK");
    }

    private static void afterHookSeesAndCanReplaceResult() throws Throwable {
        XC_MethodHook hook = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                eq(41, p.getResult(), "after hook sees the original result");
                p.setResult(((Integer) p.getResult()) + 1);
            }
        };
        FakeChain chain = new FakeChain(null, new Object[] {1, 2}, 41);
        eq(42, hook.intercept(chain), "after hook can replace the result");
        eq(1, chain.proceeds, "original ran once");
    }

    private static void beforeSetResultSkipsTheOriginal() throws Throwable {
        XC_MethodHook hook = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) { p.setResult(7); }
            @Override protected void afterHookedMethod(MethodHookParam p) {
                eq(7, p.getResult(), "after hook sees the injected result");
            }
        };
        FakeChain chain = new FakeChain(null, new Object[0], 99);
        eq(7, hook.intercept(chain), "before hook short-circuits");
        eq(0, chain.proceeds, "original skipped");
    }

    private static void argWritesReachTheOriginal() throws Throwable {
        XC_MethodHook hook = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) { p.args[0] = "changed"; }
        };
        FakeChain chain = new FakeChain(null, new Object[] {"original"}, null);
        hook.intercept(chain);
        eq("changed", chain.seenArgs[0], "argument write reaches the original");
    }

    private static void originalThrowableIsVisibleThenRethrown() throws Throwable {
        final boolean[] seen = {false};
        XC_MethodHook hook = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                seen[0] = p.getThrowable() instanceof IllegalStateException;
            }
        };
        FakeChain chain = new FakeChain(null, new Object[0], null);
        chain.error = new IllegalStateException("boom");
        boolean rethrown = false;
        try {
            hook.intercept(chain);
        } catch (Throwable t) {
            rethrown = t instanceof IllegalStateException;
        }
        check(rethrown, "original throwable is rethrown");
        check(seen[0], "after hook sees the throwable");
    }

    private static void beforeSetThrowableSkipsTheOriginal() throws Throwable {
        XC_MethodHook hook = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                p.setThrowable(new UnsupportedOperationException("denied"));
            }
        };
        FakeChain chain = new FakeChain(null, new Object[0], "never");
        boolean thrown = false;
        try {
            hook.intercept(chain);
        } catch (UnsupportedOperationException e) {
            thrown = true;
        }
        check(thrown, "setThrowable in a before hook propagates");
        eq(0, chain.proceeds, "original skipped");
    }

    private static void replacementSkipsTheOriginal() throws Throwable {
        FakeChain chain = new FakeChain(null, new Object[0], "original");
        eq(false, XC_MethodReplacement.returnConstant(false).intercept(chain), "constant returned");
        eq(0, chain.proceeds, "replacement never proceeds");
        eq(new byte[0].length,
                ((byte[]) XC_MethodReplacement.returnConstant(new byte[0]).intercept(chain)).length,
                "byte[] constant returned");
    }

    private static void thisObjectIsVisible() throws Throwable {
        final Object[] observed = {null};
        XC_MethodHook hook = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) { observed[0] = p.thisObject; }
        };
        Object self = new Object();
        hook.intercept(new FakeChain(self, new Object[0], null));
        check(observed[0] == self, "thisObject reaches the hook");
    }

    private static void fieldsResolveUpTheHierarchy() {
        Sample sample = new Sample();
        eq("sample", XposedHelpers.getObjectField(sample, "name"), "own field");
        eq("base", XposedHelpers.getObjectField(sample, "inherited"), "inherited private field");
        eq(7L, XposedHelpers.getLongField(sample, "counter"), "inherited long field");
        XposedHelpers.setObjectField(sample, "name", "right");
        eq("right", XposedHelpers.getObjectField(sample, "name"), "field write");
        eq("Sample", XposedHelpers.findField(Sample.class, "name").getDeclaringClass().getSimpleName(),
                "findField");
    }

    private static void methodResolutionMatchesBoxedAndInherited() {
        Sample sample = new Sample();
        eq("hi bob", XposedHelpers.callMethod(sample, "greet", "bob"), "plain call");
        // Boxed Integer must resolve to add(int,int), the way the legacy helper did.
        eq(3, XposedHelpers.callMethod(sample, "add", 1, 2), "boxed primitives");
        eq("x:null", XposedHelpers.callMethod(sample, "describe", "x", null), "null argument");
        eq("x:1", XposedHelpers.callMethod(sample, "describe", "x", (Object) 1), "object argument");
        eq("hi typed", XposedHelpers.callMethod(sample, "greet", new Class<?>[] {String.class}, "typed"),
                "explicit parameter types");
    }

    private static void staticCallsAndConstructors() {
        eq("static bob", XposedHelpers.callStaticMethod(Sample.class, "staticGreet", "bob"),
                "static call");
        eq("named", ((Sample) XposedHelpers.newInstance(Sample.class, "named")).name,
                "constructor by argument types");
        eq("typed", ((Sample) XposedHelpers.newInstance(Sample.class,
                new Class<?>[] {String.class}, new Object[] {"typed"})).name,
                "constructor with explicit types");
        check(XposedHelpers.newInstance(Sample.class) instanceof Sample, "no-arg constructor");
    }

    private static void invokedExceptionsKeepTheirType() {
        boolean kept = false;
        try {
            XposedHelpers.callStaticMethod(Sample.class, "fail");
        } catch (IllegalStateException e) {
            kept = "boom".equals(e.getMessage());
        }
        check(kept, "the invoked method's own exception is rethrown as itself");
    }

    private static void missingClassAndMethodReportCleanly() {
        eq(null, XposedHelpers.findClassIfExists("no.such.Class", XposedShimTest.class.getClassLoader()),
                "findClassIfExists returns null");
        boolean missing = false;
        try {
            XposedHelpers.findClass("no.such.Class", XposedShimTest.class.getClassLoader());
        } catch (NoClassDefFoundError e) {
            missing = true;
        }
        check(missing, "findClass fails loudly");
        boolean noMethod = false;
        try {
            XposedHelpers.callMethod(new Sample(), "nope");
        } catch (NoSuchMethodError e) {
            noMethod = true;
        }
        check(noMethod, "missing method reported");
    }

    /**
     * Regression guard: under the framework's default protective mode a throwable raised from
     * inside a hooker is swallowed and the original call runs instead, which silently disables
     * every hide-by-setThrowable hook. The facade has to ask for passthrough.
     */
    private static void injectedThrowablesUsePassthroughMode() throws Exception {
        final List<String> calls = new ArrayList<>();
        InvocationHandler builder = (proxy, method, args) -> {
            String name = method.getName();
            if ("setExceptionMode".equals(name)) { calls.add("mode=" + args[0]); return proxy; }
            if ("intercept".equals(name)) { calls.add("intercept"); return hookHandle(); }
            return null;
        };
        final Object hookBuilder = Proxy.newProxyInstance(
                XposedShimTest.class.getClassLoader(),
                new Class<?>[] {XposedInterface.HookBuilder.class}, builder);
        InvocationHandler api = (proxy, method, args) -> {
            String name = method.getName();
            if ("hook".equals(name)) { calls.add("hook"); return hookBuilder; }
            if ("getFrameworkVersionCode".equals(name) || "getFrameworkProperties".equals(name)) return 0L;
            return null;
        };
        XposedInterface framework = (XposedInterface) Proxy.newProxyInstance(
                XposedShimTest.class.getClassLoader(),
                new Class<?>[] {XposedInterface.class}, api);

        XposedModule module = new XposedModule() {};
        module.attachFramework(framework, () -> {});
        Xp.attach(module);
        XposedBridge.hookMethod(Sample.class.getDeclaredMethod("greet", String.class),
                new XC_MethodHook() {});

        check(calls.contains("hook"), "hook() reached the framework");
        check(calls.contains("mode=" + XposedInterface.ExceptionMode.PASSTHROUGH),
                "hook installed with passthrough mode, got " + calls);
        check(calls.contains("intercept"), "intercept() reached the builder");
    }

    private static Object hookHandle() {
        InvocationHandler handler = (proxy, method, args) -> null;
        return Proxy.newProxyInstance(XposedShimTest.class.getClassLoader(),
                new Class<?>[] {XposedInterface.HookHandle.class}, handler);
    }

    // ------------------------------------------------------------ assertions

    private static void check(boolean condition, String what) {
        if (!condition) throw new AssertionError(what);
    }

    private static void eq(Object expect, Object got, String what) {
        if (expect == null ? got != null : !expect.equals(got)) {
            throw new AssertionError(what + ": expect <" + expect + "> got <" + got + ">");
        }
    }
}
