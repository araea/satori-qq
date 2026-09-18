package com.satori.qq.xp;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * 新版 hook 兼容层的语义契约（0.22.0 起后端是模块自带的 LSPlant，不再是 libxposed 框架）。
 *
 * <p>这里不碰 native：直接建 {@link HookEntry}、用 {@code setBackupForTest} 装好备份方法，然后按
 * LSPlant 生成的桩那样调 {@code dispatch(Object[])}。钉住的是旧 Xposed 那套 before/after 语义——
 * 模块的 ~70 个钩子点都按那个写，错一个就会静默失效（最典型的是 setThrowable 被吞掉，
 * 「钩子看起来装了但没拦」）。
 */
public final class XposedShimTest {

    // ------------------------------------------------------------ fixtures

    public static class Target {
        public String tag = "T";
        public String seenArgs;
        public Object lastThis;

        public String greet(String who) {
            seenArgs = who;
            lastThis = this;
            return "hi " + who;
        }

        /** 读自身字段：proceed 时接收者没传对就会炸（或返回错值）。 */
        public String tagged(String who) { return tag + ":" + who; }

        public String boom() {
            throw new IllegalStateException("original failure");
        }

        public static int add(int a, int b) { return a + b; }
    }

    public static class Built {
        public final int value;

        public Built(int value) { this.value = value; }
    }

