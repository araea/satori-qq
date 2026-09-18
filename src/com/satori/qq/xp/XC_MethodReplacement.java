package com.satori.qq.xp;

/**
 * 原位置换：不走 before/after，直接给出返回值。{@link #returnConstant} 是本模块用到的唯一形状。
 *
 * <p>置换体自己抛异常时按旧 Xposed 的做法记日志并放行原方法。
 */
public class XC_MethodReplacement extends XC_MethodHook {

    protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
        return null;
    }

    @Override
    protected final void beforeHookedMethod(MethodHookParam param) {
        try {
            param.setResult(replaceHookedMethod(param));
        } catch (Throwable t) {
            HookEntry.logFailure("replacement hook failed", t);
        }
    }

    /** 让被钩方法直接返回 {@code value}。 */
    public static XC_MethodReplacement returnConstant(final Object value) {
        return new XC_MethodReplacement() {
            @Override protected Object replaceHookedMethod(MethodHookParam param) {
                return value;
            }
        };
    }
}
