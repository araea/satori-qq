package com.satori.qq.xp;

import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.Set;

import io.github.libxposed.api.XposedInterface;

/**
 * Static hook/logging facade with the legacy {@code de.robv.android.xposed.XposedBridge} shape,
 * backed by the module instance the libxposed API 102 framework attached.
 *
 * <p>Only the pieces this module uses are here. Semantics that matter and are easy to get wrong:
 * {@code hookAllMethods} walks declared methods only (as the legacy bridge did), and {@code log}
 * still lands in the framework log — falling back to logcat in the module's own UI process, where
 * no framework is attached.
 */
public final class XposedBridge {

    private static final String TAG = "Q.Kernel";

    private XposedBridge() {}

    public static void log(String message) {
        try {
            Xp.api().log(Log.INFO, TAG, message);
        } catch (Throwable t) {
            Log.i(TAG, message);
        }
    }

    public static void log(Throwable t) {
        if (t == null) return;
        try {
            Xp.api().log(Log.ERROR, TAG, "internal error", t);
        } catch (Throwable ignored) {
            Log.e(TAG, "internal error", t);
        }
    }

    /**
     * Hooks one method or constructor. Throws if the executable cannot be hooked.
     *
     * <p>The exception mode is {@link XposedInterface.ExceptionMode#PASSTHROUGH}, not the
     * framework default: the legacy shape lets a before hook inject a throwable with
     * {@code setThrowable}, and under the protective default the framework swallows that
     * throwable and calls the original instead — the hiding silently stops working. Hook-body
     * faults are caught inside {@link XC_MethodHook} instead, which is what the legacy bridge
     * did, so passthrough only carries the exceptions a hook meant to raise.
     */
    public static XC_MethodHook.Unhook hookMethod(Member member, XC_MethodHook callback) {
        if (!(member instanceof Executable)) {
            throw new IllegalArgumentException("not a method or constructor: " + member);
        }
        XposedInterface.HookHandle handle = Xp.api().hook((Executable) member)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept(callback);
        return new XC_MethodHook.Unhook(handle);
    }

    /** Hooks every method of {@code clazz} named {@code methodName} (declared methods only). */
    public static Set<XC_MethodHook.Unhook> hookAllMethods(Class<?> clazz, String methodName,
                                                           XC_MethodHook callback) {
        Set<XC_MethodHook.Unhook> unhooks = new LinkedHashSet<>();
        for (Method m : clazz.getDeclaredMethods()) {
            if (m.getName().equals(methodName)) unhooks.add(hookMethod(m, callback));
        }
        return unhooks;
    }

    /** Hooks every constructor declared by {@code clazz}. */
    public static Set<XC_MethodHook.Unhook> hookAllConstructors(Class<?> clazz,
                                                                XC_MethodHook callback) {
        Set<XC_MethodHook.Unhook> unhooks = new LinkedHashSet<>();
        for (Constructor<?> c : clazz.getDeclaredConstructors()) unhooks.add(hookMethod(c, callback));
        return unhooks;
    }
}
