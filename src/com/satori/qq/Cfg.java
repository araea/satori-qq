package com.satori.qq;

import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.ByteArrayOutputStream;

/** Runtime config, loaded best-effort from a JSON file readable by the QQ process.
 *  Falls back to sane defaults so the module works out-of-the-box. */
public final class Cfg {
    public volatile int port = 3001;
    public volatile String host = "127.0.0.1"; // Satori HTTP/WS is local-only; smaller network/detection surface
    public volatile String token = "";        // empty => no auth required
    public volatile boolean heartbeat = true;
    public volatile int heartbeatMs = 15000;
    /** Post a resident status notification (in QQ's notification shade) while the service runs. */
    public volatile boolean statusNotification = true;
    /** VPN-style keepalive: hold QQ's main process as a foreground service while online, so the OS
     *  won't background-freeze/kill it. Cooperative — closing QQ stops it, no watchdog relaunch. */
    public volatile boolean foregroundKeepalive = true;
    /** One-time cooperative request for Doze battery-optimization exemption (system dialog). */
    public volatile boolean requestBatteryExemption = true;
    /** Termux-style acquire/release wake-lock toggle on the resident notification (opt-in per tap). */
    public volatile boolean wakeLockControl = true;
    /** Take the wake-lock hold automatically at startup instead of waiting for the first tap. */
    public volatile boolean wakeLockAuto = true;
    /** Hold a high-performance Wi-Fi lock while a Satori client is attached, so screen-off power
     *  save cannot park the radio under an outbound upload or delay inbound events. */
    public volatile boolean wifiSustain = true;
    public volatile boolean antiDetect = true;   // Java-level anti-detection (see AntiDetect)
    public volatile boolean mapsHide = true;     // native /proc/self/maps filter (v3)
    public volatile boolean verboseLogs = false; // verbose logcat/Xposed logs are observable; opt in for debugging
    public volatile boolean blockQsecTasks = true;    // skip QSec.execTasks native worker
    public volatile boolean blockQsecReports = true;  // neutralise dedicated QSec.reportLog telemetry
    public volatile boolean observeFekitAttach = true; // count-only; never changes signing/attach bytes
    public volatile boolean blockO3Report = true;      // drop trpc.o3.report / mobile_security (QQNTHookBypass)
    /** QQ risk-control entry points covered by QQEnhancedBypass. Type-checked before hooking. */
    public volatile boolean blockTuringRisk = true;
    /**
     * 人脸核身（慧眼 SDK + TuringFace）那条链路的设备信息上报。
     *
     * <p>与 {@link #blockO3Report} 分开，是因为它走的是另一条路：QQ 的 dt 模块用
     * {@code MainProcess2Fe.k(..., "face_detect"/"camera_detect", ...)} 把慧眼 SDK 采集的
     * 设备数据交给 {@code O3BusinessHandler.P2} → MSF {@code cmd_sec_dispatch_event} 发出去。
     * 命令名过滤（{@link #blockO3Report}）拦不到它，所以单开一个开关。
     *
     * <p><b>关闭它等于把这批数据放行。</b>服务端判「设备环境异常」时如果依赖的是这批数据，
     * 关掉反而更脏；如果判据来自服务端已经存下的历史，关不关都一样。要不要关只能靠 A/B
     * 试出来——这是开关存在的唯一理由。
     */
    public volatile boolean blockFaceReport = true;
    /** Suppress the local handler for server kick packets; a revoked server session still needs login. */
    public volatile boolean blockServerKick = true;
    /**
     * 拦下踢线之后，由模块补一次「干净下线」让服务端收到 offline。**默认关**，而且是有理由关的：
     * 2026-09-16 真机实测，{@code logout(restartProcess, true)} 会把登录票据一起放掉，账号从
     * 已登录列表里被摘掉，QQ 重启后停在登录页、连自动登录都回不来——比被踢一次更麻烦。
     * 保留这个开关是给「服务端会话已经作废、本地凭据也没救了」的场合用的，默认那条路不走它。
     */
    public volatile boolean cleanOfflineOnKick = false;
    /** Empty values keep the real device identifiers. Set all required values together. */
    public volatile String fakeImei = "";
    public volatile String fakeAndroidId = "";
    public volatile String fakeSerial = "";
    public volatile int outboundMinIntervalMs = 1000; // serialize writes and avoid bursty QQ operations
    public volatile int outboundQueueTimeoutMs = 30000;
    public volatile int outboundMaxQueued = 8;
    public volatile int onlineStabilizeMs = 30000;     // do not write immediately after session recovery
    public volatile int outboundMaxPerMinute = 20;
    /** Extra attempts for a send whose rich-media upload failed inside QQ's kernel. The upload runs
     *  before the message is dispatched, so a retry cannot duplicate anything the peer has seen. */
    public volatile int mediaRetryAttempts = 2;
    public volatile int mediaRetryBackoffMs = 4000;
    /** Wall-clock ceiling for those retries; clients wait on a single HTTP call. */
    public volatile int mediaRetryBudgetMs = 45000;
    public volatile int outboundFailureThreshold = 3;
    public volatile int outboundCircuitOpenMs = 120000;
    /** Deliver messages typed in the QQ UI to Koishi as a distinct operator identity. */
    public volatile boolean manualSelfMessages = true;
    /** Empty uses the stable, non-QQ id "qq-client:{selfUin}". */
    public volatile String manualSelfUserId = "";
    /** Merge-forward strategy: "auto" (native first, fake fallback), "native" (self-chat
     *  scaffolding only), "fake" (SsoSendLongMsg card only — QQNT viewers may fail to open). */
    public volatile String forwardMode = "auto";

