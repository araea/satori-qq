package com.onebot.qq;

import com.onebot.qq.core.MsgStore;
import com.onebot.qq.core.OneBotHub;
import com.onebot.qq.qq.QQClient;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** Xposed entry. Loads inside QQ's main process, captures the NT kernel, and serves OneBot 11. */
public final class Main implements IXposedHookLoadPackage {
    private static final String QQ_PKG = "com.tencent.mobileqq";
    private static volatile boolean started = false;

    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if (!QQ_PKG.equals(lp.packageName)) return;
        // Only run in QQ's main (UI) process — that's where the NT kernel session lives.
        boolean mainProcess = QQ_PKG.equals(lp.processName);
        if (!mainProcess) return;
        if (started) return;
        started = true;

        try {
            L.i("OneBot-QQ loading in process " + lp.processName);
            Cfg cfg = Cfg.load();
            MsgStore store = new MsgStore();
            QQClient qq = new QQClient(lp.classLoader, true);
            OneBotHub hub = new OneBotHub(cfg, qq, store);
            if (cfg.antiDetect) {
                try { new com.onebot.qq.qq.AntiDetect(lp.classLoader).install(); }
                catch (Throwable t) { L.e("AntiDetect install failed", t); }
            }
            hub.start();          // set listener + start WS server + heartbeat
            qq.installHooks();    // capture kernel session -> registers receive listener
            L.i("OneBot-QQ started (OneBot 11 forward-WS on port " + cfg.port + ")");
        } catch (Throwable t) {
            L.e("OneBot-QQ failed to start", t);
        }
    }
}
