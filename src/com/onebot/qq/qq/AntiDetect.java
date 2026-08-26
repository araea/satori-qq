package com.onebot.qq.qq;

import com.onebot.qq.L;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;

import java.lang.reflect.Method;

/**
 * Best-effort anti-detection for QQ's "device environment unsafe" / forced re-login.
 *
 * IMPORTANT / HONEST LIMITATIONS:
 *  - QQ's real gatekeeper is the NATIVE security SDK libfekit.so (QSec.getSign / doSomething /
 *    Dandelion.energy are all JNI). It scans /proc/self/maps and the process natively and reports
 *    device risk to the server, which then forces the re-login/verify flow. A Java Xposed module
 *    CANNOT stop that native scan. So this class only neutralises the JAVA-level detection seams,
 *    which reduces — but does not guarantee removal of — the risk trigger.
 *  - We deliberately DO NOT hook the signing methods (getSign/getSignEntry/getEstInfo/doSomething/
 *    energy) — QQ needs those to produce a valid login signature; faking them breaks login entirely.
 *  - For a reliable fix you must hide the Xposed/Vector native .so from QQ's process maps at the
 *    FRAMEWORK level (Vector "hide", or a native maps-filter hook in a zygisk companion). See
 *    docs/ANTIDETECT.md.
 *
 * What we hook (pure detection helpers, safe to neutralise):
 *  - QSec.detectMethod(String cls, String method)  -> false   (defeats class/method-existence probes)
 *  - QSec.getXpsInfo()                              -> empty   ("Xps" = Xposed info collector)
 */
public final class AntiDetect {
    private static final String QSEC = "com.tencent.mobileqq.qsec.qsecurity.QSec";

    private final Ref ref;
    public AntiDetect(ClassLoader cl) { this.ref = new Ref(cl); }

    public void install() {
        hookDetectMethod();
        hookGetXpsInfo();
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
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    p.setResult(new byte[0]);
                }
            });
            L.i("AntiDetect: neutralised QSec.getXpsInfo");
        } catch (Throwable t) {
            L.e("AntiDetect.getXpsInfo", t);
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
