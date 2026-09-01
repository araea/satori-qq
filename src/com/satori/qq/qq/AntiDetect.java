package com.satori.qq.qq;

import android.content.pm.PackageManager;
import com.satori.qq.L;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Best-effort anti-detection for QQ's "device environment unsafe" / forced re-login.
 *
 * The signing path stays intact. Experimental task/report suppression is separately configurable
 * so it can be A/B tested and rolled back without changing the stable Java probe neutralisation.
 *
 * Packet-level intercept follows QQNTHookBypass: drop outbound environment reports
 * (ChannelProxy + MsfCore.sendSsoMsg) and forge empty success on inbound
 * (ChannelManager.onNativeReceive + MsfCore.addRespToQuque) so a server-pushed
 * check cannot run against a dirty process. Do not touch trpc.o3.ecdh_access.
 */
public final class AntiDetect {
    private static final String QSEC = "com.tencent.mobileqq.qsec.qsecurity.QSec";

    private final Ref ref;
    private final boolean blockTasks;
    private final boolean blockReports;
    private final boolean observeFekitAttach;
    private final boolean blockO3Report;

    private static final AtomicLong FEKIT_ATTACH_TOTAL = new AtomicLong();
    private static final AtomicLong FEKIT_ATTACH_ERRORS = new AtomicLong();
    private static final AtomicLong FEKIT_ATTACH_LAST_EPOCH_MS = new AtomicLong();
    private static final AtomicLong FEKIT_ATTACH_LAST_LENGTH = new AtomicLong(-1);
    private static final ConcurrentHashMap<String, AtomicLong> FEKIT_ATTACH_BY_COMMAND =
            new ConcurrentHashMap<>();
    private static final String ENV_DIR =
            "/storage/emulated/0/Android/data/com.tencent.mobileqq/files";
    private static final AtomicLong ENV_REPORT_DROPPED = new AtomicLong();
    private static final ConcurrentHashMap<String, AtomicLong> ENV_REPORT_BY_CMD =
            new ConcurrentHashMap<>();
    private static final AtomicLong ENV_LAST_PERSIST_MS = new AtomicLong();
    private static final long ENV_PERSIST_INTERVAL_MS = 5000L;
    private static volatile int hookChannelSend;
    private static volatile int hookChannelIn;
    private static volatile int hookMsfSend;
    private static volatile int hookMsfIn;
    private final java.util.Set<String> hookedSendClasses = ConcurrentHashMap.newKeySet();

    public AntiDetect(ClassLoader cl, boolean blockTasks, boolean blockReports,
                      boolean observeFekitAttach) {
        this(cl, blockTasks, blockReports, observeFekitAttach, true);
    }

    public AntiDetect(ClassLoader cl, boolean blockTasks, boolean blockReports,
                      boolean observeFekitAttach, boolean blockO3Report) {
        this.ref = new Ref(cl);
        this.blockTasks = blockTasks;
        this.blockReports = blockReports;
        this.observeFekitAttach = observeFekitAttach;
        this.blockO3Report = blockO3Report;
    }

    public void install() {
        hookDetectMethod();
        hookGetXpsInfo();
        hookStarTrail();
        hookFileProbes();
        hookPackageManager();
        hookRuntimeExec();
        hookGetenv();
        if (blockTasks) hookIntMethod("execTasks", 2);
        if (blockReports) hookIntMethod("reportLog", 4);
        if (observeFekitAttach) hookFekitAttachObserver();
        if (blockO3Report) {
            hookChannelProxyExt();
            hookChannelInbound();
            hookMsfCore();
            hookMsfInbound();
        }
        hookAdbSettings();
        hookAdbProperties();
        // Publish a process-local installation snapshot even when no report has been observed.
        // This lets the main-process status endpoint detect stale/missing MSF coverage after a
        // QQ upgrade, without adding another probe hook or touching the signing path.
        persistEnvReport(true);
    }

    /** QSec / ChannelManager environment reports. Never matches ecdh_access (login). */
    public static boolean isEnvReportCmd(String cmd) {
        if (cmd == null || cmd.isEmpty()) return false;
        if (cmd.startsWith("trpc.o3.ecdh_access.")) return false;
        return cmd.startsWith("trpc.o3.report.")
                || cmd.startsWith("trpc.o3.mobile_security.")
                || cmd.startsWith("trpc.gc_indust.device_report.");
    }

