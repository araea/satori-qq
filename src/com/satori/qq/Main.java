package com.satori.qq;

import com.satori.qq.core.MsgStore;
import com.satori.qq.core.SatoriHub;
import com.satori.qq.qq.EnvProbe;
import com.satori.qq.qq.EnvShield;
import com.satori.qq.qq.QQClient;
import com.satori.qq.xp.Xp;

/**
 * 桥接入口（0.22.0 起由 {@link Boot} 从 Zygisk 注入路径调进来，不再由 libxposed 框架回调）。
 *
 * <p>每个 QQ 进程都会走到这里：主进程起 Satori 桥接，其余进程（:MSF 等）什么都不做——
 * 钩子只在需要它们的进程里装。
 */
public final class Main {

    private static final String QQ_PKG = "com.tencent.mobileqq";
    private static volatile boolean started = false;

    private Main() {}

    /**
     * QQ 的 Application 建好之后调用一次。
     *
     * @param host    QQ 的 classloader（查它的混淆类要用）
     * @param process 进程名，主进程就是包名本身
     */
    public static void onHostReady(ClassLoader host, String process) {
        if (started) return;
        started = true;

        boolean mainProcess = QQ_PKG.equals(process);
        try {
            Cfg cfg = Cfg.load();
            L.configure(cfg.verboseLogs);
            if (!mainProcess) {
                L.i("bridge skipped in process " + process);
                return;
            }
            if (!Xp.attached()) {
                L.e("hook runtime not attached in " + process, null);
                return;
            }

            EnvShield.install(host);
            EnvProbe.hold(host);
            L.i("bridge loading in process " + process);
            MsgStore store = new MsgStore();
            QQClient qq = new QQClient(host, true);
            SatoriHub hub = new SatoriHub(cfg, qq, store);
            hub.start();
            qq.installHooks();
            L.i("bridge initialization scheduled");
        } catch (Throwable t) {
            L.e("bridge failed to start", t);
        }
    }
}
