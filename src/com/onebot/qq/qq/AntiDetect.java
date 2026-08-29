package com.onebot.qq.qq;

import com.onebot.qq.L;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import org.json.JSONObject;

import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

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
    private final boolean observeFekitAttach;

    private static final AtomicLong FEKIT_ATTACH_TOTAL = new AtomicLong();
    private static final AtomicLong FEKIT_ATTACH_ERRORS = new AtomicLong();
    private static final AtomicLong FEKIT_ATTACH_LAST_EPOCH_MS = new AtomicLong();
    private static final AtomicLong FEKIT_ATTACH_LAST_LENGTH = new AtomicLong(-1);
    private static final ConcurrentHashMap<String, AtomicLong> FEKIT_ATTACH_BY_COMMAND =
            new ConcurrentHashMap<>();

    public AntiDetect(ClassLoader cl, boolean blockTasks, boolean blockReports,
                      boolean observeFekitAttach) {
        this.ref = new Ref(cl);
        this.blockTasks = blockTasks;
        this.blockReports = blockReports;
        this.observeFekitAttach = observeFekitAttach;
    }

    public void install() {
        hookDetectMethod();
        hookGetXpsInfo();
        hookStarTrail();
        hookFileProbes();
        if (blockTasks) hookIntMethod("execTasks", 2);
        if (blockReports) hookIntMethod("reportLog", 4);
        if (observeFekitAttach) hookFekitAttachObserver();
    }

    /**
     * Observe the login TLV attachment without changing its arguments, return value, or errors.
     * Only command/sub-command and byte length are counted; attachment bytes and account data are
     * never retained. This is deliberately opt-in because even a transparent Java hook is an A/B
     * variable.
     */
    private void hookFekitAttachObserver() {
        try {
            Class<?> qsec = ref.clsOrNull(QSEC);
            if (qsec == null) return;
            Method m = findMethod(qsec, "getFeKitAttach", 4);
            if (m == null || m.getReturnType() != byte[].class) {
                L.w("AntiDetect: getFeKitAttach observer target not found");
                return;
            }
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    String command = safeCommand(p.args, 2);
                    String subCommand = safeCommand(p.args, 3);
                    Throwable error = p.getThrowable();
                    Object result = error == null ? p.getResult() : null;
                    int length = result instanceof byte[] ? ((byte[]) result).length : -1;
                    recordFekitAttach(command, subCommand, length, error != null);
                }
            });
            L.i("AntiDetect: observing getFeKitAttach counts only");
        } catch (Throwable t) {
            L.e("AntiDetect.getFeKitAttach observer", t);
        }
    }

    private static String safeCommand(Object[] args, int index) {
        if (args == null || index < 0 || index >= args.length || args[index] == null) return "empty";
        String value = String.valueOf(args[index]);
        return value.matches("0x[0-9A-Fa-f]{1,8}") ? value.toLowerCase() : "other";
    }

    public static void recordFekitAttach(String command, String subCommand, int length,
                                         boolean failed) {
        FEKIT_ATTACH_TOTAL.incrementAndGet();
        if (failed) FEKIT_ATTACH_ERRORS.incrementAndGet();
        FEKIT_ATTACH_LAST_EPOCH_MS.set(System.currentTimeMillis());
        FEKIT_ATTACH_LAST_LENGTH.set(length);
        String key = safeStatPart(command) + "/" + safeStatPart(subCommand);
        FEKIT_ATTACH_BY_COMMAND.computeIfAbsent(key, k -> new AtomicLong()).incrementAndGet();
    }

    private static String safeStatPart(String value) {
        if (value == null) return "empty";
        return value.matches("0x[0-9A-Fa-f]{1,8}") ? value.toLowerCase() : "other";
    }

    public static JSONObject fekitAttachStats(boolean enabled) {
        JSONObject out = new JSONObject();
        try {
            out.put("enabled", enabled);
            out.put("total", FEKIT_ATTACH_TOTAL.get());
            out.put("errors", FEKIT_ATTACH_ERRORS.get());
            out.put("last_epoch_ms", FEKIT_ATTACH_LAST_EPOCH_MS.get());
            out.put("last_length", FEKIT_ATTACH_LAST_LENGTH.get());
            JSONObject commands = new JSONObject();
            for (String key : FEKIT_ATTACH_BY_COMMAND.keySet()) {
                AtomicLong count = FEKIT_ATTACH_BY_COMMAND.get(key);
                if (count != null) commands.put(key, count.get());
            }
            out.put("commands", commands);
        } catch (Throwable ignore) {}
        return out;
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

    private void hookStarTrail() {
        try {
            Class<?> t = ref.clsOrNull("com.tencent.startrail.T");
            if (t == null) return;
            for (Method m : t.getDeclaredMethods()) {
                String n = m.getName();
                if ("ad".equals(n) && m.getReturnType() == byte[].class) {
                    m.setAccessible(true);
                    XposedBridge.hookMethod(m, XC_MethodReplacement.returnConstant(new byte[0]));
                    L.i("AntiDetect: neutralised startrail T.ad");
                }
            }
        } catch (Throwable t) {
            L.e("AntiDetect.startrail", t);
        }
    }

    private void hookFileProbes() {
        try {
            XposedBridge.hookAllMethods(java.io.File.class, "exists", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (!Boolean.TRUE.equals(p.getResult())) return;
                    if (deniedPath(((java.io.File) p.thisObject).getPath())) p.setResult(false);
                }
            });
            XposedBridge.hookAllMethods(android.os.Debug.class, "isDebuggerConnected",
                    XC_MethodReplacement.returnConstant(false));
            L.i("AntiDetect: File.exists + debugger probes");
        } catch (Throwable t) {
            L.e("AntiDetect.fileProbes", t);
        }
    }

    private static boolean deniedPath(String path) {
        if (path == null) return false;
        String p = path.toLowerCase();
        return p.contains("magisk") || p.contains("lsposed") || p.contains("/lspd")
                || p.contains("zygisk") || p.contains("/data/adb") || p.contains("kernelsu")
                || p.contains("mapshide") || p.contains("onebot") || p.contains("/debug_ramdisk")
                || p.endsWith("/su") || p.contains("/system/xbin/su") || p.contains("edposed");
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
