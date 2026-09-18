package com.satori.qq.xp;

import android.util.Log;

import io.github.libxposed.api.XposedInterface;

/**
 * Legacy-shaped hook callback over the libxposed API 102 interceptor chain.
 *
 * <p>The framework's modern API is an interceptor chain, but the module's ~70 hook sites are
 * written in the old before/after style. This class reproduces that style faithfully:
 *
 * <ul>
 *   <li>{@code beforeHookedMethod} runs first; {@code setResult}/{@code setThrowable} there skips
 *   the original call (legacy {@code returnEarly}).</li>
 *   <li>{@code afterHookedMethod} always runs, including when the original threw; the throwable is
 *   visible through {@code getThrowable()} and is rethrown afterwards.</li>
 *   <li>Writes to {@code param.args} are passed on to the original.</li>
 * </ul>
 *
 * Exceptions from the callbacks themselves are logged and swallowed, the way the legacy bridge
 * did — a broken hook must not take the hooked method down with it.
 */
public class XC_MethodHook implements XposedInterface.Hooker {

    /** Handle for {@link XposedBridge#hookMethod}; {@link #unhook()} is idempotent. */
    public static final class Unhook {
        private final XposedInterface.HookHandle handle;

        Unhook(XposedInterface.HookHandle handle) { this.handle = handle; }

        public void unhook() {
            if (handle != null) handle.unhook();
        }
    }

    /** Same surface as the legacy {@code XC_MethodHook.MethodHookParam}. */
    public static class MethodHookParam {
        public Object thisObject;
        public Object[] args;

        private Object result;
        private Throwable throwable;
        private boolean returnEarly;

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

    @Override
    public Object intercept(XposedInterface.Chain chain) throws Throwable {
        MethodHookParam param = new MethodHookParam();
        try {
            param.thisObject = chain.getThisObject();
            param.args = chain.getArgs().toArray(new Object[0]);
        } catch (Throwable t) {
            // The hook cannot see the call; behave as if it were not installed.
            Log.e(TAG, "cannot read the hooked call", t);
            return chain.proceed();
        }

        try {
            beforeHookedMethod(param);
        } catch (Throwable t) {
            Log.e(TAG, "before hook failed", t);
        }

        if (!param.returnEarly) {
            try {
                param.result = chain.proceed(param.args);
                param.throwable = null;
            } catch (Throwable t) {
                param.throwable = t;
            }
        }

        try {
            afterHookedMethod(param);
        } catch (Throwable t) {
            Log.e(TAG, "after hook failed", t);
        }

        if (param.throwable != null) throw param.throwable;
        return param.result;
    }

    private static final String TAG = "Q.Kernel";
}