    private static final String[] PATHS = new String[]{
        // QQ can always read its own external files dir under scoped storage
        "/sdcard/Android/data/com.tencent.mobileqq/files/satori-qq.json",
        "/storage/emulated/0/Android/data/com.tencent.mobileqq/files/satori-qq.json",
        "/sdcard/satori-qq.json",
        "/storage/emulated/0/satori-qq.json",
    };

    public static Cfg load() {
        Cfg c = new Cfg();
        for (String p : PATHS) {
            try {
                File f = new File(p);
                if (!f.isFile()) continue;
                FileInputStream in = new FileInputStream(f);
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[4096]; int n;
                while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                in.close();
                JSONObject o = new JSONObject(bo.toString("UTF-8"));
                c.port = o.optInt("port", c.port);
                c.host = o.optString("host", c.host);
                c.token = o.optString("token", c.token);
                c.heartbeat = o.optBoolean("heartbeat", c.heartbeat);
                c.heartbeatMs = o.optInt("heartbeat_ms", c.heartbeatMs);
                c.statusNotification = o.optBoolean("status_notification", c.statusNotification);
                c.foregroundKeepalive = o.optBoolean("foreground_keepalive", c.foregroundKeepalive);
                c.requestBatteryExemption = o.optBoolean("request_battery_exemption", c.requestBatteryExemption);
                c.wakeLockControl = o.optBoolean("wake_lock_control", c.wakeLockControl);
                c.wakeLockAuto = o.optBoolean("wake_lock_auto", c.wakeLockAuto);
                c.wifiSustain = o.optBoolean("wifi_sustain", c.wifiSustain);
                c.antiDetect = o.optBoolean("anti_detect", c.antiDetect);
                c.mapsHide = o.optBoolean("maps_hide", c.mapsHide);
                c.verboseLogs = o.optBoolean("verbose_logs", c.verboseLogs);
                c.blockQsecTasks = o.optBoolean("block_qsec_tasks", c.blockQsecTasks);
                c.blockQsecReports = o.optBoolean("block_qsec_reports", c.blockQsecReports);
                c.observeFekitAttach = o.optBoolean("observe_fekit_attach", c.observeFekitAttach);
                c.blockO3Report = o.optBoolean("block_o3_report", c.blockO3Report);
                c.blockTuringRisk = o.optBoolean("block_turing_risk", c.blockTuringRisk);
                c.blockFaceReport = o.optBoolean("block_face_report", c.blockFaceReport);
                c.blockServerKick = o.optBoolean("block_server_kick", c.blockServerKick);
                c.cleanOfflineOnKick =
                        o.optBoolean("clean_offline_on_kick", c.cleanOfflineOnKick);
                c.fakeImei = o.optString("fake_imei", c.fakeImei).trim();
                c.fakeAndroidId = o.optString("fake_android_id", c.fakeAndroidId).trim();
                c.fakeSerial = o.optString("fake_serial", c.fakeSerial).trim();
                c.outboundMinIntervalMs = bounded(o.optInt("outbound_min_interval_ms", c.outboundMinIntervalMs), 0, 60000);
                c.outboundQueueTimeoutMs = bounded(o.optInt("outbound_queue_timeout_ms", c.outboundQueueTimeoutMs), 1000, 120000);
                c.outboundMaxQueued = bounded(o.optInt("outbound_max_queued", c.outboundMaxQueued), 1, 128);
                c.onlineStabilizeMs = bounded(o.optInt("online_stabilize_ms", c.onlineStabilizeMs), 0, 300000);
                c.outboundMaxPerMinute = bounded(o.optInt("outbound_max_per_minute", c.outboundMaxPerMinute), 1, 600);
                c.outboundFailureThreshold = bounded(o.optInt("outbound_failure_threshold", c.outboundFailureThreshold), 1, 20);
                c.mediaRetryAttempts = bounded(o.optInt("media_retry_attempts", c.mediaRetryAttempts), 0, 5);
                c.mediaRetryBackoffMs = bounded(o.optInt("media_retry_backoff_ms", c.mediaRetryBackoffMs), 0, 60000);
                c.mediaRetryBudgetMs = bounded(o.optInt("media_retry_budget_ms", c.mediaRetryBudgetMs), 0, 300000);
                c.outboundCircuitOpenMs = bounded(o.optInt("outbound_circuit_open_ms", c.outboundCircuitOpenMs), 1000, 1800000);
                c.manualSelfMessages = o.optBoolean("manual_self_messages", c.manualSelfMessages);
                c.manualSelfUserId = o.optString("manual_self_user_id", c.manualSelfUserId).trim();
                c.forwardMode = normalizeForwardMode(o.optString("forward_mode", c.forwardMode));
                L.i("Config loaded from " + p + " (port=" + c.port + ", auth=" + (c.token.isEmpty()?"off":"on") + ")");
                return c;
            } catch (Throwable t) {
                L.w("Failed reading config " + p + ": " + t);
            }
        }
        L.i("No config file found; using defaults (port=" + c.port + ", no auth)");
        return c;
    }

    private static int bounded(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String normalizeForwardMode(String raw) {
        String v = raw == null ? "" : raw.trim().toLowerCase(java.util.Locale.ROOT);
        return "native".equals(v) || "fake".equals(v) ? v : "auto";
    }
}
