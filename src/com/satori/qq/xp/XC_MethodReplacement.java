package com.satori.qq.xp;

import io.github.libxposed.api.XposedInterface;

/**
 * Hook that answers in place of the original method, with no before/after phases.
 * {@link #returnConstant} is the shape every call site in this module uses.
 */
public class XC_MethodReplacement extends XC_MethodHook {

    protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
        return null;
    }

    @Override
    public final Object intercept(XposedInterface.Chain chain) throws Throwable {
        MethodHookParam param = new MethodHookParam();
        param.thisObject = chain.getThisObject();
        param.args = chain.getArgs().toArray(new Object[0]);
        try {
            return replaceHookedMethod(param);
        } catch (Throwable t) {
            // Legacy: a failing replacement was logged and the original call went through.
            android.util.Log.e("Q.Kernel", "replacement hook failed", t);
            return chain.proceed(param.args);
        }
    }

    public static XC_MethodReplacement returnConstant(final Object value) {
        return new XC_MethodReplacement() {
            @Override protected Object replaceHookedMethod(MethodHookParam param) {
                return value;
            }
        };
    }
}
