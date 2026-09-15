package com.satori.qq.qq;

import android.content.pm.PackageManager;
import com.satori.qq.L;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import org.json.JSONObject;

import java.io.File;
import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
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
    private final boolean blockTuringRisk;
    private final boolean blockServerKick;
    private final String fakeImei;
    private final String fakeAndroidId;
    private final String fakeSerial;

    private static final AtomicLong FEKIT_ATTACH_TOTAL = new AtomicLong();
    private static final AtomicLong FEKIT_ATTACH_ERRORS = new AtomicLong();
    private static final AtomicLong FEKIT_ATTACH_LAST_EPOCH_MS = new AtomicLong();
    private static final AtomicLong FEKIT_ATTACH_LAST_LENGTH = new AtomicLong(-1);
    private static final ConcurrentHashMap<String, AtomicLong> FEKIT_ATTACH_BY_COMMAND =
            new ConcurrentHashMap<>();
    /**
     * Self-check output. This is diagnostics the module writes for its own {@code /healthz}, so it
     * belongs in the app-private files dir: the external {@code Android/data} directory is
     * enumerable by tools that work around the storage sandbox, and a file named
     * {@code qk_env_maps_main.json} carrying patch counts is a direct description of the module.
     */
    private static final String ENV_DIR = "/data/data/com.tencent.mobileqq/files";
    /** Where earlier versions wrote the same files; swept on first use. */
    private static final String ENV_DIR_LEGACY =
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
    private static final AtomicLong HARDENING_HOOKS = new AtomicLong();
    private final java.util.Set<String> hookedSendClasses = ConcurrentHashMap.newKeySet();

    /** 被拦下的服务端踢线。挡掉之后服务端会话已经作废、本地还报在线，这个计数是外部看守
     *  判断「要不要重启 QQ」的信号，所以单独记一份，不进 verbose 开关。 */
    private static final AtomicInteger BLOCKED_KICKS = new AtomicInteger();
    private static volatile long lastKickMs;
    private static volatile String lastKick = "";
    /** 命中的是哪个踢线入口（nt-kick / ticket-refresh / uid-fail）。三个入口的处置不同，
     *  只有知道是谁拦下的，才能判断这次踢线是不是真风控。 */
    private static volatile String lastKickSource = "";
    /** 踢线处理入口的 hook 数；0 表示这个版本没拦住踢线，被踢会正常退出登录。 */
    private static volatile int serverKickHookCount;
    /**
     * 最近几次被拦下的踢线。{@code /healthz} 的 kick 字段只留最后一次，重复被踢时看不出规律
     * （是同一台设备在别处登录，还是风控每隔几分钟来一次）。这里留一小圈原文。
     */
    private static final int KICK_LOG_MAX = 12;
    private static final java.util.ArrayDeque<String> KICK_LOG = new java.util.ArrayDeque<>();
    private static final AtomicLong KICK_LOG_TOTAL = new AtomicLong();
    private static final AtomicLong AUTO_LOGIN_KEPT = new AtomicLong();
    /**
     * 拦下踢线之后这段时间内不许把本机登出。
     *
     * <p>比 {@link #KICK_AFTERMATH_MS} 短：登出守卫要拦的只有「踢线之后 QQ 顺手把自己登出」
     * 这一小段，窗口给太长会把用户自己按的退出登录也拦掉。善后期（保账号、保自动登录、
     * 自愈）走 {@link #KICK_AFTERMATH_MS}，两者判据分开。
     */
    private static final long LOGOUT_GUARD_MS = 300000L;
    private static final AtomicLong LOGOUT_GUARD_BLOCKS = new AtomicLong();
    /** 登出守卫挂上了几个入口；0 表示这一版没拦住「踢线之后自己登出」这条路。 */
    private static volatile int logoutGuardHookCount;
    /**
     * 「刚被踢过」的善后期，比登出守卫长。
     *
     * <p>踢线会落盘毁掉两样东西——账号在已登录列表里的标记（{@code files/user/u_<uin>_t}
     * 被改名成 {@code _f}）与自动登录开关（mmkv 里的 {@code mqq_account_auto_login_<uin>}
     * 写成 1）。落盘的东西不会随进程消失，而看守恰恰会在这个窗口里 force-stop QQ 重启，
     * 所以善后期必须跨重启成立：判据除了内存里的 {@code lastKickMs}，还看
     * {@code qk_kick.log} 的最后修改时间。
     */
    private static final long KICK_AFTERMATH_MS = 900000L;
    /** 摘账号、关自动登录这些落盘动作被顶回去的次数。 */
    private static final AtomicLong LOGIN_STATE_KEPT = new AtomicLong();
    /** 落盘状态的守卫挂上了几个入口。 */
    private static volatile int loginStateHookCount;
    /** 被顶回去的落盘写入，新的在前。 */
    private static final java.util.ArrayDeque<String> STATE_LOG = new java.util.ArrayDeque<>();
    /** 踢线原文里记下的 uin，善后期用它指名修哪一份账号标记。 */
    private static volatile String lastKickUin = "";
    /** {@code qk_kick.log} 的 mtime 缓存：跨重启的「刚被踢过」判据，每 5 秒最多 stat 一次。 */
    private static volatile long markerCheckedMs;
    private static volatile long markerLastMs;
    /**
     * 用户主动登出的时刻。用户自己退出了就别再替他保活——两件事都做会把「退出登录」
     * 变成「退不掉」。参考值是 {@code AppRuntime.logout(reason)} 里 reason 为
     * {@code user}/{@code switchAccount} 的调用。
     */
    private static volatile long deliberateLogoutMs;
    /**
     * 「软踢线事件」的时刻：{@code onGrayError} 这类 QQ 会自己登出、但我们不该让看守
     * 立刻重启 QQ 的事件。
     *
     * <p>它不写 {@code qk_kick.log}（那份是看守的重启判据），只把善后窗口打开，让账号标记与
     * 自动登录开关不被改坏——会话照样断，但本地凭据还在，下一次启动能自己登回来。
     */
    private static volatile long softKickMs;
    /**
     * 主进程里那个实例。善后期的自愈要能在模块自己的线程上被调起来（状态监控每秒跑一轮），
     * 而 AntiDetect 是每个进程各一个实例，所以留一个主进程的引用。子进程为 null。
     */
    private static volatile AntiDetect mainInstance;
    /** 自愈的节流：状态监控每秒调一次，真正动手最多 30 秒一次。 */
    private static final AtomicLong LAST_HEAL_MS = new AtomicLong();
    private static final int GUARD_LOG_MAX = 12;
    private static final java.util.ArrayDeque<String> GUARD_LOG = new java.util.ArrayDeque<>();

    public static int blockedKicks() { return BLOCKED_KICKS.get(); }
    public static long lastKickMs() { return lastKickMs; }
    public static String lastKick() { return lastKick; }
    public static String lastKickSource() { return lastKickSource; }
    public static int serverKickHooks() { return serverKickHookCount; }
    public static long autoLoginKept() { return AUTO_LOGIN_KEPT.get(); }
    public static long logoutGuardBlocks() { return LOGOUT_GUARD_BLOCKS.get(); }
    public static int logoutGuardHooks() { return logoutGuardHookCount; }
    public static long loginStateKept() { return LOGIN_STATE_KEPT.get(); }
    public static int loginStateHooks() { return loginStateHookCount; }

    /** 踢线后窗口内被拦掉的登出，新的在前。 */
    public static String[] guardLog() {
        synchronized (GUARD_LOG) {
            return GUARD_LOG.toArray(new String[0]);
        }
    }

    /** 被顶回去的落盘状态写入，新的在前。 */
    public static String[] stateLog() {
        synchronized (STATE_LOG) {
            return STATE_LOG.toArray(new String[0]);
        }
    }

    /** 踢线之后的一段时间里，本机不许自己登出。public 是为了能在 JVM 单测里验边界。 */
    public static boolean inLogoutGuardWindow(long nowMs) {
        long last = lastKickMs;
        return last != 0 && nowMs - last >= 0 && nowMs - last <= LOGOUT_GUARD_MS;
    }

    /**
     * 是否处在「刚被踢过」的善后期。跨进程重启成立，理由见 {@link #KICK_AFTERMATH_MS}。
     *
     * <p>用户在善后期里自己按了退出登录（{@code reason=user}/{@code switchAccount}）就不再
     * 替他保活，否则「退出登录」会退不掉。
     */
    public static boolean inKickAftermath(long nowMs) {
        long last = Math.max(lastKickMs, softKickMs);
        long deliberate = deliberateLogoutMs;
        if (deliberate != 0 && nowMs - deliberate >= 0 && nowMs - deliberate <= KICK_AFTERMATH_MS
                && deliberate >= last) return false;
        if (last != 0 && nowMs - last >= 0 && nowMs - last <= KICK_AFTERMATH_MS) return true;
        long mark = kickMarkerMs(nowMs);
        return mark != 0 && nowMs - mark >= 0 && nowMs - mark <= KICK_AFTERMATH_MS;
    }

    /**
     * 记一次「软踢线」：开善后窗口，但不计入 {@code blocked_kicks}、不写 {@code qk_kick.log}。
     *
     * <p>{@code blocked_kicks} 的增长与踢线日志的行数都是看守「立刻重启 QQ」的判据，
     * onGrayError 那种 QQ 自己就会登出、并且可能反复发生的事件混进去会变成重启循环。
     */
    public static void noteSoftKick(String detail) {
        softKickMs = System.currentTimeMillis();
        noteGuardLine("soft-kick", detail == null ? "" : detail);
    }

    /**
     * {@code qk_kick.log} 的最后修改时间，0 表示没有这份文件。
     *
     * <p>只在善后期判据里用，所以带 5 秒缓存：状态监控每秒调一次，不该每秒 stat 一次盘。
     */
    private static long kickMarkerMs(long nowMs) {
        if (markerCheckedMs != 0 && nowMs - markerCheckedMs >= 0 && nowMs - markerCheckedMs < 5000L) {
            return markerLastMs;
        }
        markerCheckedMs = nowMs;
        long value = 0;
        try {
            File f = new File(ENV_DIR, "qk_kick.log");
            if (f.isFile()) value = f.lastModified();
        } catch (Throwable ignore) {}
        markerLastMs = value;
        return value;
    }

    /** 记下一次用户主动登出，善后期的保活动作就此停手。 */
    public static void noteDeliberateLogout() {
        deliberateLogoutMs = System.currentTimeMillis();
    }

    /**
     * 这个 {@code LogoutReason} 是不是用户自己按出来的。
     *
     * <p>只认 {@code user}（退出登录）与 {@code switchAccount}（切号）。{@code expired} /
     * {@code gray} / {@code tips} / {@code restartProcess} 都是 QQ 自己的生命周期，把它们当成
     * 「用户要退出」会让善后期在第一件 QQ 自己的事上就失效。
     */
    public static boolean userInitiatedLogout(String reason) {
        return "user".equals(reason) || "switchAccount".equals(reason);
    }

    /** 踢线原文，新的在前。进程内环形，落盘那份由 {@link #recordBlockedKick} 追加。 */
    public static String[] kickLog() {
        synchronized (KICK_LOG) {
            return KICK_LOG.toArray(new String[0]);
        }
    }

    /** 进程启动以来记下的踢线条数；落盘的 qk_kick.log 行数才是跨重启的判据。 */
    public static long kickLogTotal() { return KICK_LOG_TOTAL.get(); }

    /**
     * 踢线善后期的自愈入口，供模块的状态监控周期调用（只有主进程有实例，子进程直接返回）。
     *
     * <p>为什么要自愈：拦住踢线只是第一步，QQ 在踢线路径上还会把两样**落盘**的东西改掉
     * （账号标记 {@code files/user/u_<uin>_t} → {@code _f}、自动登录开关 → 1）。改完之后
     * 无论重启多少次 QQ 都停在登录页，外部看守只能一次次重启、等不来自动登录。这里在善后期内
     * 把这两样修回来，QQ 下一次启动就能自己登回来。
     */
    public static void healLoginStateIfDue() {
        AntiDetect self = mainInstance;
        if (self == null) return;
        self.healLoginState();
    }

    private void healLoginState() {
        long now = System.currentTimeMillis();
        long last = LAST_HEAL_MS.get();
        if (last != 0 && now - last >= 0 && now - last < 30000L) return;
        LAST_HEAL_MS.set(now);
        if (!inKickAftermath(now)) return;
        try {
            String uin = healTargetUin();
            if (uin.isEmpty()) return;
            List<String> done = new ArrayList<>();
            if (restoreAccountMarker(uin)) done.add("account");
            if (restoreAutoLogin(uin)) done.add("auto_login");
            if (!done.isEmpty()) noteGuardLine("self-heal", uin + " " + done);
        } catch (Throwable t) {
            L.e("AntiDetect.heal", t);
        }
    }

    /**
     * 要修哪个号。
     *
     * <p>只认踢线原文里那个 uin：内存里那份（{@link #lastKickUin}）没有就读落盘的
     * {@code qk_kick.log} 末行——踢线之后看守会 force-stop QQ，重启后内存里那份就没了，
     * 而善后期判据恰恰是跨重启成立的，两者必须配套。
     *
     * <p>**不要**拿 {@code files/user/u_<uin>_f} 当兜底。那个目录里除了本次被踢的号，
     * 还留着用户自己注销掉的其他账号（本机就有一个 2026-09-11 退出登录的
     * {@code u_1665757132_f}）。按「唯一那个 _f」去猜，踢线之后会把用户早就登出的号
     * 重新标成已登录、还把它的自动登录打开。认不出来就什么都不做。
     */
    private static String healTargetUin() {
        String uin = lastKickUin;
        if (uin != null && !uin.isEmpty()) return uin;
        try {
            java.util.regex.Matcher m =
                    java.util.regex.Pattern.compile("uin=(\\d+)").matcher(lastKick);
            if (m.find()) return m.group(1);
        } catch (Throwable ignore) {}
        return persistedKickUin();
    }

    /** 读 {@code qk_kick.log} 尾部，取最后一条 {@code uin=}。 */
    private static String persistedKickUin() {
        File f = new File(ENV_DIR, "qk_kick.log");
        if (!f.isFile()) return "";
        try {
            java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "r");
            long len = raf.length();
            if (len <= 0) { raf.close(); return ""; }
            int take = (int) Math.min(4096L, len);
            byte[] tail = new byte[take];
            raf.seek(len - take);
            raf.readFully(tail);
            raf.close();
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("uin=(\\d+)").matcher(new String(tail, "UTF-8"));
            String found = "";
            while (m.find()) found = m.group(1);
            return found;
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * 把 {@code u_<uin>_f} 改回 {@code u_<uin>_t}；已经是 {@code _t} 就不动，两者都没有时新建
     * {@code _t}。返回是否真的改了什么。
     */
    private boolean restoreAccountMarker(String uin) {
        File dir = new File(ENV_DIR, "user");
        if (!dir.isDirectory()) return false;
        File active = new File(dir, "u_" + uin + "_t");
        if (active.isFile()) return false;
        File off = new File(dir, "u_" + uin + "_f");
        if (off.isFile()) return off.renameTo(active);
        try {
            return active.createNewFile();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 把 mmkv 里的自动登录开关写回 2。**已经是自动登录就什么都不做**——否则每 30 秒的善后
     * 循环会在 qk_guard.log 里刷一行同样的 self-heal。
     *
     * <p>先问 {@code AutoLoginUtil.canAutoLogin(uin)}（值为 2 才返回 true；值为 0 时它会自己
     * 回落到缓存并顺手写一次），返回 false 才真正改。
     */
    private boolean restoreAutoLogin(String uin) {
        try {
            Class<?> cls = ref.clsOrNull("mqq.app.AutoLoginUtil");
            if (cls == null) return false;
            Method can = findMethod(cls, "canAutoLogin", 1);
            Method set = null;
            for (Method m : cls.getDeclaredMethods()) {
                if (!"setAutoLogin".equals(m.getName())) continue;
                Class<?>[] pt = m.getParameterTypes();
                if (m.getReturnType() != void.class || pt.length != 2
                        || pt[0] != String.class || pt[1] != boolean.class) continue;
                m.setAccessible(true);
                set = m;
                break;
            }
            if (set == null) return false;
            if (can != null) {
                Object state = can.invoke(null, uin);
                if (Boolean.TRUE.equals(state)) return false;
            }
            set.invoke(null, uin, Boolean.TRUE);
            return true;
        } catch (Throwable t) {
            L.e("AntiDetect.heal.autoLogin", t);
        }
        return false;
    }

    public AntiDetect(ClassLoader cl, boolean blockTasks, boolean blockReports,
                      boolean observeFekitAttach) {
        this(cl, blockTasks, blockReports, observeFekitAttach, true);
    }

    public AntiDetect(ClassLoader cl, boolean blockTasks, boolean blockReports,
                      boolean observeFekitAttach, boolean blockO3Report) {
        this(cl, blockTasks, blockReports, observeFekitAttach, blockO3Report,
                true, true, "", "", "");
    }

    public AntiDetect(ClassLoader cl, boolean blockTasks, boolean blockReports,
                      boolean observeFekitAttach, boolean blockO3Report,
                      boolean blockTuringRisk, boolean blockServerKick,
                      String fakeImei, String fakeAndroidId, String fakeSerial) {
        this.ref = new Ref(cl);
        this.blockTasks = blockTasks;
        this.blockReports = blockReports;
        this.observeFekitAttach = observeFekitAttach;
        this.blockO3Report = blockO3Report;
        this.blockTuringRisk = blockTuringRisk;
        this.blockServerKick = blockServerKick;
        this.fakeImei = cleanFake(fakeImei);
        this.fakeAndroidId = cleanFake(fakeAndroidId);
        this.fakeSerial = cleanFake(fakeSerial);
    }

    public void install() {
        if ("main".equals(envProcessKey())) mainInstance = this;
        hookDetectMethod();
        hookGetXpsInfo();
        hookStarTrail();
        hookFileProbes();
        hookPackageManager();
        hookRuntimeExec();
        hookGetenv();
        hookVendorRootChecks();
        hookProcTextReads();
        hookStackAndLoader();
        hookDeviceIdentity();
        hookRuntimeMonitor();
        hookTuringSdk();
        hookQQDetectionPatch();
        hookQimeiObserver();
        hookSocketProbe();
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
        // Publish a process-local installation snapshot even when no report has been observed.
        // This lets the main-process status endpoint detect stale/missing MSF coverage after a
        // QQ upgrade, without adding another probe hook or touching the signing path.
        persistEnvReport(true);
    }

    /**
     * QQ 9.3.60.40970 给 libfekit 换上了 QSec_Channel 上报器（二进制里带
     * channel/src/reporter/{async,delay,high_reliability,super}_reporter.cpp、"QSec_Channel report retry"
     * 与 HighReliabilityReporter saveToDisk/loadFromDisk，报不通会落盘重试 3 次）。它比旧的
     * SsoReport 通道抗丢包，命令名也换了。0x9c00/0x9c01/0x9c02/0x9c0c 只出现在 libfekit，
     * 0x9cdf 与 libMSFKernel 共用。旧版 libfekit 的 312 条命令表里没有这 5 条。
     */
    private static final String[] FEKIT_CHANNEL_REPORT_CMDS = {
            "OidbSvcTrpcTcp.0x9c00_", "OidbSvcTrpcTcp.0x9c01_", "OidbSvcTrpcTcp.0x9c02_",
            "OidbSvcTrpcTcp.0x9c0c_", "OidbSvcTrpcTcp.0x9cdf_",
    };

    public static boolean isFekitChannelReportCmd(String cmd) {
        if (cmd == null || cmd.isEmpty()) return false;
        for (String prefix : FEKIT_CHANNEL_REPORT_CMDS) {
            if (cmd.startsWith(prefix)) return true;
        }
        return false;
    }

    /** QSec / ChannelManager environment reports. Never matches ecdh_access (login). */
    public static boolean isEnvReportCmd(String cmd) {
        if (cmd == null || cmd.isEmpty()) return false;
        if (cmd.startsWith("trpc.o3.ecdh_access.")) return false;
        return cmd.equals("trpc.o3.report") || cmd.startsWith("trpc.o3.report.")
                || cmd.equals("trpc.o3.mobile_security")
                || cmd.startsWith("trpc.o3.mobile_security.")
                || cmd.equals("trpc.gc_indust.device_report")
                || cmd.startsWith("trpc.gc_indust.device_report.")
                || cmd.equals("trpc.ilive_cdn.report")
                || cmd.startsWith("trpc.ilive_cdn.report.")
                || cmd.equals("OidbSvc.0xd79")
                || cmd.startsWith("OidbSvc.0xd79_")
                || isFekitChannelReportCmd(cmd);
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
            JSONObject maps = readMapsFile("maps_main");
            if (maps != null) out.put("maps", maps);
            JSONObject mapsMsf = readEnvFile("maps_msf");
            if (mapsMsf != null) out.put("maps_msf", mapsMsf);
        } catch (Throwable ignore) {}
        return out;
    }

    /**
     * 进程键，决定 {@code qk_env_*.json} 的文件名。只有裸包名才是主进程。
     *
     * <p>此前除 :MSF 之外一律记成 main，于是 :qzone 这类子进程写完就把主进程的数字覆盖掉，
     * 从状态接口上看不出这是谁的数据。现在按冒号后的进程名分开写，子进程再启动也不会动主进程那份。
     */
    private static String envProcessKey() {
        try {
            Method m = Class.forName("android.app.ActivityThread")
                    .getDeclaredMethod("currentProcessName");
            Object value = m.invoke(null);
            if (value instanceof String) {
                String n = (String) value;
                int colon = n.lastIndexOf(':');
                if (colon >= 0 && colon + 1 < n.length()) {
                    return sanitizeProcessKey(n.substring(colon + 1));
                }
            }
        } catch (Throwable ignore) {}
        return "main";
    }

    private static String sanitizeProcessKey(String name) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length() && sb.length() < 24; i++) {
            char c = name.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) sb.append(c);
            else if (c >= 'A' && c <= 'Z') sb.append((char) (c - 'A' + 'a'));
            else sb.append('_');
        }
        return sb.length() == 0 ? "main" : sb.toString();
    }

    private static volatile boolean legacySwept;

    /** Delete the {@code qk_env_*} files older versions left on external storage. */
    private static void sweepLegacyEnvDir() {
        if (legacySwept) return;
        legacySwept = true;
        try {
            File dir = new File(ENV_DIR_LEGACY);
            File[] files = dir.listFiles();
            if (files == null) return;
            for (File f : files) {
                String n = f.getName();
                if (n.startsWith("qk_env_") && (n.endsWith(".json") || n.endsWith(".json.tmp")))
                    f.delete();
            }
        } catch (Throwable ignore) {}
    }

    private static synchronized void persistEnvReport(boolean force) {
        try {
            long now = System.currentTimeMillis();
            long previous = ENV_LAST_PERSIST_MS.get();
            if (!force && previous != 0 && now - previous < ENV_PERSIST_INTERVAL_MS) return;
            File dir = new File(ENV_DIR);
            if (!dir.isDirectory()) return;
            sweepLegacyEnvDir();
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
                .put("msf_in", hookMsfIn)
                .put("hardening", HARDENING_HOOKS.get())
                .put("server_kick", serverKickHookCount);
    }

    private static boolean interceptsReady(String process) {
        return "msf".equals(process)
                ? hookMsfSend > 0 && hookMsfIn > 0
                : hookChannelSend > 0 && hookChannelIn > 0;
    }

    /**
     * native 层把除 :MSF 之外的进程都写成 {@code qk_env_maps_main.json}，后启动的进程会覆盖先写的，
     * 所以直接读到的数字可能来自 :qzone 而不是主进程（实测主进程的 libfekit GOT 已全量补丁，
     * 文件里却是 :qzone 的 21，看起来像过检测退化）。比对 pid，并明确标出这份数据是谁写的。
     */
    private static JSONObject readMapsFile(String key) {
        JSONObject stats = readEnvFile(key);
        if (stats == null) return null;
        try {
            if (stats.has("pid")) {
                int owner = stats.getInt("pid");
                if (owner != android.os.Process.myPid()) {
                    stats.put("owner", "pid " + owner + " (not this process)");
                }
            }
        } catch (Throwable ignore) {}
        return stats;
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
                final Class<?> returnType = m.getReturnType();
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
                        Object value = safeDefault(returnType, true);
                        param.setResult(value == VOID_VALUE ? null : value);
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
            final Class<?> returnType = send.getReturnType();
            XposedBridge.hookMethod(send, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    Object msg = param.args == null || param.args.length < 1 ? null : param.args[0];
                    if (msg == null) return;
                    String cmd;
                    try { cmd = Ref.asStr(ref.call(msg, "getServiceCmd")); }
                    catch (Throwable t) { return; }
                    if (!isEnvReportCmd(cmd)) return;
                    recordEnvReportDrop(cmd);
                    Object seq = null;
                    try {
                        seq = ref.call(msg, "getRequestSsoSeq");
                    } catch (Throwable ignore) {}
                    param.setResult(coerceNumber(returnType, seq));
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

    /** Java checks covered by QQEnhancedBypass in addition to QSec's own probes. */
    private void hookVendorRootChecks() {
        hookBooleanFalse("org.light.device.LightDeviceUtils", "isRooted");
        hookBooleanFalse("com.tenpay.charge.v2.util.ChargeV2Utils", "isDeviceRooted");
        hookBooleanFalse("com.tencent.gathererga.core.UserInfoImpl", "isRooted");

        try {
            Class<?> wlogin = ref.clsOrNull("oicq.wlogin_sdk.request.w");
            if (wlogin == null) return;
            for (Method m : wlogin.getDeclaredMethods()) {
                if (!"h".equals(m.getName())) continue;
                m.setAccessible(true);
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        L.d("AntiDetect: Wlogin device probe observed");
                    }
                });
                HARDENING_HOOKS.incrementAndGet();
            }
        } catch (Throwable t) {
            L.e("AntiDetect.wloginRoot", t);
        }
    }

    private void hookBooleanFalse(String className, String methodName) {
        try {
            Class<?> cls = ref.clsOrNull(className);
            if (cls == null) return;
            int hooked = 0;
            for (Method m : cls.getDeclaredMethods()) {
                if (!methodName.equals(m.getName()) || m.getReturnType() != boolean.class) continue;
                m.setAccessible(true);
                XposedBridge.hookMethod(m, XC_MethodReplacement.returnConstant(false));
                hooked++;
                HARDENING_HOOKS.incrementAndGet();
            }
            if (hooked > 0) L.i("AntiDetect: root check " + className + "." + methodName);
        } catch (Throwable t) {
            L.e("AntiDetect.root " + className, t);
        }
    }

    /** Covers Java readers of proc status files; native readers are handled by MapsHide. */
    private void hookProcTextReads() {
        try {
            XposedBridge.hookAllMethods(BufferedReader.class, "readLine", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    Object value = p.getResult();
                    if (!(value instanceof String)) return;
                    String line = (String) value;
                    if (line.startsWith("TracerPid:")) p.setResult("TracerPid:\t0");
                    else if (line.startsWith("NoNewPrivs:")) p.setResult("NoNewPrivs:\t0");
                    else if (shouldHideProcMapLine(line)) p.setResult("");
                }
            });
            HARDENING_HOOKS.incrementAndGet();
            L.i("AntiDetect: proc status text filter");
        } catch (Throwable t) {
            L.e("AntiDetect.procText", t);
        }
    }

    private void hookStackAndLoader() {
        try {
            XposedBridge.hookAllMethods(Throwable.class, "getStackTrace", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    Object value = p.getResult();
                    if (!(value instanceof StackTraceElement[])) return;
                    StackTraceElement[] src = (StackTraceElement[]) value;
                    ArrayList<StackTraceElement> out = new ArrayList<>(src.length);
                    for (StackTraceElement e : src) {
                        if (e != null && !frameworkText(e.getClassName())) out.add(e);
                    }
                    if (out.size() != src.length) p.setResult(out.toArray(new StackTraceElement[0]));
                }
            });
            XposedBridge.hookAllMethods(ClassLoader.class, "toString", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (p.getResult() instanceof String)
                        p.setResult(sanitizeFrameworkText((String) p.getResult()));
                }
            });
            HARDENING_HOOKS.addAndGet(2);
            L.i("AntiDetect: stack and class-loader filter");
        } catch (Throwable t) {
            L.e("AntiDetect.stack", t);
        }
    }

    private void hookDeviceIdentity() {
        hookStringResult("android.telephony.TelephonyManager",
                new String[]{"getImei", "getDeviceId", "getMeid"}, fakeImei);
        hookStringResult("android.telephony.TelephonyManager",
                new String[]{"getSimSerialNumber"}, fakeSerial);
        hookStringResult("android.os.Build", new String[]{"getSerial"}, fakeSerial);
        hookStringResult("com.tencent.qmethod.pandoraex.monitor.DeviceInfoMonitor",
                new String[]{"getImei"}, fakeImei);

        if (!fakeAndroidId.isEmpty()) {
            try {
                Class<?> secure = ref.clsOrNull("android.provider.Settings$Secure");
                if (secure != null) {
                    XposedBridge.hookAllMethods(secure, "getString", new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            if (p.args != null && p.args.length >= 2
                                    && "android_id".equals(p.args[1])) p.setResult(fakeAndroidId);
                        }
                    });
                    HARDENING_HOOKS.incrementAndGet();
                }
            } catch (Throwable t) {
                L.e("AntiDetect.androidId", t);
            }
        }
        hookDetectionProperties();
        hookVoidMethods("com.tencent.qmethod.pandoraex.core.MonitorReporter",
                new String[]{"report"}, "Pandora reports");
    }

    private void hookStringResult(String className, String[] names, final String value) {
        if (value == null || value.isEmpty()) return;
        try {
            Class<?> cls = ref.clsOrNull(className);
            if (cls == null) return;
            for (Method m : cls.getDeclaredMethods()) {
                if (m.getReturnType() != String.class || !containsName(names, m.getName())) continue;
                m.setAccessible(true);
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) { p.setResult(value); }
                });
                HARDENING_HOOKS.incrementAndGet();
            }
        } catch (Throwable t) {
            L.e("AntiDetect.identity " + className, t);
        }
    }

    private void hookDetectionProperties() {
        try {
            Class<?> sp = ref.clsOrNull("android.os.SystemProperties");
            if (sp == null) return;
            XposedBridge.hookAllMethods(sp, "get", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (p.args == null || p.args.length < 1 || !(p.args[0] instanceof String)) return;
                    String key = (String) p.args[0];
                    String safe = safeStringProperty(key, fakeSerial);
                    if (safe != null) p.setResult(safe);
                }
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (p.args == null || p.args.length < 1
                            || !"ro.product.device".equals(p.args[0])
                            || !(p.getResult() instanceof String)) return;
                    String value = ((String) p.getResult()).toLowerCase(Locale.ROOT);
                    if ("goldfish".equals(value) || "vbox86".equals(value)) p.setResult("unknown");
                }
            });
            XposedBridge.hookAllMethods(sp, "getInt", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (p.args == null || p.args.length < 1 || !(p.args[0] instanceof String)) return;
                    Integer safe = safeIntProperty((String) p.args[0]);
                    if (safe != null) p.setResult(safe);
                }
            });
            XposedBridge.hookAllMethods(sp, "getBoolean", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (p.args == null || p.args.length < 1 || !(p.args[0] instanceof String)) return;
                    Boolean safe = safeBooleanProperty((String) p.args[0]);
                    if (safe != null) p.setResult(safe);
                }
            });
            HARDENING_HOOKS.addAndGet(3);
            L.i("AntiDetect: debug, emulator and serial properties");
        } catch (Throwable t) {
            L.e("AntiDetect.properties", t);
        }
    }

    /** Observe Pandora's command wrappers; Runtime/ProcessBuilder hooks do the neutralisation. */
    private void hookRuntimeMonitor() {
        hookObserveMethods("com.tencent.qmethod.pandoraex.monitor.RuntimeMonitor",
                new String[]{"exec", "execute"});
        hookObserveMethods("com.tencent.qmethod.pandoraex.monitor.RuntimeMonitor$IPProcessor",
                new String[]{"transform"});
        hookObserveMethods("com.tencent.qmethod.pandoraex.monitor.RuntimeMonitor$PackageManagerProcessor",
                new String[]{"transform"});
        hookObserveMethods("com.tencent.qmethod.pandoraex.monitor.RuntimeMonitor$PropProcessor",
                new String[]{"transform"});
    }

    private void hookObserveMethods(String className, String[] names) {
        try {
            Class<?> cls = ref.clsOrNull(className);
            if (cls == null) return;
            for (Method m : cls.getDeclaredMethods()) {
                if (!containsName(names, m.getName())) continue;
                m.setAccessible(true);
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (p.args == null) return;
                        for (Object arg : p.args) {
                            String s = commandText(arg);
                            if (cmdDenied(s)) L.d("AntiDetect: Pandora root command blocked downstream");
                        }
                    }
                });
                HARDENING_HOOKS.incrementAndGet();
            }
        } catch (Throwable t) {
            L.e("AntiDetect.runtimeMonitor " + className, t);
        }
    }

    /** Turing entry points from QQEnhancedBypass, with exact return-type defaults. */
    private void hookTuringSdk() {
        if (!blockTuringRisk) return;
        String[] entryClasses = {
                "com.tencent.turingfd.sdk.xq.Pomegranate",
                "com.tencent.tfd.sdk.wxa.Pomegranate",
                "com.tencent.turingfd.sdk.xq.Blueberry",
                "com.tencent.tfd.sdk.wxa.Blueberry",
                "com.tencent.turingcam.oqKCa"
        };
        for (String cls : entryClasses) {
            hookSafeDefaults(cls, new String[]{"a"}, true, "Turing entry");
            hookSafeDefaults(cls, new String[]{"b"}, false, "Turing debug");
        }
    }

    private void hookQQDetectionPatch() {
        if (blockServerKick) {
            // 内核 IKickApi 的踢线回调是 a(AppRuntime, KickedInfo)，b(...) 是它调用的私有方法。
            // 只拦 b 的话，a 里在它之前做的几件事照样跑：kick.a 线程（清登录数据）、
            // updateSimpleAccount(uin,false)、reportClearLoginData(uin,"2004")、setSortAccountList
            // ——本地账号列表当场就被标成已下线。两个都拦，a 是外层，b 就再也到不了。
            hookServerKick("com.tencent.mobileqq.kick.NTKickProcessor",
                    new String[]{"a", "b"}, "nt-kick", true);
            // NTLoginTicketManager。登录后刷新票据失败时，错误码落在
            // 140022014/140022015/140022016 或 refreshMethodNeedKick 为真，会走到 f(int,String)：
            // 里面先 ntTriggerLogout(expired)，再拿 LoginActivity 发 ACTION_KICK_TO_LOGIN，
            // 界面表现为「刚登录就被弹回登录页」。NTKickProcessor 不经过这里。
            hookServerKick("com.tencent.mobileqq.login.ntlogin.ao",
                    new String[]{"f"}, "ticket-refresh", false);
            // UidServiceImpl。拿不到 UID 时 startRequestUid 先 logoutWhenReqUidFail()，
            // 再 kickToLoginPage() 跳到 /base/login。
            hookServerKick("com.tencent.mobileqq.login.api.impl.UidServiceImpl",
                    new String[]{"kickToLoginPage"}, "uid-fail", false);
            hookMainServiceKick();
            hookAutoLoginGuard();
            hookLogoutGuard();
            hookLoginStateGuard();
        }

        hookVoidMethods("com.tencent.mobileqq.msf.core.MsfCore", new String[]{
                "doReportOnInitComplete", "reportMsfCoreInit", "tryReportJobAlive",
                "tryReportLoadCfgTempFile", "tryReportMSFAlive", "tryReportSoLoadUseTxlib"
        }, "MSF telemetry");
        hookVoidMethods("com.tencent.mobileqq.dt.model.TuringWrapper",
                new String[]{"b", "c"}, "Turing wrapper");
        hookVoidMethods("com.tencent.mobileqq.channel.ChannelManager",
                new String[]{"checkMethod"}, "channel report setup");

        if (!blockTuringRisk) return;
        for (String cls : new String[]{
                "com.tencent.tfd.sdk.wxa.TuringRiskService",
                "com.tencent.turingfd.sdk.xq.TuringRiskService"}) {
            hookSafeDefaults(cls, new String[]{"reqRiskDetectV2"}, true, "Turing risk");
        }
        for (String cls : new String[]{
                "com.tencent.tfd.sdk.wxa.TuringIDService",
                "com.tencent.turingfd.sdk.xq.TuringIDService"}) {
            hookSafeDefaults(cls,
                    new String[]{"getTuringDID", "getTuringDIDAsync", "getTuringDIDCached"},
                    true, "Turing DID");
        }
    }

    /**
     * QSec ships SocketStatus.checkSocket(name): it connects to an abstract local socket and
     * returns 1 when the socket exists (or is permission-denied), 0 when it is absent. That is a
     * direct probe for LSPosed / Zygisk / Shamiko / Frida style daemons. Only names that carry a
     * framework token are answered as absent; every other name is passed through untouched.
     */
    private void hookSocketProbe() {
        try {
            Class<?> cls = ref.clsOrNull("com.tencent.mobileqq.qsec.qsecurity.utils.SocketStatus");
            if (cls == null) return;
            Method m = findMethod(cls, "checkSocket", 1);
            if (m == null || m.getReturnType() != int.class) return;
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (p.args == null || p.args.length < 1 || !(p.args[0] instanceof String)) return;
                    if (frameworkSocketDenied((String) p.args[0])) p.setResult(0);
                }
            });
            HARDENING_HOOKS.incrementAndGet();
            L.i("AntiDetect: QSec socket probe neutralised");
        } catch (Throwable t) {
            L.e("AntiDetect.socketProbe", t);
        }
    }

    public static boolean frameworkSocketDenied(String name) {
        if (name == null || name.isEmpty()) return false;
        String n = name.toLowerCase(Locale.ROOT);
        return n.contains("lspd") || n.contains("lsposed") || n.contains("xposed")
                || n.contains("zygisk") || n.contains("riru") || n.contains("magisk")
                || n.contains("shamiko") || n.contains("kernelsu") || n.contains("apatch")
                || n.contains("frida") || n.contains("substrate") || n.contains("lsplant")
                || n.contains("satori");
    }

    /** The reference native hook only observed this value. Xposed can cover the Java native bridge. */
    private void hookQimeiObserver() {
        try {
            Class<?> cls = ref.clsOrNull("com.tencent.mobileqq.msfcore.MSFKernelBridge$CppProxy");
            if (cls == null) return;
            for (Method m : cls.getDeclaredMethods()) {
                if (!"native_setQimei36".equals(m.getName())) continue;
                m.setAccessible(true);
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        L.d("AntiDetect: qimei36 bridge observed (value redacted)");
                    }
                });
                HARDENING_HOOKS.incrementAndGet();
            }
        } catch (Throwable t) {
            L.e("AntiDetect.qimei", t);
        }
    }

    private void hookVoidMethods(String className, String[] names, String label) {
        try {
            Class<?> cls = ref.clsOrNull(className);
            if (cls == null) return;
            int hooked = 0;
            for (Method m : cls.getDeclaredMethods()) {
                if (!containsName(names, m.getName()) || m.getReturnType() != void.class) continue;
                m.setAccessible(true);
                XposedBridge.hookMethod(m, XC_MethodReplacement.returnConstant(null));
                hooked++;
                HARDENING_HOOKS.incrementAndGet();
            }
            if (hooked > 0) L.i("AntiDetect: blocked " + label + " (" + hooked + ")");
        } catch (Throwable t) {
            L.e("AntiDetect." + label, t);
        }
    }

    /**
     * 服务端踢线（别处登录、改密码、版本过低）时 QQ 唯一的处理入口：{@code NTKickProcessor.b}
     * 会退出登录并跳回登录页。挡掉它，本机就不会被踢下线；但服务端会话已经作废，本机会停在
     * 「自己报在线、消息一条收不到、也不会自己重连」的状态，只有重启 QQ 才能恢复。
     * 所以这里拦下之后必须留下痕迹：计数与最近一次的内容进 {@code /healthz}，看守靠它动手。
     */
    private void hookServerKick(String className, String[] names, String source,
            boolean kickedInfoArgs) {
        try {
            Class<?> cls = ref.clsOrNull(className);
            if (cls == null) return;
            int hooked = 0;
            for (Method m : cls.getDeclaredMethods()) {
                if (!containsName(names, m.getName()) || m.getReturnType() != void.class) continue;
                m.setAccessible(true);
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        recordBlockedKick(source,
                                describeArgs(p == null ? null : p.args, kickedInfoArgs));
                        if (p != null) p.setResult(null);
                    }
                });
                hooked++;
                HARDENING_HOOKS.incrementAndGet();
            }
            if (hooked > 0) {
                serverKickHookCount += hooked;
                L.i("AntiDetect: blocked server kick handler " + source + " (" + hooked + ")");
            }
        } catch (Throwable t) {
            L.e("AntiDetect.serverKick", t);
        }
    }

    /**
     * 该拦的踢线原因。{@code user}（用户自己退出）、{@code switchAccount}（切号）、
     * {@code expired}（票据自然过期，QQ 自己会重登）、{@code tips}/{@code gray}（只是提示）、
     * {@code restartProcess} 都要放行——拦这些才是真出问题。
     */
    private static final String[] KICK_REASONS_BLOCKED = {
            "kicked", "secKicked", "forceLogout", "suspend",
    };

    public static boolean kickReasonBlocked(String reason) {
        if (reason == null) return false;
        for (String r : KICK_REASONS_BLOCKED) if (r.equals(reason)) return true;
        return false;
    }

    /**
     * MSF 侧强踢的处理口。
     *
     * <p>服务端下发的强制下线在客户端有另一条与 NTKickProcessor 完全独立的路：MSF 把它当错误
     * 事件抛给 {@code mqq.app.MainService$MyErrorHandler}。onKicked / onKickedAndClearToken /
     * onUserTokenExpired / onServerSuspended / onCloneError / onGrayError 各处理各的，界面上
     * 最后都落进 popupNotification（6 参与 8 参两个重载）与 popupNotificationEx，那里做的是
     * {@code appRuntime.logout(reason, true)} 再拿 LoginActivity 发 KICK_TO_LOGIN。
     *
     * <p><b>只拦 popupNotification 是不够的</b>——那是整条链路的最后一环，毁本地登录态的写在它
     * 前面。2026-09-15 18:33 的真踢线是走 onKickedAndClearToken 进来的（reason=kicked、
     * bSigKick != 1），进 onKickedInternal 之后依次做了：
     *
     * <pre>
     * MsfSdkUtils.updateSimpleAccount(uin, false);   // files/user/u_&lt;uin&gt;_t 改名成 _f
     * mApplication.setSortAccountList(...);          // 已登录列表里当场少一个号
     * ... 之后才是被拦下的 popupNotification
     * </pre>
     *
     * 另一支（onKicked，isTokenExpired=false）在更前面还有一句
     * {@code mApplication.setAutoLogin(false)}，同样早于 popupNotification。
     *
     * <p>所以这里拦的是<b>处理器入口</b>：onKicked、onKickedAndClearToken、onKickedInternal
     * 三个一拦，上面那些写操作一次都不会发生；onCloneError 会遍历已登录列表把每个号都摘掉，
     * 也一并拦。popupNotification 那两个出口继续挂着，负责 onUserTokenExpired /
     * onServerSuspended / onGrayError 这些按 reason 分类的回调。
     */
    private void hookMainServiceKick() {
        try {
            Class<?> cls = ref.clsOrNull("mqq.app.MainService$MyErrorHandler");
            if (cls == null) return;
            int hooked = 0;
            // (1) 处理器入口。名字是固定的，参数形状都是 (ToServiceMsg, FromServiceMsg, ...)。
            //     onKicked / onKickedAndClearToken / onKickedInternal 是强制下线那三个；
            //     onCloneError 会把已登录列表里每个号都标成下线，一起拦。
            String[] entries = {"onKicked", "onKickedAndClearToken", "onKickedInternal",
                    "onCloneError"};
            for (Method m : cls.getDeclaredMethods()) {
                if (!containsName(entries, m.getName())) continue;
                if (m.getReturnType() != void.class) continue;
                Class<?>[] pt = m.getParameterTypes();
                if (pt.length < 2 || !pt[0].getName().endsWith("ToServiceMsg")
                        || !pt[1].getName().endsWith("FromServiceMsg")) continue;
                m.setAccessible(true);
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (p == null || p.args == null || p.args.length < 2) return;
                        recordBlockedKick("msf-kick-entry", describeMsfKick(p.args));
                        p.setResult(null);
                    }
                });
                hooked++;
                HARDENING_HOOKS.incrementAndGet();
            }
            // (1b) onGrayError 只当软事件：QQ 认为这个号进了风控灰名单，它自己会登出，
            //      硬拦会让看守每来一次就 force-stop QQ 一次。这里只开善后窗口（保住账号标记
            //      与自动登录开关），并把命令名记进 qk_guard.log 备查，登录流程照旧放行。
            for (Method m : cls.getDeclaredMethods()) {
                if (!"onGrayError".equals(m.getName())) continue;
                if (m.getReturnType() != void.class) continue;
                Class<?>[] pt = m.getParameterTypes();
                if (pt.length < 2 || !pt[0].getName().endsWith("ToServiceMsg")
                        || !pt[1].getName().endsWith("FromServiceMsg")) continue;
                m.setAccessible(true);
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (p == null || p.args == null || p.args.length < 2) return;
                        // onGrayError 兼管短信验证登录的响应：那条命令要原样放行，否则
                        // 重新登录这一步直接被我们挡死。
                        if (isLoginFlowMsg(p.args[1])) return;
                        String uin = uinOf(p.args[1]);
                        if (!uin.isEmpty()) lastKickUin = uin;
                        noteSoftKick(describeMsfKick(p.args));
                    }
                });
                hooked++;
                HARDENING_HOOKS.incrementAndGet();
            }
            // (2) 出口。上面三个入口被拦下之后这里到不了，但 onUserTokenExpired /
            //     onServerSuspended / onGrayError 是 MSF 直接调进来的，只按 reason 过滤拦出口。
            for (Method m : cls.getDeclaredMethods()) {
                String name = m.getName();
                if (!"popupNotification".equals(name) && !"popupNotificationEx".equals(name)) continue;
                if (m.getReturnType() != void.class) continue;
                Class<?>[] pt = m.getParameterTypes();
                // 两个重载的形状都是 (String action, String uin, String title, String msg,
                // Constants$LogoutReason reason, ...)，reason 固定在第 5 个参数上。
                if (pt.length < 5 || !pt[4].getName().endsWith("LogoutReason")) continue;
                m.setAccessible(true);
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (p == null || p.args == null || p.args.length < 5 || p.args[4] == null) return;
                        String reason = String.valueOf(p.args[4]);
                        if (!kickReasonBlocked(reason)) return;
                        StringBuilder sb = new StringBuilder("reason=").append(reason);
                        sb.append(" action=").append(Ref.asStr(p.args[0]));
                        sb.append(" uin=").append(Ref.asStr(p.args[1]));
                        String title = Ref.asStr(p.args[2]);
                        if (!title.isEmpty()) sb.append(" title=").append(title);
                        String msg = Ref.asStr(p.args[3]);
                        if (!msg.isEmpty()) sb.append(" msg=").append(msg);
                        recordBlockedKick("msf-kick", clip(sb.toString(), 200));
                        p.setResult(null);
                    }
                });
                hooked++;
                HARDENING_HOOKS.incrementAndGet();
            }
            if (hooked > 0) {
                serverKickHookCount += hooked;
                L.i("AntiDetect: blocked MSF kick handler (" + hooked + ")");
            } else {
                L.w("AntiDetect: MSF kick handler not found");
            }
        } catch (Throwable t) {
            L.e("AntiDetect.msfKick", t);
        }
    }

    /**
     * 拦住踢线还不够：QQ 在踢线路径上顺手把"下次自动登录"关掉。
     * {@code QQAppInterface.setAutoLogin(false)} → {@code mqq.app.AutoLoginUtil.setAutoLogin(uin,false)}
     * 会把 {@code common_mmkv_configurations} 里的 {@code mqq_account_auto_login_<uin>} 写成 1
     * （2 才是自动）。这个值是落盘的，所以踢线之后哪怕把 QQ 拉起来也停在登录页、不会自己登回来
     * ——看守重启多少次都一样。所以在拦下踢线后的窗口里，把 false 换成 true。
     */
    private void hookAutoLoginGuard() {
        try {
            Class<?> cls = ref.clsOrNull("mqq.app.AutoLoginUtil");
            if (cls == null) return;
            int hooked = 0;
            for (Method m : cls.getDeclaredMethods()) {
                if (!"setAutoLogin".equals(m.getName())) continue;
                Class<?>[] pt = m.getParameterTypes();
                if (pt.length != 2 || pt[0] != String.class || pt[1] != boolean.class) continue;
                m.setAccessible(true);
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (p == null || p.args == null || p.args.length < 2) return;
                        if (!Boolean.FALSE.equals(p.args[1])) return;
                        // 善后期判据（跨重启），不是「拦下踢线后 60 秒」：真正的关自动登录
                        // 发生在 onKickedInternal 里，早于被拦下的 popupNotification，
                        // 那一刻 lastKickMs 还是 0，短窗口根本来不及。
                        if (!inKickAftermath(System.currentTimeMillis())) return;
                        p.args[1] = Boolean.TRUE;
                        AUTO_LOGIN_KEPT.incrementAndGet();
                        L.e("AntiDetect: kept auto-login after blocked kick", null);
                    }
                });
                hooked++;
                HARDENING_HOOKS.incrementAndGet();
            }
            if (hooked > 0) L.i("AntiDetect: auto-login guard (" + hooked + ")");
        } catch (Throwable t) {
            L.e("AntiDetect.autoLogin", t);
        }
    }

    private static String clip(String text, int max) {
        if (text == null) return "";
        String t = text.replace('\n', ' ').replace('\r', ' ');
        return t.length() > max ? t.substring(0, max) : t;
    }

    /**
     * 踢线之后的一段时间里，不许本机自己登出。
     *
     * <p>2026-09-15 17:29 的真踢线上暴露出来的漏洞：拦住了 {@code popupNotification}，
     * {@code blocked_kicks=1}、自动登录也保住了，**但账号还是被登出、要短信验证才能登回来**。
     * 漏的是 UID 那条线——{@code com.tencent.mobileqq.login.api.impl.UidServiceImpl
     * .logoutWhenReqUidFail()}：
     *
     * <pre>
     * peekAppRuntime.setAutoLogin(false);   // ← 命中自动登录守卫，所以 auto_login_kept 涨了
     * peekAppRuntime.logout(true);          // ← 真登出，之前没人拦
     * MsfSdkUtils.updateSimpleAccountNotCreate(uin, false);  // ← 把账号从已登录列表里摘掉
     * </pre>
     *
     * 最后那一步是关键：账号从列表里没了，下次启动没有可自动登录的对象，于是就停在登录页、
     * 要短信验证。之前的 uid-fail 只拦了它**后面**的 {@code kickToLoginPage()}，前一步的真登出
     * 一次都没拦到。
     *
     * <p>{@code logout(true)} 走的 {@code QQAppInterface.logout(boolean)} 不经过
     * {@code AppRuntime.logout(reason,...)}，而且底下会把 reason 写成 {@code user}，
     * 所以按 reason 过滤对它无效——这条只能按窗口拦。
     */
    private void hookLogoutGuard() {
        // 1) UID 拿不到时真登出的那条：拦它，连带它后面的摘账号、报清数据一起停掉。
        try {
            Class<?> cls = ref.clsOrNull("com.tencent.mobileqq.login.api.impl.UidServiceImpl");
            if (cls != null) {
                int n = 0;
                for (Method m : cls.getDeclaredMethods()) {
                    if (!"logoutWhenReqUidFail".equals(m.getName())) continue;
                    if (m.getReturnType() != void.class || m.getParameterTypes().length != 0) continue;
                    m.setAccessible(true);
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            if (!inLogoutGuardWindow(System.currentTimeMillis())) return;
                            noteLogoutGuard("uid.logoutWhenReqUidFail");
                            p.setResult(null);
                        }
                    });
                    n++;
                    HARDENING_HOOKS.incrementAndGet();
                }
                if (n > 0) {
                    logoutGuardHookCount += n;
                    L.i("AntiDetect: logout guard on UidServiceImpl (" + n + ")");
                }
            }
        } catch (Throwable t) {
            L.e("AntiDetect.logoutGuard.uid", t);
        }
        // 2) 用户按得到的那个出口：{@code logout(boolean)} 既服务「用户点退出登录」（主线程），
        //    也服务「踢线路径顺手把自己登出」（UidServiceImpl 在工作线程上调的就是它）；
        //    reason 在它底下被写死成 user，分不出来——按调用线程区分。
        hookLogoutEntry("com.tencent.mobileqq.app.QQAppInterface", "logout", 1, false,
                "qqapp.logout(boolean)");
        hookLogoutEntry("mqq.app.AppRuntime", "logout", 1, false, "appruntime.logout(boolean)");
        // 3) AppRuntime 的核心出口：只有带踢线 reason 的那几种才拦，user / switchAccount /
        //    expired 这些正常生命周期照旧放行。
        hookLogoutEntry("mqq.app.AppRuntime", "logout", 2, true, "appruntime.logout(reason)");
        hookLogoutEntry("mqq.app.AppRuntime", "ntTriggerLogout", 1, true, "appruntime.ntTriggerLogout");
    }

    /**
     * 挂一个登出入口。
     *
     * @param reasonFiltered true 表示只看第一个参数是不是踢线 reason（user / switchAccount /
     *                       expired 等正常登出要放行，并记下「用户主动退出」以免善后期替他保活）；
     *                       false 表示这条入口的 reason 分不出来（{@code logout(boolean)} 底下
     *                       写死 user），此时按调用线程区分：主线程当用户点的，不拦。
     */
    private void hookLogoutEntry(String className, String name, int argc, boolean reasonFiltered,
            String label) {
        try {
            Class<?> cls = ref.clsOrNull(className);
            if (cls == null) return;
            int n = 0;
            for (Method m : cls.getDeclaredMethods()) {
                if (!name.equals(m.getName())) continue;
                if (m.getReturnType() != void.class || m.getParameterTypes().length != argc) continue;
                m.setAccessible(true);
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (!inLogoutGuardWindow(System.currentTimeMillis())) return;
                        if (reasonFiltered) {
                            if (p == null || p.args == null || p.args.length < 1) return;
                            String reason = String.valueOf(p.args[0]);
                            if (!kickReasonBlocked(reason)) {
                                // 只有用户自己按下的才算「主动退出」。expired / gray / tips /
                                // restartProcess 都是 QQ 自己的生命周期，善后期照旧保活。
                                if (userInitiatedLogout(reason)) noteDeliberateLogout();
                                return;
                            }
                        } else if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                            // logout(boolean) 的 reason 在下面被写死成 user，但主线程上那次
                            // 是用户点「退出登录」走的路，工作线程上那次才是踢线顺手登出。
                            noteDeliberateLogout();
                            return;
                        }
                        noteLogoutGuard(label);
                        p.setResult(null);
                    }
                });
                n++;
                HARDENING_HOOKS.incrementAndGet();
            }
            if (n > 0) {
                logoutGuardHookCount += n;
                L.i("AntiDetect: logout guard " + label + " (" + n + ")");
            }
        } catch (Throwable t) {
            L.e("AntiDetect.logoutGuard " + label, t);
        }
    }

    /**
     * 别让踢线把「账号在已登录列表里」这件事从盘上抹掉。
     *
     * <p>{@code MsfSdkUtils.updateSimpleAccount(uin, false)} 做的事是把
     * {@code /data/data/com.tencent.mobileqq/files/user/u_<uin>_t} 改名成 {@code _f}：
     * {@code getLoginedAccountList()} 只认 {@code _t}，改名之后下次启动没有可自动登录的对象，
     * 于是停在登录页要短信验证。这是**盘上的**状态，进程重启也不会自己回来，所以是那一串
     * 「拦住了踢线却还是要重新登录」的根本原因。
     *
     * <p>善后期内把 {@code false} 顶成 {@code true}：改名仍然发生，但改成 {@code _t}，
     * 账号留在已登录列表里。{@code updateSimpleAccountNotCreate} 只在既有文件之间改名，
     * 顶成 true 正好把 {@code _f} 改回 {@code _t}。
     */
    private void hookLoginStateGuard() {
        try {
            Class<?> cls = ref.clsOrNull("com.tencent.mobileqq.msf.sdk.MsfSdkUtils");
            if (cls == null) return;
            int n = 0;
            String[] names = {"updateSimpleAccount", "updateSimpleAccountNotCreate"};
            for (Method m : cls.getDeclaredMethods()) {
                if (!containsName(names, m.getName())) continue;
                Class<?>[] pt = m.getParameterTypes();
                if (m.getReturnType() != void.class || pt.length != 2
                        || pt[0] != String.class || pt[1] != boolean.class) continue;
                m.setAccessible(true);
                final String label = m.getName();
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (p == null || p.args == null || p.args.length < 2) return;
                        if (!Boolean.FALSE.equals(p.args[1])) return;
                        if (!inKickAftermath(System.currentTimeMillis())) return;
                        p.args[1] = Boolean.TRUE;
                        LOGIN_STATE_KEPT.incrementAndGet();
                        noteGuardLine("kept-login-state",
                                label + "(" + Ref.asStr(p.args[0]) + ", false->true)");
                    }
                });
                n++;
                HARDENING_HOOKS.incrementAndGet();
            }
            if (n > 0) {
                loginStateHookCount += n;
                L.i("AntiDetect: login-state guard (" + n + ")");
            }
        } catch (Throwable t) {
            L.e("AntiDetect.loginState", t);
        }
    }

    /**
     * 记一次被拦下的登出。**不写 qk_kick.log**：那个文件是看守「踢线 = 会话已作废，立刻重启」
     * 的判据，多写几行会让看守反复重启 QQ。这里写到旁边的 qk_guard.log。
     */
    private static void noteLogoutGuard(String what) {
        LOGOUT_GUARD_BLOCKS.incrementAndGet();
        noteGuardLine("blocked-logout", what);
    }

    /**
     * 记一行善后动作：被顶回去的落盘写入、自愈修回来的东西。
     *
     * <p>和 {@link #noteLogoutGuard} 落同一个文件（{@code qk_guard.log}，0600），靠行首的
     * 动作名区分。同样不写 {@code qk_kick.log}——那一份是看守「立刻重启 QQ」的判据。
     */
    private static void noteGuardLine(String kind, String what) {
        String line = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
                .format(new java.util.Date(System.currentTimeMillis()))
                + " " + kind + " " + what
                + " (last kick " + lastKickSource + " " + (lastKickMs == 0 ? "-"
                        : ((System.currentTimeMillis() - lastKickMs) / 1000) + "s ago") + ")"
                + " pid=" + android.os.Process.myPid();
        synchronized (GUARD_LOG) {
            GUARD_LOG.addFirst(line);
            while (GUARD_LOG.size() > GUARD_LOG_MAX) GUARD_LOG.removeLast();
        }
        L.e("AntiDetect: " + kind + " — " + line, null);
        appendLine(ENV_DIR, "qk_guard.log", line);
    }

    /** 追加一行到 app 私有目录下的某个日志文件，超 64KB 只留尾部 32KB。 */
    private static void appendLine(String dir, String name, String line) {
        try {
            File d = new File(dir);
            if (!d.isDirectory()) return;
            File f = new File(d, name);
            if (f.length() > 65536L) {
                java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "rw");
                byte[] tail = new byte[(int) Math.min(32768L, f.length())];
                raf.seek(f.length() - tail.length);
                raf.readFully(tail);
                raf.setLength(0);
                raf.write(tail);
                raf.close();
            }
            FileOutputStream out = new FileOutputStream(f, true);
            out.write((line + "\n").getBytes("UTF-8"));
            out.close();
            f.setReadable(false, false);
            f.setReadable(true, true);
            f.setWritable(false, false);
            f.setWritable(true, true);
        } catch (Throwable ignore) {}
    }

    /** 踢线入口的参数形状不一：NTKickProcessor 带 KickedInfo，其余入口按原样记。 */
    private String describeArgs(Object[] args, boolean kickedInfoArgs) {        if (kickedInfoArgs) return describeKick(args);
        if (args == null || args.length == 0) return "no-args";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < args.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(args[i] == null ? "null" : String.valueOf(args[i]));
        }
        String text = sb.toString().replace('\n', ' ').replace('\r', ' ');
        return text.length() > 160 ? text.substring(0, 160) : text;
    }

    /**
     * 把一次踢线的参数整理成一行可读文本。
     *
     * <p>{@code KickedInfo} 比 MSF 那个包多三个字段，而且只有这里有：{@code appId} 指是哪一端
     * 的登录把本机顶了（PC / 手机 / 平板各有 appId），{@code instanceId} 指同一端里的第几个实例，
     * {@code securityKickedType} 是安全强踢的子类型。判断「是不是有人从别处登录」只能靠 appId，
     * 服务端文案里看不出来。
     */
    private String describeKick(Object[] args) {
        StringBuilder sb = new StringBuilder();
        Object info = args != null && args.length > 1 ? args[1] : null;
        if (info != null) {
            sb.append("type=").append(String.valueOf(ref.get(info, "kickedType")));
            sb.append(" kickType=").append(Ref.asInt(ref.get(info, "kickedType")));
            sb.append(" security=").append(String.valueOf(ref.get(info, "securityKickedType")));
            sb.append(" sameDevice=").append(String.valueOf(ref.get(info, "sameDevice")));
            sb.append(" appId=").append(Ref.asInt(ref.get(info, "appId")));
            sb.append(" instanceId=").append(Ref.asInt(ref.get(info, "instanceId")));
            String title = Ref.asStr(ref.get(info, "tipsTitle"));
            if (title != null && !title.isEmpty()) sb.append(" tips=").append(title);
            String desc = Ref.asStr(ref.get(info, "tipsDesc"));
            if (desc != null && !desc.isEmpty()) sb.append(" msg=").append(desc);
        }
        if (args != null && args.length > 2 && args[2] != null)
            sb.append(" reason=").append(args[2]);
        return sb.toString();
    }

    /**
     * MSF 侧踢线的参数：把服务端推下来的强制下线包解开，逐字段记下来。
     *
     * <p>{@code RequestMSFForceOffline} 一共八个字段：{@code bKickType}、{@code bSameDevice}、
     * {@code bSigKick}、{@code iSeqno}、{@code lUin}、{@code strTitle}、{@code strInfo}、
     * {@code vecSigKickData}。能当判据的是前三个加签名段长度：
     *
     * <ul>
     *   <li>{@code bKickType} —— 区分「被另一处登录顶下线」「改密码」「多开」「版本过低」。
     *       2026-09-15 从 16070 的 {@code classes.dex} 里核出 {@code KickedType} 的声明顺序是
     *       {@code KKICKBYMULTIINST, KKICKBYMOBILE, KKICKBYPASSWORDCHANGE, KKCIKBYLOWVERSION}，
     *       名字按这个顺序取。**但「服务端那个字节就是枚举序号」仍是推定**——没有哪个 Java
     *       类读过这个字段（映射在 native，而 native 里也没有这几个枚举名的字符串），所以
     *       名字后面继续带 {@code ?}。0 还有一层歧义：{@code KickedInfo} 的默认构造就是
     *       {@code KickedType.values()[0]}，服务端没填时同样取到 0。</li>
     *   <li>{@code bSigKick} —— 1 表示带 {@code vecSigKickData} 的安全强踢（reason 取
     *       {@code secKicked}），0 是普通强踢（{@code kicked}）。{@code sigLen} 单独记，
     *       因为「说了是安全强踢但签名段是空的」和真有数据是两回事。</li>
     *   <li>{@code bSameDevice} —— 0 表示服务端认这不是同一台设备。</li>
     * </ul>
     *
     * <p>只记 reason 与服务端文案的话，现场只能看到「下线通知 / 你的账号当前登录已失效」，
     * 分不出是风控打击还是账号在别处登录。MSF 这条路拿不到 {@code appId}（那是 NT 内核
     * {@code KickedInfo} 的字段，见 {@link #describeKick}），所以这条路判不到"哪一端"。
     */
    private String describeMsfKick(Object[] args) {
        StringBuilder sb = new StringBuilder();
        Object from = null;
        for (Object a : args) {
            if (a != null && a.getClass().getName().endsWith("FromServiceMsg")) { from = a; break; }
        }
        Object decoded = decodeForceOffline(from);
        if (decoded != null) {
            Object kind = ref.get(decoded, "bKickType");
            sb.append("kickType=").append(kind);
            String name = kickedTypeName(kind);
            if (!name.isEmpty()) sb.append("(").append(name).append("?)");
            sb.append(" sigKick=").append(ref.get(decoded, "bSigKick"));
            sb.append(" sameDevice=").append(ref.get(decoded, "bSameDevice"));
            sb.append(" seqno=").append(Ref.asLong(ref.get(decoded, "iSeqno")));
            // 安全强踢带一段签名数据，长度够区分「真的有 vecSigKickData」与「字段是空的」。
            Object sig = ref.get(decoded, "vecSigKickData");
            sb.append(" sigLen=").append(sig instanceof byte[] ? ((byte[]) sig).length : 0);
            String title = Ref.asStr(ref.get(decoded, "strTitle"));
            if (!title.isEmpty()) sb.append(" title=").append(title);
            String info = Ref.asStr(ref.get(decoded, "strInfo"));
            if (!info.isEmpty()) sb.append(" msg=").append(info);
        }
        String uin = uinOf(from);
        if (!uin.isEmpty()) {
            sb.append(" uin=").append(uin);
            lastKickUin = uin;
        }
        String cmd = msfCommandName(from);
        if (!cmd.isEmpty()) sb.append(" cmd=").append(cmd);
        if (sb.length() == 0) sb.append("no-payload");
        return clip(sb.toString(), 260);
    }

    /**
     * 解出服务端推下来的 {@code RequestMSFForceOffline}，解不出来就返回 null。
     *
     * <p>复用 QQ 自己的 {@code mqq.app.Packet.decodePacket}——那就是 MainService 解这个包用的
     * 入口。别的回调（onUserTokenExpired / onGrayError）带的是另一种包，硬解会得到一堆垃圾字段，
     * 所以解完必须校验：标题或正文至少有一个非空，且 uin 非 0，否则当成没解出来。
     */
    private Object decodeForceOffline(Object fromServiceMsg) {
        if (fromServiceMsg == null) return null;
        try {
            Object raw = fromServiceMsg.getClass().getMethod("getWupBuffer").invoke(fromServiceMsg);
            if (!(raw instanceof byte[]) || ((byte[]) raw).length == 0) return null;
            Class<?> packetCls = ref.clsOrNull("mqq.app.Packet");
            Class<?> structCls = ref.clsOrNull(
                    "com.tencent.msf.service.protocol.push.RequestMSFForceOffline");
            if (packetCls == null || structCls == null) return null;
            Object struct = structCls.newInstance();
            for (Method m : packetCls.getDeclaredMethods()) {
                Class<?>[] pt = m.getParameterTypes();
                if (pt.length != 3 || pt[0] != byte[].class || pt[1] != String.class) continue;
                m.setAccessible(true);
                Object out = m.invoke(null, raw, "RequestMSFForceOffline", struct);
                if (out == null) return null;
                String title = Ref.asStr(ref.get(out, "strTitle"));
                String info = Ref.asStr(ref.get(out, "strInfo"));
                long uin = Ref.asLong(ref.get(out, "lUin"));
                if (title.isEmpty() && info.isEmpty() && uin == 0L) return null;
                return out;
            }
        } catch (Throwable ignore) {}
        return null;
    }

    /** {@code KickedType} 按声明顺序取名字；超出范围返回空串。 */
    private String kickedTypeName(Object value) {
        try {
            int i = Ref.asInt(value);
            Class<?> cls = ref.clsOrNull("com.tencent.qqnt.kernel.nativeinterface.KickedType");
            if (cls == null || !cls.isEnum()) return "";
            Object[] constants = cls.getEnumConstants();
            if (i < 0 || i >= constants.length) return "";
            return String.valueOf(constants[i]);
        } catch (Throwable t) {
            return "";
        }
    }

    /** 从 FromServiceMsg 上取 uin。取不到返回空串。 */
    private static String uinOf(Object fromServiceMsg) {
        if (fromServiceMsg == null) return "";
        try {
            Object uin = fromServiceMsg.getClass().getMethod("getUin").invoke(fromServiceMsg);
            String s = Ref.asStr(uin);
            return s == null ? "" : s.trim();
        } catch (Throwable t) {
            return "";
        }
    }

    /** {@code fromServiceMsg.getMsfCommand()} 的名字，用于日志。 */
    private static String msfCommandName(Object fromServiceMsg) {
        if (fromServiceMsg == null) return "";
        try {
            Object cmd = fromServiceMsg.getClass().getMethod("getMsfCommand").invoke(fromServiceMsg);
            if (cmd == null) return "";
            return cmd instanceof Enum ? ((Enum<?>) cmd).name() : String.valueOf(cmd);
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * 这条 MSF 响应是不是短信验证登录链路的一部分。
     *
     * <p>{@code onGrayError} 兼管 {@code wt_GetStViaSMSVerifyLogin} 与 {@code wt_loginAuth}
     * 的响应——它先把这两个命令转给 {@code receiveMessageFromMSF} 再干别的。整条拦掉会把
     * 「被踢之后靠短信验证登回来」这一步一起封死，所以这两个命令必须原样放行。
     */
    private static boolean isLoginFlowMsg(Object fromServiceMsg) {
        String cmd = msfCommandName(fromServiceMsg);
        return cmd.contains("SMSVerifyLogin") || cmd.contains("loginAuth");
    }

    /** 记下这次踢线。日志走 L.e，不开 verbose 也要能在 logcat 里看到。 */
    public static void recordBlockedKick(String source, String detail) {
        noteBlockedKick(source, detail);
        appendKickLog(lastKickSource, lastKick);
        L.e("AntiDetect: server kick blocked #" + BLOCKED_KICKS.get()
                + " [" + lastKickSource + "] " + lastKick, null);
    }

    /**
     * 把这次踢线追加到 app 私有目录的 {@code qk_kick.log}。
     *
     * <p>只留在内存里不够：{@code blocked_kicks} 会随进程重启归零，而「被拦下的踢线 = 服务端会话
     * 已作废」这件事必须在重启后仍然看得见——看守正是靠它决定要不要立刻重启 QQ。root 读得到这个
     * 文件，普通应用进不来，文件里也只有一行时间戳、来源和一句服务端原文。
     */
    private static void appendKickLog(String source, String detail) {
        // 本次登录活了多久。这一项是判「踢线是周期性的还是事件驱动的」的唯一线索：如果每次都
        // 落在同一个数（例如一小时）附近，那是会话/票据的生命周期到了；如果长短不一，才更像
        // 按行为与设备指纹打分的结果。之前只有绝对时间戳，对不上「活了多少」。
        long up = sessionAgeSeconds();
        String line = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
                .format(new java.util.Date(System.currentTimeMillis()))
                + " " + (source == null ? "" : source)
                + " " + (detail == null ? "" : detail)
                + (up >= 0 ? " up=" + up + "s" : "")
                + " pid=" + android.os.Process.myPid();
        line = line.replace('\n', ' ').replace('\r', ' ');
        if (line.length() > 400) line = line.substring(0, 400);
        KICK_LOG_TOTAL.incrementAndGet();
        synchronized (KICK_LOG) {
            KICK_LOG.addFirst(line);
            while (KICK_LOG.size() > KICK_LOG_MAX) KICK_LOG.removeLast();
        }
        appendLine(ENV_DIR, "qk_kick.log", line);
    }

    /** 本进程看到的「上线时刻」，由 hub 的状态监控在 online 翻转时写进来。0 表示未知。 */
    private static final AtomicLong SESSION_START_MS = new AtomicLong();

    public static void noteOnlineSince(long ms) { SESSION_START_MS.set(ms); }

    /** 这次登录已经活了多久（秒）。没有上线时刻时返回 -1。 */
    private static long sessionAgeSeconds() {
        long start = SESSION_START_MS.get();
        if (start <= 0) return -1;
        long age = (System.currentTimeMillis() - start) / 1000L;
        return age < 0 ? -1 : age;
    }

    /** 只更新状态、不落日志。单测跑在 JVM 上，碰 android.util.Log 会撞上桩实现。 */
    public static void noteBlockedKick(String detail) {
        noteBlockedKick(lastKickSource, detail);
    }

    public static void noteBlockedKick(String source, String detail) {
        lastKickSource = source == null ? "" : source;
        lastKick = detail == null ? "" : detail;
        lastKickMs = System.currentTimeMillis();
        BLOCKED_KICKS.incrementAndGet();
    }

    private void hookSafeDefaults(String className, String[] names, boolean allowObjects,
                                  String label) {
        try {
            Class<?> cls = ref.clsOrNull(className);
            if (cls == null) return;
            int hooked = 0;
            for (Method m : cls.getDeclaredMethods()) {
                if (!containsName(names, m.getName())) continue;
                Object value = safeDefault(m.getReturnType(), allowObjects);
                if (value == UNSUPPORTED) continue;
                m.setAccessible(true);
                final Object replacement = value;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        p.setResult(replacement == VOID_VALUE ? null : replacement);
                    }
                });
                hooked++;
                HARDENING_HOOKS.incrementAndGet();
            }
            if (hooked > 0) L.i("AntiDetect: blocked " + label + " @ " + className
                    + " (" + hooked + ")");
        } catch (Throwable t) {
            L.e("AntiDetect." + label + " " + className, t);
        }
    }

    private static final Object UNSUPPORTED = new Object();
    private static final Object VOID_VALUE = new Object();

    private static Object safeDefault(Class<?> type, boolean allowObjects) {
        if (type == void.class) return VOID_VALUE;
        if (type == boolean.class) return Boolean.FALSE;
        if (type == byte.class) return Byte.valueOf((byte) 0);
        if (type == short.class) return Short.valueOf((short) 0);
        if (type == int.class) return Integer.valueOf(0);
        if (type == long.class) return Long.valueOf(0L);
        if (type == float.class) return Float.valueOf(0F);
        if (type == double.class) return Double.valueOf(0D);
        if (type == char.class) return Character.valueOf('\0');
        return allowObjects ? null : UNSUPPORTED;
    }

    private static Object coerceNumber(Class<?> type, Object value) {
        Number n = value instanceof Number ? (Number) value : Integer.valueOf(0);
        if (type == int.class) return Integer.valueOf(n.intValue());
        if (type == long.class) return Long.valueOf(n.longValue());
        if (type == short.class) return Short.valueOf(n.shortValue());
        if (type == byte.class) return Byte.valueOf(n.byteValue());
        if (type == float.class) return Float.valueOf(n.floatValue());
        if (type == double.class) return Double.valueOf(n.doubleValue());
        if (type == void.class) return null;
        if (value == null || type.isInstance(value)) return value;
        return null;
    }

    private static boolean containsName(String[] names, String name) {
        for (String n : names) if (n.equals(name)) return true;
        return false;
    }

    private static String commandText(Object arg) {
        if (arg == null) return "";
        if (arg instanceof String[]) return join((String[]) arg);
        if (arg instanceof List) return String.valueOf(arg);
        return String.valueOf(arg);
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
                    clearDebuggableFlag(p.getResult());
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

    private static void clearDebuggableFlag(Object result) {
        if (result == null) return;
        try {
            Object ai = result;
            try { ai = result.getClass().getField("applicationInfo").get(result); }
            catch (Throwable ignore) {}
            if (ai instanceof android.content.pm.ApplicationInfo) {
                android.content.pm.ApplicationInfo info = (android.content.pm.ApplicationInfo) ai;
                info.flags &= ~android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE;
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
            XposedBridge.hookAllMethods(android.os.Debug.class, "waitingForDebugger",
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

    public static String safeStringProperty(String name, String serial) {
        if (name == null) return null;
        String adb = adbPropSafe(name);
        if (adb != null) return adb;
        if ("ro.debuggable".equals(name) || "ro.kernel.qemu".equals(name)) return "0";
        if ("ro.secure".equals(name)) return "1";
        if (("ro.boot.serialno".equals(name) || "gsm.serial".equals(name))
                && serial != null && !serial.isEmpty()) return serial;
        return null;
    }

    public static Integer safeIntProperty(String name) {
        if ("ro.debuggable".equals(name) || "ro.kernel.qemu".equals(name)) return 0;
        if ("ro.secure".equals(name)) return 1;
        return null;
    }

    public static Boolean safeBooleanProperty(String name) {
        if ("ro.debuggable".equals(name) || "ro.kernel.qemu".equals(name)) return false;
        if ("ro.secure".equals(name)) return true;
        return null;
    }

    static boolean frameworkText(String text) {
        if (text == null) return false;
        String value = stripIgnorable(text).toLowerCase(Locale.ROOT);
        return value.contains("xposed") || value.contains("lsposed")
                || value.contains("edxposed") || value.contains("lsplant")
                || value.contains("com.satori.qq");
    }

    public static String sanitizeFrameworkText(String text) {
        if (text == null || text.isEmpty()) return text;
        String out = text;
        String[] words = {"lsposed", "edxposed", "xposed", "lsplant", "com.satori.qq"};
        for (String word : words) out = replaceIgnoreCase(out, word, "dalvik");
        return out;
    }

    public static boolean shouldHideProcMapLine(String line) {
        if (line == null || line.isEmpty()) return false;
        int dash = line.indexOf('-');
        int space = line.indexOf(' ');
        if (dash <= 0 || space <= dash) return false;
        for (int i = 0; i < dash; i++) {
            char c = line.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')
                    || (c >= 'A' && c <= 'F'))) return false;
        }
        String value = stripIgnorable(line).toLowerCase(Locale.ROOT);
        String[] words = {"xposed", "lsposed", "edxposed", "zygisk", "riru", "magisk",
                "mapshide", "com.satori.qq", "kernelsu", "ksud", "frida", "substrate",
                "lsplant", "shamiko"};
        for (String word : words) if (value.contains(word)) return true;
        return false;
    }

    private static String replaceIgnoreCase(String value, String needle, String replacement) {
        String lower = value.toLowerCase(Locale.ROOT);
        String target = needle.toLowerCase(Locale.ROOT);
        int at = lower.indexOf(target);
        if (at < 0) return value;
        StringBuilder out = new StringBuilder(value.length());
        int from = 0;
        while (at >= 0) {
            out.append(value, from, at).append(replacement);
            from = at + needle.length();
            at = lower.indexOf(target, from);
        }
        out.append(value, from, value.length());
        return out.toString();
    }

    private static String cleanFake(String value) {
        return value == null ? "" : value.trim();
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
                || p.contains("koushikdutta")
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
                || c.contains("supersu") || c.contains("superuser") || c.contains("busybox")
                || c.contains("which su") || c.contains("type su")
                || c.contains("command -v su")) return true;
        return c.equals("su") || c.startsWith("su ") || c.endsWith("/su")
                || c.contains("/su ") || c.contains("/su/bin")
                || c.contains("/data/local/su");
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
                || p.contains("frida") || p.contains("lsplant")
                || p.contains("koushikdutta") || p.contains("install-recovery.sh");
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
