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
    public volatile boolean antiDetect = true;   // Java-level anti-detection (see AntiDetect)
    public volatile boolean mapsHide = true;     // native /proc/self/maps filter (v3)
    public volatile boolean verboseLogs = false; // verbose logcat/Xposed logs are observable; opt in for debugging
    public volatile boolean blockQsecTasks = true;    // skip QSec.execTasks native worker
    public volatile boolean blockQsecReports = true;  // neutralise dedicated QSec.reportLog telemetry
    public volatile boolean observeFekitAttach = true; // count-only; never changes signing/attach bytes
    public volatile boolean blockO3Report = true;      // drop trpc.o3.report / mobile_security (QQNTHookBypass)
    public volatile int outboundMinIntervalMs = 1000; // serialize writes and avoid bursty QQ operations
    public volatile int outboundQueueTimeoutMs = 30000;
    public volatile int outboundMaxQueued = 8;
    public volatile int onlineStabilizeMs = 30000;     // do not write immediately after session recovery
    public volatile int outboundMaxPerMinute = 20;
    public volatile int outboundFailureThreshold = 3;
    public volatile int outboundCircuitOpenMs = 120000;
    /** Deliver messages typed in the QQ UI to Koishi as a distinct operator identity. */
    public volatile boolean manualSelfMessages = true;
    /** Empty uses the stable, non-QQ id "qq-client:{selfUin}". */
    public volatile String manualSelfUserId = "";

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
                c.antiDetect = o.optBoolean("anti_detect", c.antiDetect);
                c.mapsHide = o.optBoolean("maps_hide", c.mapsHide);
                c.verboseLogs = o.optBoolean("verbose_logs", c.verboseLogs);
                c.blockQsecTasks = o.optBoolean("block_qsec_tasks", c.blockQsecTasks);
                c.blockQsecReports = o.optBoolean("block_qsec_reports", c.blockQsecReports);
                c.observeFekitAttach = o.optBoolean("observe_fekit_attach", c.observeFekitAttach);
                c.blockO3Report = o.optBoolean("block_o3_report", c.blockO3Report);
                c.outboundMinIntervalMs = bounded(o.optInt("outbound_min_interval_ms", c.outboundMinIntervalMs), 0, 60000);
                c.outboundQueueTimeoutMs = bounded(o.optInt("outbound_queue_timeout_ms", c.outboundQueueTimeoutMs), 1000, 120000);
                c.outboundMaxQueued = bounded(o.optInt("outbound_max_queued", c.outboundMaxQueued), 1, 128);
                c.onlineStabilizeMs = bounded(o.optInt("online_stabilize_ms", c.onlineStabilizeMs), 0, 300000);
                c.outboundMaxPerMinute = bounded(o.optInt("outbound_max_per_minute", c.outboundMaxPerMinute), 1, 600);
                c.outboundFailureThreshold = bounded(o.optInt("outbound_failure_threshold", c.outboundFailureThreshold), 1, 20);
                c.outboundCircuitOpenMs = bounded(o.optInt("outbound_circuit_open_ms", c.outboundCircuitOpenMs), 1000, 1800000);
                c.manualSelfMessages = o.optBoolean("manual_self_messages", c.manualSelfMessages);
                c.manualSelfUserId = o.optString("manual_self_user_id", c.manualSelfUserId).trim();
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
}