    public static void recordEnvReportDrop(String cmd) {
        ENV_REPORT_DROPPED.incrementAndGet();
        String key = cmd == null || cmd.isEmpty() ? "empty" : cmd;
        if (key.length() > 96) key = key.substring(0, 96);
        ENV_REPORT_BY_CMD.computeIfAbsent(key, k -> new AtomicLong()).incrementAndGet();
        persistEnvReport(false);
    }

    public static JSONObject envReportStats(boolean enabled) {
        JSONObject out = new JSONObject();
        try {
            out.put("enabled", enabled);
            out.put("process", envProcessKey());
            out.put("dropped", ENV_REPORT_DROPPED.get());
            JSONObject commands = new JSONObject();
            for (String key : ENV_REPORT_BY_CMD.keySet()) {
                AtomicLong count = ENV_REPORT_BY_CMD.get(key);
                if (count != null) commands.put(key, count.get());
            }
            out.put("commands", commands);
            out.put("hooks", hookStats());
            out.put("intercepts_ready", !enabled || interceptsReady(envProcessKey()));
            JSONObject msf = readEnvFile("msf");
            if (msf != null) out.put("msf", msf);
            JSONObject maps = readEnvFile("maps_main");
            if (maps != null) out.put("maps", maps);
            JSONObject mapsMsf = readEnvFile("maps_msf");
            if (mapsMsf != null) out.put("maps_msf", mapsMsf);
        } catch (Throwable ignore) {}
        return out;
    }

    private static String envProcessKey() {
        try {
            Method m = Class.forName("android.app.ActivityThread")
                    .getDeclaredMethod("currentProcessName");
            String n = (String) m.invoke(null);
            if (n != null && n.contains(":MSF")) return "msf";
        } catch (Throwable ignore) {}
        return "main";
    }

    private static synchronized void persistEnvReport(boolean force) {
        try {
            long now = System.currentTimeMillis();
            long previous = ENV_LAST_PERSIST_MS.get();
            if (!force && previous != 0 && now - previous < ENV_PERSIST_INTERVAL_MS) return;
            File dir = new File(ENV_DIR);
            if (!dir.isDirectory()) return;
            ENV_LAST_PERSIST_MS.set(now);
            String process = envProcessKey();
            JSONObject o = new JSONObject()
                    .put("process", process)
                    .put("pid", android.os.Process.myPid())
                    .put("updated_at_epoch_ms", now)
                    .put("dropped", ENV_REPORT_DROPPED.get())
                    .put("intercepts_ready", interceptsReady(process));
            JSONObject commands = new JSONObject();
            for (String key : ENV_REPORT_BY_CMD.keySet()) {
                AtomicLong count = ENV_REPORT_BY_CMD.get(key);
                if (count != null) commands.put(key, count.get());
            }
            o.put("commands", commands);
            o.put("hooks", hookStats());
            File f = new File(dir, "qk_env_" + envProcessKey() + ".json");
            File tmp = new File(f.getPath() + ".tmp");
            FileOutputStream out = new FileOutputStream(tmp);
            out.write(o.toString().getBytes("UTF-8"));
            out.close();
            if (!tmp.renameTo(f)) {
                FileOutputStream direct = new FileOutputStream(f);
                direct.write(o.toString().getBytes("UTF-8"));
                direct.close();
                tmp.delete();
            }
        } catch (Throwable ignore) {}
    }

    private static JSONObject hookStats() throws Exception {
        return new JSONObject()
                .put("channel_send", hookChannelSend)
                .put("channel_in", hookChannelIn)
                .put("msf_send", hookMsfSend)
                .put("msf_in", hookMsfIn);
    }

    private static boolean interceptsReady(String process) {
        return "msf".equals(process)
                ? hookMsfSend > 0 && hookMsfIn > 0
                : hookChannelSend > 0 && hookChannelIn > 0;
    }

