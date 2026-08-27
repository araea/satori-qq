package com.onebot.qq.qq;

import com.onebot.qq.L;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;

import java.lang.reflect.Method;

/**
 * Best-effort anti-detection for QQ's "device environment unsafe" / forced re-login.
 *
 * The signing path stays intact. Experimental task/report suppression is separately configurable
 * so it can be A/B tested and rolled back without changing the stable Java probe neutralisation.
 *
 * What we hook (pure detection helpers, safe to neutralise):
 *  - QSec.detectMethod(String cls, String method)  -> false   (defeats class/method-existence probes)
 *  - QSec.getXpsInfo()                              -> empty   (replacement: original collector never runs)
 *  - optional QSec.execTasks/reportLog              -> 0       (experimental native worker/telemetry suppression)
 */
public final class AntiDetect {
    private static final String QSEC = "com.tencent.mobileqq.qsec.qsecurity.QSec";

    private final Ref ref;
    private final boolean blockTasks;
    private final boolean blockReports;
    public AntiDetect(ClassLoader cl, boolean blockTasks, boolean blockReports) {
        this.ref = new Ref(cl);
        this.blockTasks = blockTasks;
        this.blockReports = blockReports;
    }

    public void install() {
        hookDetectMethod();
        hookGetXpsInfo();
        if (blockTasks) hookIntMethod("execTasks", 2);
        if (blockReports) hookIntMethod("reportLog", 4);
    }

    /** QSec.detectMethod(cls, method): returns true if the class defines that method — used to
     *  sniff hook frameworks (e.g. de.robv.android.xposed.XposedBridge.log). Force false. */
    private void hookDetectMethod() {
        try {
            Class<?> qsec = ref.clsOrNull(QSEC);
            if (qsec == null) { L.w("AntiDetect: QSec not found, skip detectMethod"); return; }
            Method m = findMethod(qsec, "detectMethod", 2);
            if (m == null) { L.w("AntiDetect: detectMethod not found"); return; }
            XposedBridge.hookMethod(m, XC_MethodReplacement.returnConstant(false));
            L.i("AntiDetect: neutralised QSec.detectMethod");
        } catch (Throwable t) {
            L.e("AntiDetect.detectMethod", t);
        }
    }

    /** QSec.getXpsInfo(): collects Xposed info for the risk report. Return an empty byte[]. */
    private void hookGetXpsInfo() {
        try {
            Class<?> qsec = ref.clsOrNull(QSEC);
            if (qsec == null) return;
            Method m = findMethod(qsec, "getXpsInfo", 0);
            if (m == null) { L.w("AntiDetect: getXpsInfo not found"); return; }
            // Replacement matters: an after-hook still lets T.ad(...) collect data and side effects.
            XposedBridge.hookMethod(m, XC_MethodReplacement.returnConstant(new byte[0]));
            L.i("AntiDetect: neutralised QSec.getXpsInfo");
        } catch (Throwable t) {
            L.e("AntiDetect.getXpsInfo", t);
        }
    }

    private void hookIntMethod(String name, int argc) {
        try {
            Class<?> qsec = ref.clsOrNull(QSEC);
            if (qsec == null) return;
            Method m = findMethod(qsec, name, argc);
            if (m == null || m.getReturnType() != int.class) {
                L.w("AntiDetect: " + name + " not found");
                return;
            }
            XposedBridge.hookMethod(m, XC_MethodReplacement.returnConstant(0));
            L.i("AntiDetect: experimental block " + name);
        } catch (Throwable t) {
            L.e("AntiDetect." + name, t);
        }
    }

    private static Method findMethod(Class<?> c, String name, int argc) {
        for (Method m : c.getDeclaredMethods()) {
            if (m.getName().equals(name) && m.getParameterTypes().length == argc) {
                m.setAccessible(true);
                return m;
            }
        }
        return null;
    }
}
