package com.satori.qq;

import com.satori.qq.xp.Xp;

/**
 * 注入后的引导入口：native 侧在轮询到宿主的 Application 之后调用 {@link #start(String, ClassLoader)}。
 *
 * <p>0.22.0 时这里还要钩 {@code Instrumentation.callApplicationOnCreate} 才能拿到宿主
 * classloader；0.23.0 起 native 直接轮询 {@code ActivityThread.currentApplication()}，这一层
 * 就不再需要任何钩子了。
 *
 * <p>本方法在 native 起的引导线程里跑（不是 QQ 的主线程），所以可以直接做重活。
 */
public final class Boot {

    private static volatile boolean started;

    private Boot() {}

    /** 由 native 在内嵌 dex 加载完成、且拿到宿主 classloader 之后调用。 */
    public static void start(String process, ClassLoader host) {
        if (started) return;
        started = true;
        if (host == null) {
            L.e("bootstrap without host classloader in " + process, null);
            return;
        }
        Xp.attach(host, process);
        L.i("zygisk bootstrap in " + process);
        try {
            Main.onHostReady(host, process);
        } catch (Throwable t) {
            L.e("bootstrap failed in " + process, t);
        }
    }
}
