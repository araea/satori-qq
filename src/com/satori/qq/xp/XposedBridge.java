package com.satori.qq.xp;

import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 静态 hook / 日志入口，保持旧 {@code de.robv.android.xposed.XposedBridge} 的形状。
 *
 * <p>0.22.0 起后端不再是 libxposed 框架，而是模块自带的 LSPlant（见 {@link Xp}）。这里只负责
 * 「一个成员一个 {@link HookEntry}」的登记与回调挂载：
 *
 * <ul>
 *   <li>同一个成员被钩多次时复用同一个 entry——LSPlant 对同一方法重复 hook 会直接跳过。</li>
 *   <li>{@code hookAllMethods} 只走已声明方法（与旧桥一致，不爬父类）。</li>
 *   <li>{@code log} 落到 logcat。</li>
 * </ul>
 */
public final class XposedBridge {

    private static final String TAG = "Q.Kernel";

    private static final Map<Member, HookEntry> ENTRIES = new ConcurrentHashMap<>();

    private XposedBridge() {}

    public static void log(String message) {
        Log.i(TAG, message);
    }

    public static void log(Throwable t) {
        if (t != null) Log.e(TAG, "internal error", t);
    }

    /**
     * 钩一个方法或构造器。钩不上时抛异常（旧桥同样如此，调用点按失败处理）。
     */
    public static XC_MethodHook.Unhook hookMethod(Member member, XC_MethodHook callback) {
        if (!(member instanceof Executable)) {
            throw new IllegalArgumentException("not a method or constructor: " + member);
        }
        // 注意：这里不能要求 Xp.attached()。引导期的第一个钩子（Boot 钩
        // Instrumentation.callApplicationOnCreate）正是在拿到宿主 classloader **之前**装的，
        // attached() 那会儿还是 false。引擎没就绪时 install() 会失败并抛出下面的异常。
        HookEntry entry = ENTRIES.get(member);
        if (entry == null) {
            HookEntry created = new HookEntry(member);
            HookEntry raced = ENTRIES.putIfAbsent(member, created);
            entry = raced != null ? raced : created;
            if (raced == null && !entry.install()) {
                ENTRIES.remove(member);
                throw new IllegalStateException("failed to hook " + member);
            }
        }
        entry.add(callback);
        return new XC_MethodHook.Unhook(entry, callback);
    }

    /** 钩 {@code clazz} 里所有叫 {@code methodName} 的已声明方法。 */
    public static Set<XC_MethodHook.Unhook> hookAllMethods(Class<?> clazz, String methodName,
                                                           XC_MethodHook callback) {
        Set<XC_MethodHook.Unhook> unhooks = new LinkedHashSet<>();
        for (Method m : clazz.getDeclaredMethods()) {
            if (m.getName().equals(methodName)) unhooks.add(hookMethod(m, callback));
        }
        return unhooks;
    }

    /** 钩 {@code clazz} 声明的所有构造器。 */
    public static Set<XC_MethodHook.Unhook> hookAllConstructors(Class<?> clazz,
                                                                XC_MethodHook callback) {
        Set<XC_MethodHook.Unhook> unhooks = new LinkedHashSet<>();
        for (Constructor<?> c : clazz.getDeclaredConstructors()) {
            unhooks.add(hookMethod(c, callback));
        }
        return unhooks;
    }

    /** 回调全部撤销后由 {@link XC_MethodHook.Unhook} 调用。 */
    static void uninstall(HookEntry entry) {
        ENTRIES.values().remove(entry);
        Xp.nativeUnhook(entry.target());
    }
}
