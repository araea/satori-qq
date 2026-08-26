package de.robv.android.xposed;

/** compile-time stub only - real class provided by LSPosed/vector at runtime */
public abstract class XC_MethodReplacement extends XC_MethodHook {
    public XC_MethodReplacement() {}
    public XC_MethodReplacement(int priority) {}
    protected abstract Object replaceHookedMethod(MethodHookParam param) throws Throwable;
    public static final XC_MethodReplacement DO_NOTHING = new XC_MethodReplacement(10000) {
        protected Object replaceHookedMethod(MethodHookParam param) { return null; }
    };
    public static XC_MethodReplacement returnConstant(final Object result) { return null; }
}
