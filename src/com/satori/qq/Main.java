package com.satori.qq;

import com.satori.qq.core.MsgStore;
import com.satori.qq.core.SatoriHub;
import com.satori.qq.qq.QQClient;
import com.satori.qq.qq.Ref;
import com.satori.qq.xp.Xp;

import java.io.FileInputStream;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * Module entry (libxposed API 102). Anti-detect runs in every QQ process (main + MSF);
 * the Satori bridge only in the main process.
 */
public final class Main extends XposedModule {
    private static final String QQ_PKG = "com.tencent.mobileqq";
    private static volatile boolean started = false;

    private String processName;

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        Xp.attach(this);
        processName = param.getProcessName();
    }

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        if (!QQ_PKG.equals(param.getPackageName())) return;
        if (started) return;
        started = true;

        String process = processName != null && !processName.isEmpty()
                ? processName : currentProcessName();
        boolean mainProcess = QQ_PKG.equals(process);
        try {
            Cfg cfg = Cfg.load();
            L.configure(cfg.verboseLogs);
            if (!mainProcess) {
                L.i("bridge skipped in process " + process);
                return;
            }

            L.i("bridge loading in process " + process);
            MsgStore store = new MsgStore();
            QQClient qq = new QQClient(param.getClassLoader(), true);
            SatoriHub hub = new SatoriHub(cfg, qq, store);
            hub.start();
            qq.installHooks();
            L.i("bridge initialization scheduled");
        } catch (Throwable t) {
            L.e("bridge failed to start", t);
        }
    }

    /** Fallback for {@code ModuleLoadedParam.getProcessName()}, which is empty in odd loaders. */
    private static String currentProcessName() {
        try {
            FileInputStream in = new FileInputStream("/proc/self/cmdline");
            byte[] buf = new byte[256];
            int n;
            try { n = in.read(buf); } finally { in.close(); }
            if (n <= 0) return "";
            int end = 0;
            while (end < n && buf[end] != 0) end++;
            return new String(buf, 0, end, "UTF-8");
        } catch (Throwable t) {
            return "";
        }
    }
}