    private static JSONObject readEnvFile(String key) {
        File f = new File(ENV_DIR, "qk_env_" + key + ".json");
        if (!f.isFile()) return null;
        try {
            FileInputStream in = new FileInputStream(f);
            byte[] buf = new byte[(int) Math.min(f.length(), 8192)];
            int n = in.read(buf);
            in.close();
            if (n <= 0) return null;
            return new JSONObject(new String(buf, 0, n, "UTF-8"));
        } catch (Throwable t) {
            return null;
        }
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

    /**
     * QQNTHookBypass: ChannelProxyExt is the NT-side path fekit uses for
     * trpc.o3.report.Report.SsoReport. Swallow the send and ack an empty success so the
     * HighReliability / DelayReporter does not retry a dirty payload.
     *
     * 9.3.55: ChannelProxyExt.sendMessage(cmd, body, uin, id) is abstract. Native / FEKit
     * call the concrete 4-arg override and skip sendMessageInner. Main-process impl is
     * O3MainProcessChannel$4 → O3BusinessHandler.P2 → callback
     * ChannelManager.onNativeReceive (ack path matches QQNTHookBypass).
     * Also hook ChannelManager.sendMessage (Java ChannelReport funnel) and the runtime
     * proxy installed by ChannelManager.init.
     */
    private void hookChannelProxyExt() {
        int hooked = 0;
        hooked += hookSendMessageOn("com.tencent.mobileqq.channel.ChannelProxy");
        hooked += hookSendMessageOn("com.tencent.mobileqq.channel.ChannelProxyExt");
        hooked += hookSendMessageOn("com.tencent.mobileqq.channel.ChannelManagerImpl");
        hooked += hookSendMessageOn("com.tencent.mobileqq.channel.ChannelManager");
        hooked += hookSendMessageOn("com.tencent.mobileqq.dt.app.O3MainProcessChannel$4");
        hooked += hookSendMessageOn("com.tencent.mobileqq.msf.core.security.a$a");
        hooked += hookChannelManagerInitDiscover();
        hooked += hookExistingChannelProxy();
        scheduleProxyRediscover();
        hookChannelSend = hooked;
        if (hooked > 0) L.i("AntiDetect: channel report intercept " + hooked);
        else L.w("AntiDetect: channel sendMessage* not found");
    }

    /** After ChannelManager.init(proxy), hook the actual ChannelProxyExt subclass. */
    private int hookChannelManagerInitDiscover() {
        try {
            Class<?> cm = ref.clsOrNull("com.tencent.mobileqq.channel.ChannelManager");
            if (cm == null) return 0;
            int hooked = 0;
            for (Method m : cm.getDeclaredMethods()) {
                if (!"init".equals(m.getName())) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length != 1) continue;
                m.setAccessible(true);
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (param.args == null || param.args.length < 1 || param.args[0] == null) return;
                        String name = param.args[0].getClass().getName();
                        int n = hookSendMessageOn(name);
                        if (n > 0) {
                            hookChannelSend += n;
                            L.i("AntiDetect: channel proxy discovered " + name + " +" + n);
                        }
                    }
                });
                hooked++;
            }
            return hooked;
        } catch (Throwable t) {
            L.e("AntiDetect.channelInit", t);
            return 0;
        }
    }

    /** ChannelManager.init may already have run; hook the live proxy class. */
    private int hookExistingChannelProxy() {
        try {
            Class<?> cm = ref.clsOrNull("com.tencent.mobileqq.channel.ChannelManager");
            if (cm == null) return 0;
            Object inst = ref.callS(cm, "getInstance");
            if (inst == null) return 0;
            Object proxy = ref.get(inst, "mChannelProxy");
            if (proxy == null) return 0;
            return hookSendMessageOn(proxy.getClass().getName());
        } catch (Throwable t) {
            return 0;
        }
    }

    private void scheduleProxyRediscover() {
        Thread t = new Thread(() -> {
            for (int i = 0; i < 24; i++) {
                try { Thread.sleep(i < 12 ? 1000L : 5000L); }
                catch (InterruptedException e) { return; }
                int n = hookExistingChannelProxy();
                if (n > 0) {
                    hookChannelSend += n;
                    L.i("AntiDetect: late channel proxy +" + n);
                }
            }
        }, "pool-6-thread-3");
        t.setDaemon(true);
        t.start();
    }

    private int hookSendMessageOn(String className) {
        if (className == null || className.isEmpty()) return 0;
        if (!hookedSendClasses.add(className)) return 0;
        int hooked = 0;
        try {
            Class<?> cls = ref.clsOrNull(className);
            if (cls == null) {
                hookedSendClasses.remove(className);
                return 0;
            }
            for (Method m : cls.getDeclaredMethods()) {
                String n = m.getName();
                if (!"sendMessage".equals(n) && !"sendMessageInner".equals(n)) continue;
                if ((m.getModifiers() & java.lang.reflect.Modifier.ABSTRACT) != 0) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length < 1 || p[0] != String.class) continue;
                m.setAccessible(true);
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args == null || param.args.length < 1) return;
                        if (!(param.args[0] instanceof String)) return;
                        String cmd = (String) param.args[0];
                        if (!isEnvReportCmd(cmd)) return;
                        long callbackId = 0L;
                        for (int i = param.args.length - 1; i >= 1; i--) {
                            if (param.args[i] instanceof Number) {
                                callbackId = ((Number) param.args[i]).longValue();
                                break;
                            }
                        }
                        recordEnvReportDrop(cmd);
                        ackNativeReceive(cmd, callbackId);
                        param.setResult(null);
                    }
                });
                hooked++;
            }
        } catch (Throwable t) {
            L.e("AntiDetect.sendMessage " + className, t);
        }
        return hooked;
    }

    private void ackNativeReceive(String cmd, long callbackId) {
        try {
            Class<?> cm = ref.clsOrNull("com.tencent.mobileqq.channel.ChannelManager");
            if (cm == null) return;
            Object inst = ref.callS(cm, "getInstance");
            if (inst == null) return;
            Method best = null;
            for (Method m : cm.getDeclaredMethods()) {
                if (!"onNativeReceive".equals(m.getName())) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length < 2 || p[0] != String.class) continue;
                if (best == null || p.length > best.getParameterTypes().length) best = m;
            }
            if (best == null) return;
            best.setAccessible(true);
            Class<?>[] p = best.getParameterTypes();
            Object[] args = new Object[p.length];
            args[0] = cmd;
            for (int i = 1; i < p.length; i++) {
                Class<?> t = p[i];
                if (t == byte[].class) args[i] = new byte[0];
                else if (t == boolean.class || t == Boolean.class) args[i] = Boolean.TRUE;
                else if (t == int.class || t == Integer.class) args[i] = 1000;
                else if (t == long.class || t == Long.class) args[i] = callbackId;
                else args[i] = null;
            }
            best.invoke(inst, args);
        } catch (Throwable t) {
            L.d("AntiDetect ackNativeReceive: " + t);
        }
    }

    /** MSF-process path. Same command prefixes; return the SSO seq as if the packet left. */
    private void hookMsfCore() {
        try {
            Class<?> msf = ref.clsOrNull("com.tencent.mobileqq.msf.core.MsfCore");
            Class<?> toMsg = ref.clsOrNull("com.tencent.qphone.base.remote.ToServiceMsg");
            if (msf == null || toMsg == null) return;
            Method send = null;
            for (Method m : msf.getDeclaredMethods()) {
                if (!"sendSsoMsg".equals(m.getName())) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 1 && p[0] == toMsg) { send = m; break; }
            }
            if (send == null) {
                L.w("AntiDetect: MsfCore.sendSsoMsg not found");
                return;
            }
            send.setAccessible(true);
            XposedBridge.hookMethod(send, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    Object msg = param.args == null || param.args.length < 1 ? null : param.args[0];
                    if (msg == null) return;
                    String cmd;
                    try { cmd = Ref.asStr(ref.call(msg, "getServiceCmd")); }
                    catch (Throwable t) { return; }
                    if (!isEnvReportCmd(cmd)) return;
                    recordEnvReportDrop(cmd);
                    try {
                        param.setResult(ref.call(msg, "getRequestSsoSeq"));
                    } catch (Throwable t) {
                        param.setResult(0);
                    }
                }
            });
            hookMsfSend = 1;
            L.i("AntiDetect: MsfCore.sendSsoMsg report intercept");
        } catch (Throwable t) {
            L.e("AntiDetect.MsfCore", t);
        }
    }

    /**
     * QQNTHookBypass inbound: server-pushed environment checks arrive as
     * ChannelManager.onNativeReceive / onReceive / MsfCore.addRespToQuque.
     * 9.3.55 MSF FEKitManager calls onReceive, not onNativeReceive.
     */
    private void hookChannelInbound() {
        try {
            Class<?> cm = ref.clsOrNull("com.tencent.mobileqq.channel.ChannelManager");
            if (cm == null) return;
            int hooked = 0;
            for (Method m : cm.getDeclaredMethods()) {
                String n = m.getName();
                if (!"onNativeReceive".equals(n) && !"onReceive".equals(n)) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length < 2 || p[0] != String.class) continue;
                m.setAccessible(true);
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args == null || param.args.length < 2) return;
                        if (!(param.args[0] instanceof String)) return;
                        String cmd = (String) param.args[0];
                        if (!isEnvReportCmd(cmd)) return;
                        recordEnvReportDrop("in:" + cmd);
                        for (int i = 1; i < param.args.length; i++) {
                            if (param.args[i] instanceof byte[]) param.args[i] = new byte[0];
                            else if (param.args[i] instanceof Boolean) param.args[i] = Boolean.TRUE;
                        }
                    }
                });
                hooked++;
            }
            hookChannelIn = hooked;
            if (hooked > 0) L.i("AntiDetect: channel inbound intercept " + hooked);
        } catch (Throwable t) {
            L.e("AntiDetect.channelInbound", t);
        }
    }

    private void hookMsfInbound() {
        try {
            Class<?> msf = ref.clsOrNull("com.tencent.mobileqq.msf.core.MsfCore");
            Class<?> fromMsg = ref.clsOrNull("com.tencent.qphone.base.remote.FromServiceMsg");
            if (msf == null || fromMsg == null) return;
            int hooked = 0;
            for (Method m : msf.getDeclaredMethods()) {
                String n = m.getName();
                if (!"addRespToQuque".equals(n) && !"addRespToQueue".equals(n))
                    continue;
                Class<?>[] p = m.getParameterTypes();
                int fromIdx = -1;
                for (int i = 0; i < p.length; i++) if (p[i] == fromMsg) { fromIdx = i; break; }
                if (fromIdx < 0) continue;
                final int idx = fromIdx;
                m.setAccessible(true);
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args == null || param.args.length <= idx) return;
                        sanitizeFromServiceMsg(param.args[idx]);
                    }
                });
                hooked++;
            }
            hookMsfIn = hooked;
            if (hooked > 0) L.i("AntiDetect: MsfCore inbound intercept " + hooked);
        } catch (Throwable t) {
            L.e("AntiDetect.MsfInbound", t);
        }
    }

    private void sanitizeFromServiceMsg(Object msg) {
        if (msg == null) return;
        String cmd;
        try { cmd = Ref.asStr(ref.call(msg, "getServiceCmd")); }
        catch (Throwable t) { return; }
        if (!isEnvReportCmd(cmd)) return;
        recordEnvReportDrop("in:" + cmd);
        try { ref.call(msg, "setMsgSuccess"); } catch (Throwable ignore) {}
        try { ref.call(msg, "setWupBuffer", new Object[]{new byte[0]}); } catch (Throwable ignore) {}
        try { ref.call(msg, "setBusinessFailCode", 0); } catch (Throwable ignore) {}
    }

    private void hookPackageManager() {
        try {
            XC_MethodHook hide = new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (p.args == null || p.args.length < 1 || !(p.args[0] instanceof String)) return;
                    if (hiddenPackage((String) p.args[0])) {
                        p.setThrowable(new PackageManager.NameNotFoundException((String) p.args[0]));
                    }
                }
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (p.getThrowable() != null) return;
                    stripXposedMeta(p.getResult());
                }
            };
            Class<?> appPm = ref.clsOrNull("android.app.ApplicationPackageManager");
            if (appPm != null) {
                for (Method m : appPm.getDeclaredMethods()) {
                    String n = m.getName();
                    Class<?>[] pt = m.getParameterTypes();
                    if (("getPackageInfo".equals(n) || "getApplicationInfo".equals(n))
                            && pt.length >= 1 && pt[0] == String.class) {
                        m.setAccessible(true);
                        XposedBridge.hookMethod(m, hide);
                    } else if ("getInstalledPackages".equals(n) || "getInstalledApplications".equals(n)
                            || "getInstalledPackagesAsUser".equals(n)
                            || "getInstalledApplicationsAsUser".equals(n)) {
                        m.setAccessible(true);
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override protected void afterHookedMethod(MethodHookParam p) {
                                copyFilterInstalled(p);
                            }
                        });
                    }
                }
            }
            L.i("AntiDetect: PackageManager hide");
        } catch (Throwable t) {
            L.e("AntiDetect.packageManager", t);
        }
    }

    /** Copy-on-write; never mutate the framework list (0.5.4 iterator.remove threw and looked like a timeout). */
    private static void copyFilterInstalled(XC_MethodHook.MethodHookParam p) {
        Object result = p.getResult();
        if (!(result instanceof java.util.List)) return;
        java.util.List<?> src = (java.util.List<?>) result;
        java.util.ArrayList<Object> out = new java.util.ArrayList<>(src.size());
        boolean changed = false;
        for (Object item : src) {
            String pkg = packageNameOf(item);
            if (hiddenInstalledPackage(pkg) || hasXposedMeta(item)) { changed = true; continue; }
            out.add(item);
        }
        if (changed) p.setResult(out);
    }

    private static String packageNameOf(Object item) {
        if (item == null) return "";
        try {
            Object v = item.getClass().getField("packageName").get(item);
            return v == null ? "" : String.valueOf(v);
        } catch (Throwable ignore) {}
        return "";
    }

    static boolean hasXposedMeta(Object item) {
        if (item == null) return false;
        try {
            Object ai = item;
            try { ai = item.getClass().getField("applicationInfo").get(item); }
            catch (Throwable ignore) {}
            if (ai == null) return false;
            Object bd = ai.getClass().getField("metaData").get(ai);
            if (!(bd instanceof android.os.Bundle)) return false;
            android.os.Bundle b = (android.os.Bundle) bd;
            for (String k : b.keySet()) {
                if (isXposedMetaKey(k)) return true;
            }
        } catch (Throwable ignore) {}
        return false;
    }

    public static boolean isXposedMetaKey(String key) {
        if (key == null) return false;
        String k = key.toLowerCase();
        return k.startsWith("xposed");
    }

    private static void stripXposedMeta(Object result) {
        if (result == null) return;
        try {
            Object ai = result;
            try { ai = result.getClass().getField("applicationInfo").get(result); }
            catch (Throwable ignore) {}
            if (ai == null) return;
            Object bd = ai.getClass().getField("metaData").get(ai);
            if (!(bd instanceof android.os.Bundle)) return;
            android.os.Bundle b = (android.os.Bundle) bd;
            for (String k : new java.util.ArrayList<>(b.keySet())) {
                if (isXposedMetaKey(k)) b.remove(k);
            }
        } catch (Throwable ignore) {}
    }

    private void hookRuntimeExec() {
        try {
            XposedBridge.hookAllMethods(Runtime.class, "exec", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (p.args == null || p.args.length < 1 || p.args[0] == null) return;
                    String cmd = p.args[0] instanceof String[]
                            ? join((String[]) p.args[0]) : String.valueOf(p.args[0]);
                    if (deniedPath(cmd) || cmdDenied(cmd)) {
                        p.setThrowable(new java.io.IOException("error=2"));
                    }
                }
            });
            XposedBridge.hookAllMethods(ProcessBuilder.class, "start", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        java.util.List<String> cmd = ((ProcessBuilder) p.thisObject).command();
                        if (cmd == null) return;
                        String joined = join(cmd.toArray(new String[0]));
                        if (deniedPath(joined) || cmdDenied(joined)) {
                            p.setThrowable(new java.io.IOException("error=2"));
                        }
                    } catch (Throwable ignore) {}
                }
            });
            L.i("AntiDetect: Runtime.exec hide");
        } catch (Throwable t) {
            L.e("AntiDetect.runtimeExec", t);
        }
    }

    private void hookGetenv() {
        try {
            XposedBridge.hookAllMethods(System.class, "getenv", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (p.args != null && p.args.length >= 1 && p.args[0] instanceof String) {
                        if (envNameDenied((String) p.args[0])) p.setResult(null);
                        return;
                    }
                    Object r = p.getResult();
                    if (!(r instanceof java.util.Map)) return;
                    java.util.Map<?, ?> src = (java.util.Map<?, ?>) r;
                    java.util.HashMap<Object, Object> out = new java.util.HashMap<>();
                    boolean changed = false;
                    for (java.util.Map.Entry<?, ?> e : src.entrySet()) {
                        String key = e.getKey() == null ? "" : String.valueOf(e.getKey());
                        String val = e.getValue() == null ? "" : String.valueOf(e.getValue());
                        if (envNameDenied(key) || deniedPath(val)) {
                            changed = true;
                            continue;
                        }
                        out.put(e.getKey(), e.getValue());
                    }
                    if (changed) p.setResult(java.util.Collections.unmodifiableMap(out));
                }
            });
            L.i("AntiDetect: getenv hide");
        } catch (Throwable t) {
            L.e("AntiDetect.getenv", t);
        }
    }

    private void hookFileProbes() {
        try {
            XC_MethodHook denyTrue = new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (!Boolean.TRUE.equals(p.getResult())) return;
                    if (deniedPath(((java.io.File) p.thisObject).getPath())) p.setResult(false);
                }
            };
            XposedBridge.hookAllMethods(java.io.File.class, "exists", denyTrue);
            XposedBridge.hookAllMethods(java.io.File.class, "canRead", denyTrue);
            XposedBridge.hookAllMethods(java.io.File.class, "canWrite", denyTrue);
            XposedBridge.hookAllMethods(java.io.File.class, "isFile", denyTrue);
            XposedBridge.hookAllMethods(java.io.File.class, "isDirectory", denyTrue);
            XposedBridge.hookAllMethods(android.os.Debug.class, "isDebuggerConnected",
                    XC_MethodReplacement.returnConstant(false));
            L.i("AntiDetect: File + debugger probes");
        } catch (Throwable t) {
            L.e("AntiDetect.fileProbes", t);
        }
    }

    private void hookAdbSettings() {
        try {
            XC_MethodHook hideAdb = new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (p.args == null || p.args.length < 2 || !(p.args[1] instanceof String)) return;
                    String key = (String) p.args[1];
                    if (!"adb_enabled".equals(key) && !"adb_wifi_enabled".equals(key)
                            && !"development_settings_enabled".equals(key)) return;
                    if (p.args.length >= 3 && p.args[2] instanceof Integer) p.setResult(p.args[2]);
                    else p.setResult(0);
                }
            };
            XposedBridge.hookAllMethods(android.provider.Settings.Global.class, "getInt", hideAdb);
            XposedBridge.hookAllMethods(android.provider.Settings.Secure.class, "getInt", hideAdb);
            XC_MethodHook hideAdbString = new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (p.args == null || p.args.length < 2 || !(p.args[1] instanceof String)) return;
                    String key = (String) p.args[1];
                    if (!"adb_enabled".equals(key) && !"adb_wifi_enabled".equals(key)
                            && !"development_settings_enabled".equals(key)) return;
                    if (p.args.length >= 3 && p.args[2] instanceof String) p.setResult(p.args[2]);
                    else p.setResult("0");
                }
            };
            XposedBridge.hookAllMethods(android.provider.Settings.Global.class, "getString", hideAdbString);
            XposedBridge.hookAllMethods(android.provider.Settings.Secure.class, "getString", hideAdbString);
            L.i("AntiDetect: adb settings hide");
        } catch (Throwable t) {
            L.e("AntiDetect.settings", t);
        }
    }

    /** Narrow keys only. Do not spoof the rest of SystemProperties (0.5.4). */
    private void hookAdbProperties() {
        try {
            Class<?> sp = ref.clsOrNull("android.os.SystemProperties");
            if (sp == null) return;
            XposedBridge.hookAllMethods(sp, "get", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (p.args == null || p.args.length < 1 || !(p.args[0] instanceof String)) return;
                    String safe = adbPropSafe((String) p.args[0]);
                    if (safe != null) p.setResult(safe);
                }
            });
            L.i("AntiDetect: adb property hide");
        } catch (Throwable t) {
            L.e("AntiDetect.sysprop", t);
        }
    }

    public static String adbPropSafe(String name) {
        if (name == null) return null;
        if ("persist.sys.usb.config".equals(name) || "sys.usb.config".equals(name)) return "mtp";
        if ("init.svc.adbd".equals(name)) return "stopped";
        return null;
    }

    private static boolean hiddenPackage(String pkg) {
        if (pkg == null) return false;
        String p = stripIgnorable(pkg).toLowerCase();
        return p.startsWith("org.lsposed")
                || p.startsWith("io.github.lsposed")
                || p.startsWith("io.github.huskydg")
                || "com.topjohnwu.magisk".equals(p)
                || "me.weishu.kernelsu".equals(p)
                || "com.rifsxd.ksunext".equals(p)
                || "me.bmax.apatch".equals(p)
                || "com.noshufou.android.su".equals(p)
                || "eu.chainfire.supersu".equals(p)
                || "de.robv.android.xposed.installer".equals(p)
                || "org.meowcat.edxposed.manager".equals(p)
                || "com.resukisu.resukisu".equals(p)
                || "com.tsng.hidemyapplist".equals(p)
                || "com.tsng.pzyhrx.hma".equals(p)
                || "ru.blays.bootloaderspoofer".equals(p)
                || "es.chiteroman.bootloaderspoofer".equals(p)
                || "com.aistra.hail".equals(p)
                || "com.jy.notewatermark".equals(p)
                || "com.suqi8.oshin".equals(p)
                || "bin.mt.termex".equals(p)
                || p.contains("lsposed")
                || p.contains("xposed")
                || p.contains("magisk")
                || p.contains("kernelsu")
                || p.contains("resukisu")
                || p.contains("sukisu")
                || p.contains("hidemyapplist")
                || p.contains("bootloaderspoofer")
                || p.contains("zygisk");
    }

    /** Installed-list filter also drops this module. Point queries stay
     *  visible so MapsHide can still resolve nativeLibraryDir as a fallback. */
    private static boolean hiddenInstalledPackage(String pkg) {
        String p = pkg == null ? "" : stripIgnorable(pkg);
        return hiddenPackage(p) || "com.satori.qq".equals(p);
    }

    private static boolean cmdDenied(String cmd) {
        if (cmd == null) return false;
        String c = collapsePath(stripIgnorable(cmd).toLowerCase());
        if (c.contains("magisk") || c.contains("ksud") || c.contains("apatch")
                || c.contains("which su")) return true;
        return c.equals("su") || c.startsWith("su ") || c.endsWith("/su") || c.contains("/su ");
    }

    private static String join(String[] parts) {
        if (parts == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(parts[i]);
        }
        return sb.toString();
    }

    public static boolean envNameDenied(String name) {
        if (name == null || name.isEmpty()) return false;
        String n = name.toLowerCase();
        return n.contains("magisk") || n.contains("zygisk") || n.contains("lsposed")
                || n.contains("lspd") || n.contains("riru") || n.contains("kernelsu")
                || n.contains("ksud") || n.contains("apatch") || n.contains("shamiko");
    }

    static String stripIgnorable(String path) {
        if (path == null || path.isEmpty()) return path;
        StringBuilder sb = new StringBuilder(path.length());
        for (int i = 0; i < path.length(); ) {
            int cp = path.codePointAt(i);
            i += Character.charCount(cp);
            if (cp < 0x20) continue;
            if (cp == 0x00AD || cp == 0xFEFF || cp == 0x2060) continue;
            if (cp >= 0x200B && cp <= 0x200F) continue;
            if (cp >= 0x202A && cp <= 0x202E) continue;
            if (cp >= 0x2066 && cp <= 0x2069) continue;
            if (Character.getType(cp) == Character.FORMAT) continue;
            sb.appendCodePoint(cp);
        }
        return sb.toString();
    }

    static String collapsePath(String path) {
        if (path == null || path.isEmpty()) return path;
        String p = stripIgnorable(path).replace('\\', '/');
        boolean abs = p.charAt(0) == '/';
        String[] parts = p.split("/", -1);
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        for (String part : parts) {
            if (part.isEmpty() || ".".equals(part)) continue;
            if ("..".equals(part)) {
                if (!out.isEmpty()) out.remove(out.size() - 1);
                continue;
            }
            out.add(part);
        }
        if (out.isEmpty()) return abs ? "/" : ".";
        StringBuilder sb = new StringBuilder();
        if (abs) sb.append('/');
        for (int i = 0; i < out.size(); i++) {
            if (i > 0) sb.append('/');
            sb.append(out.get(i));
        }
        return sb.toString();
    }

    static boolean deniedPath(String path) {
        if (path == null) return false;
        String p = collapsePath(path.toLowerCase()); // stripIgnorable is inside collapsePath
        return p.contains("magisk") || p.contains("lsposed") || p.contains("/lspd")
                || p.contains("zygisk") || p.contains("/data/adb") || p.contains("kernelsu")
                || p.contains("mapshide") || p.contains("satori") || p.contains("/debug_ramdisk")
                || p.equals("su") || p.endsWith("/su") || p.contains("/system/xbin/su")
                || p.contains("xposed") || p.contains("edposed") || p.contains("riru")
                || p.contains("apatch") || p.contains("shamiko") || p.contains("ksud")
                || p.contains("frida") || p.contains("lsplant");
    }

    public static boolean isDeniedPath(String path) { return deniedPath(path); }
    public static boolean isDeniedCommand(String cmd) { return cmdDenied(cmd); }
    public static boolean isHiddenInstalledPackage(String pkg) {
        return hiddenInstalledPackage(pkg);
    }
    public static boolean isHiddenPointQueryPackage(String pkg) {
        return hiddenPackage(pkg);
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
