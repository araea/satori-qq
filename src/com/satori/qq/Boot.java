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

    /** native 侧的诊断档位（交接文档 §4）：见 native/satori.cpp 里的 kModeFile。 */
    private static final String MODE_BOOT = "boot";

    private static volatile boolean started;

    private Boot() {}

    /** 由 native 在内嵌 dex 加载完成后调用。 */
    public static void start(String process) {
        if (started) return;
        started = true;
        final String mode = System.getProperty("satori.zygisk.mode", "hooks");
        L.i("zygisk bootstrap in " + process + " mode=" + mode);
        try {
            hookApplicationCreate(process, mode);
        } catch (Throwable t) {
            L.e("bootstrap failed in " + process, t);
        }
    }

    private static void hookApplicationCreate(final String process, final String mode) throws Throwable {
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
                L.i("host classloader ready in " + process + " mode=" + mode);
                // 引导的重活（装 ~75 个钩子、起 HTTP 服务）不能跑在这个回调里：它位于
                // Application 创建路径上，同步做完会把 QQ 启动拖住甚至卡死，而且此时栈上
                // 可能正停在我们要钩的方法里。丢到独立线程，QQ 照常启动。
                Thread boot = new Thread(() -> {
                    Xp.attach(host, process);
                    if (MODE_BOOT.equals(mode)) {
                        // 诊断档：只证明「引擎 + 一个引导锚点」能拿到宿主 classloader，
                        // 不装任何 QQ 的钩子、不起服务。人脸验证的结果决定这一层是否干净。
                        L.i("mode=boot: classloader captured, stopping before any QQ hook");
                        return;
                    }
                    Main.onHostReady(host, process);
                }, "satori-boot");
                boot.start();
            }
        });
        L.i("hooked Instrumentation.callApplicationOnCreate mode=" + mode);
    }
}
