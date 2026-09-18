package com.satori.qq.xp;

/**
 * 旧 Xposed 形状的 hook 回调，语义与 0.21.x（libxposed 兼容层）一致：
 *
 * <ul>
 *   <li>{@code beforeHookedMethod} 先跑；在它里面 {@code setResult}/{@code setThrowable}
 *   会跳过原方法调用。</li>
 *   <li>{@code afterHookedMethod} 总会跑，包括原方法抛异常时（异常从
 *   {@code getThrowable()} 拿），跑完再抛出去。</li>
 *   <li>改 {@code param.args} 会传给原方法。</li>
 * </ul>
 *
 * 回调自己抛的异常只记日志、吞掉——一个坏钩子不该把被钩方法带崩。
 */
public class XC_MethodHook {

    /** {@link XposedBridge#hookMethod} 的句柄；{@link #unhook()} 可重复调。 */
    public static final class Unhook {
        private final HookEntry entry;
        private final XC_MethodHook callback;

        Unhook(HookEntry entry, XC_MethodHook callback) {
            this.entry = entry;
            this.callback = callback;
        }

        public void unhook() {
            entry.remove(callback);
            if (entry.isEmpty()) XposedBridge.uninstall(entry);
        }
    }

    /** 与旧 {@code XC_MethodHook.MethodHookParam} 同面。 */
    public static class MethodHookParam {
        public Object thisObject;
        public Object[] args;

        // 包内可见：同包的 HookEntry 靠它驱动 before/proceed/after 流程
        Object result;
        Throwable throwable;
        boolean returnEarly;

        MethodHookParam() {}

        public Object getResult() { return result; }

        public void setResult(Object result) {
            this.result = result;
            this.returnEarly = true;
        }

        public Throwable getThrowable() { return throwable; }

        public void setThrowable(Throwable throwable) {
            this.throwable = throwable;
            this.returnEarly = true;
        }

        public boolean hasThrowable() { return throwable != null; }

        public Object getResultOrThrowable() throws Throwable {
            if (throwable != null) throw throwable;
            return result;
        }
    }

    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {}

    protected void afterHookedMethod(MethodHookParam param) throws Throwable {}
}
