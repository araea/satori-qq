package com.satori.qq;

import com.satori.qq.core.MsgStore;
import com.satori.qq.core.SatoriHub;
import com.satori.qq.qq.AntiDetect;
import com.satori.qq.qq.MapsHide;
import com.satori.qq.qq.QQClient;
import com.satori.qq.qq.Ref;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** Xposed entry. Anti-detect runs in every QQ process (main + MSF). Satori only in main. */
public final class Main implements IXposedHookLoadPackage {
    private static final String QQ_PKG = "com.tencent.mobileqq";
    private static volatile boolean started = false;

    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if (!QQ_PKG.equals(lp.packageName)) return;
        if (started) return;
        started = true;

        boolean mainProcess = QQ_PKG.equals(lp.processName);
        try {
            Cfg cfg = Cfg.load();
            L.configure(cfg.verboseLogs);
            if (cfg.mapsHide) {
                try { MapsHide.tryLoad(new Ref(lp.classLoader)); }
                catch (Throwable t) { L.e("MapsHide load failed", t); }
            }
            if (cfg.antiDetect) {
                try {
                    new AntiDetect(lp.classLoader, cfg.blockQsecTasks, cfg.blockQsecReports,
                            cfg.observeFekitAttach, cfg.blockO3Report).install();
                } catch (Throwable t) { L.e("AntiDetect install failed", t); }
            }
            if (!mainProcess) {
                L.i("anti-detect only in process " + lp.processName);
                return;
            }

            L.i("bridge loading in process " + lp.processName);
            MsgStore store = new MsgStore();
            QQClient qq = new QQClient(lp.classLoader, true);
            SatoriHub hub = new SatoriHub(cfg, qq, store);
            hub.start();
            qq.installHooks();
            L.i("bridge started on local port " + cfg.port);
        } catch (Throwable t) {
            L.e("bridge failed to start", t);
        }
    }
}