    private static final class Recorder extends XC_MethodHook {
        final List<String> events = new ArrayList<>();
        Object replacement;
        Throwable injected;
        boolean rewriteArgs;

        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            events.add("before");
            if (rewriteArgs && param.args.length > 0) param.args[0] = "rewritten";
            if (replacement != null) param.setResult(replacement);
            if (injected != null) param.setThrowable(injected);
        }

        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            events.add("after");
        }
    }

    // ------------------------------------------------------------ tests

    public static void main(String[] args) throws Throwable {
        afterSeesAndCanReplaceResult();
        beforeSetResultSkipsTheOriginal();
        argWritesReachTheOriginal();
        originalThrowableIsVisibleThenRethrown();
        beforeSetThrowableSkipsTheOriginal();
        replacementSkipsTheOriginal();
        thisObjectIsVisible();
        proceedKeepsTheReceiver();
        staticHooksRunWithoutThis();
        constructorHooksSeeArgs();
        severalCallbacksRunInOrder();
        fatalCallbackDoesNotBreakTheCall();
        helperResolutionStillWorks();
        hookEntryIsPubliclyReachable();
        System.out.println("XposedShimTest passed");
    }

    private static void afterSeesAndCanReplaceResult() throws Throwable {
        Target t = new Target();
        Recorder r = new Recorder();
        r.replacement = "hijacked";
        eq("hijacked", wire(t, method("greet", String.class), r, "world"), "before setResult wins");
        eq("before,after", String.join(",", r.events), "phases ran in order");
    }

    private static void beforeSetResultSkipsTheOriginal() throws Throwable {
        Target t = new Target();
        Recorder r = new Recorder();
        r.replacement = "short circuit";
        wire(t, method("greet", String.class), r, "world");
        eq(null, t.seenArgs, "original method not entered");
    }

    private static void argWritesReachTheOriginal() throws Throwable {
        Target t = new Target();
        Recorder r = new Recorder();
        r.rewriteArgs = true;
        eq("hi rewritten", wire(t, method("greet", String.class), r, "world"),
                "rewritten args reach the original");
        eq("rewritten", t.seenArgs, "target saw the rewritten arg");
    }

    private static void originalThrowableIsVisibleThenRethrown() throws Throwable {
        Target t = new Target();
        Recorder r = new Recorder();
        try {
            wire(t, method("boom"), r);
            throw new AssertionError("original throwable must be rethrown");
        } catch (IllegalStateException e) {
            eq("original failure", e.getMessage(), "same throwable type and message");
        }
        eq("before,after", String.join(",", r.events), "after runs even when the original threw");
    }

    private static void beforeSetThrowableSkipsTheOriginal() throws Throwable {
        Target t = new Target();
        Recorder r = new Recorder();
        r.injected = new UnsupportedOperationException("blocked");
        try {
            wire(t, method("greet", String.class), r, "world");
            throw new AssertionError("injected throwable must reach the caller");
        } catch (UnsupportedOperationException e) {
            eq("blocked", e.getMessage(), "injected throwable message");
        }
        eq(null, t.seenArgs, "original method not entered when a throwable was injected");
    }

    private static void replacementSkipsTheOriginal() throws Throwable {
        Target t = new Target();
        XC_MethodReplacement rep = XC_MethodReplacement.returnConstant("fixed");
        eq("fixed", wire(t, method("greet", String.class), rep, "world"), "replacement value");
        eq(null, t.seenArgs, "original method not entered");
    }

    private static void thisObjectIsVisible() throws Throwable {
        Target t = new Target();
        final Object[] seen = new Object[1];
        XC_MethodHook cb = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) { seen[0] = p.thisObject; }
        };
        wire(t, method("greet", String.class), cb, "world");
        check(seen[0] == t, "thisObject is the receiver");
    }

    /**
     * 备份方法（LSPlant 返回的那个）**必须带上接收者**调用。它自称 static，但实际是「挪过来的
     * 原方法」，ART 仍要接收者：传 null 会抛 "NullPointerException: null receiver"。
     * 这条断言是 2026-09-19 「QQ 卡启动界面」那次事故的钉子。
     */
    private static void proceedKeepsTheReceiver() throws Throwable {
        Target t = new Target();
        String got = (String) wire(t, method("tagged", String.class), new Recorder(), "world");
        eq("T:world", got, "proceed ran on the real receiver (读到的是 t.tag)");
    }

    private static void staticHooksRunWithoutThis() throws Throwable {
        Method m = method("add", int.class, int.class);
        final int[] got = new int[1];
        XC_MethodHook cb = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                got[0] = (Integer) p.getResult();
            }
        };
        HookEntry entry = entry(m, cb);
        eq(3, entry.dispatch(new Object[]{1, 2}), "static call result");
        eq(3, got[0], "after hook saw the result");
    }

    /** 构造器与实例方法同一套：args[0] 是「正在被构造的对象」，其余才是参数。 */
    private static void constructorHooksSeeArgs() throws Throwable {
        Constructor<?> c = Built.class.getConstructor(int.class);
        Object allocated = new Built(0);
        final Object[] seenThis = new Object[1];
        final Object[] seenArgs = new Object[1];
        XC_MethodHook cb = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                seenThis[0] = p.thisObject;
                seenArgs[0] = p.args.length == 1 ? p.args[0] : null;
            }
        };
        entry(c, cb).dispatch(new Object[]{allocated, 7});
        check(seenThis[0] == allocated, "constructor receiver is args[0]");
        eq(7, seenArgs[0], "constructor arg visible");
    }

    private static void severalCallbacksRunInOrder() throws Throwable {
        Target t = new Target();
        Method m = method("greet", String.class);
        Recorder first = new Recorder();
        Recorder second = new Recorder();
        HookEntry entry = entry(m, first);
        entry.add(second);
        eq("hi world", entry.dispatch(new Object[]{t, "world"}), "call goes through");
        eq("before,after", String.join(",", first.events), "first callback ran");
        eq("before,after", String.join(",", second.events), "second callback ran");
    }

    private static void fatalCallbackDoesNotBreakTheCall() throws Throwable {
        Target t = new Target();
        XC_MethodHook cb = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                throw new RuntimeException("hook is broken");
            }
        };
        eq("hi world", wire(t, method("greet", String.class), cb, "world"),
                "broken hook is swallowed");
        eq("world", t.seenArgs, "original still ran");
    }

    /** 反射辅助层没动（还是纯 java.lang.reflect），这里只钉住模块依赖的两个老行为。 */
    private static void helperResolutionStillWorks() throws Throwable {
        eq(3, XposedHelpers.callStaticMethod(Target.class, "add", 1, 2), "static call");
        // 装箱的 Integer 要能匹配 int 形参（旧实现的行为，Ref 依赖它）。
        eq(3, XposedHelpers.callStaticMethod(Target.class, "add",
                Integer.valueOf(1), Integer.valueOf(2)), "boxed args match primitives");
        Object built = XposedHelpers.newInstance(Built.class, 5);
        eq(5, XposedHelpers.getObjectField(built, "value"), "field via helper");
    }

    /**
     * LSPlant 生成的桩类（{@code LSPHooker_}）在匿名 dex 里、**没有包名**，只能访问 public 成员。
     * 钩子对象和回调方法只要有一个不是 public，每次调用都会抛 IllegalAccessError —— 那个异常
     * 出现在被钩方法体内，宿主进程直接崩（0.22.0 第一次装机就是这么把 QQ 卡在启动界面的，
     * 而当时的验证台只装了钩子、没真调用一次，所以没覆盖到）。这条断言就是那次事故的钉子。
     */
    private static void hookEntryIsPubliclyReachable() {
        check(java.lang.reflect.Modifier.isPublic(HookEntry.class.getModifiers()),
                "HookEntry must be public (the generated stub has no package)");
        int dispatch;
        try {
            dispatch = HookEntry.class.getMethod("dispatch", Object[].class).getModifiers();
        } catch (NoSuchMethodException e) {
            throw new AssertionError("HookEntry.dispatch(Object[]) is gone: " + e);
        }
        check(java.lang.reflect.Modifier.isPublic(dispatch), "HookEntry.dispatch must be public");
    }

    // ------------------------------------------------------------ harness

    private static Method method(String name, Class<?>... params) throws Exception {
        return Target.class.getMethod(name, params);
    }

    private static HookEntry entry(java.lang.reflect.Member target, XC_MethodHook cb)
            throws Exception {
        HookEntry entry = new HookEntry(target);
        entry.setBackupForTest(target);
        entry.add(cb);
        return entry;
    }

    /** 按 LSPlant 桩的形状调用：非静态方法的 args[0] 是 this。 */
    private static Object wire(Object receiver, Method target, XC_MethodHook cb, Object... args)
            throws Throwable {
        HookEntry entry = entry(target, cb);
        Object[] wireArgs = new Object[args.length + 1];
        wireArgs[0] = receiver;
        System.arraycopy(args, 0, wireArgs, 1, args.length);
        return entry.dispatch(wireArgs);
    }

    // ------------------------------------------------------------ assertions

    private static void check(boolean condition, String what) {
        if (!condition) throw new AssertionError(what);
    }

    private static void eq(Object expect, Object got, String what) {
        if (expect == null ? got != null : !expect.equals(got)) {
            throw new AssertionError(what + ": expected " + expect + " but got " + got);
        }
    }

    private XposedShimTest() {}
}
