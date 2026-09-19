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
        awaitApplicationBound();
        try {
            Main.onHostReady(host, process);
        } catch (Throwable t) {
            L.e("bootstrap failed in " + process, t);
        }
    }

    /**
     * 等宿主的 Application 真正绑定完。
     *
     * <p>native 侧是从 {@code ActivityThread.currentApplication()} 拿到 Application 的，而那个
     * 字段在 {@code handleBindApplication} 中途就被赋值——**ContentProvider 还没装**。此时去问
     * QQ 那边要 {@code com.satori.qq.control}，只会拿到
     * {@code provider-unavailable: Failed to find provider info}（2026-09-19 首启实测）。
     *
     * <p>往主线程的 looper 投一个空任务再等它跑完：主线程此刻还在
     * {@code handleBindApplication} 里，任务只会在它返回、{@code Looper.loop()} 之后才被执行。
     */
    private static void awaitApplicationBound() {
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        try {
            new android.os.Handler(android.os.Looper.getMainLooper()).post(latch::countDown);
            if (!latch.await(15, java.util.concurrent.TimeUnit.SECONDS)) {
                L.e("main looper did not come up in 15s", null);
            }
        } catch (Throwable t) {
            L.e("awaitApplicationBound failed", t);
        }
    }
}
