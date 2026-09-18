package com.satori.qq;

import android.app.Application;
import android.content.Context;

import com.satori.qq.xp.XC_MethodHook;
import com.satori.qq.xp.XposedBridge;
import com.satori.qq.xp.Xp;

import java.lang.reflect.Method;

/**
 * 注入后的引导：native 侧只调用 {@link #start(String)}，此时 ART hook 引擎已经就绪，
 * 但 QQ 的 Application（以及它的 classloader）还没建出来。
 *
 * <p>所以这里先钩 {@code Instrumentation.callApplicationOnCreate}——每个应用进程建完
 * Application 都会经过它，在它的 after 钩里拿到 Application 的 classloader，再交给
 * {@link Main}。用这个锚点是因为它只需要 boot classloader 就能挂上，是「能拿到宿主
 * classloader」的最早可靠时机。
 *
 * <p>进程过滤在 {@link Main} 里做（:MSF 也要进，只是不做桥接）。
 */
public final class Boot {

    private static volatile boolean started;

    private Boot() {}

    /** 由 native 在内嵌 dex 加载完成后调用。 */
    public static void start(String process) {
        if (started) return;
        started = true;
        L.i("zygisk bootstrap in " + process);
        try {
            hookApplicationCreate(process);
        } catch (Throwable t) {
            L.e("bootstrap failed in " + process, t);
        }
    }

    private static void hookApplicationCreate(final String process) throws Throwable {
        Class<?> instrumentation = Class.forName("android.app.Instrumentation");
        Method target = instrumentation.getDeclaredMethod("callApplicationOnCreate",
                Application.class);
        XposedBridge.hookMethod(target, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!(param.args != null && param.args.length == 1)
                        || !(param.args[0] instanceof Context)) {
                    return;
                }
                Context app = (Context) param.args[0];
                ClassLoader host = app.getClassLoader();
                if (host == null) return;
                Xp.attach(host, process);
                L.i("host classloader ready in " + process);
                Main.onHostReady(host, process);
            }
        });
        L.i("hooked Instrumentation.callApplicationOnCreate");
    }
}
