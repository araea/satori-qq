package com.onebot.qq;

import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.ByteArrayOutputStream;

/** Runtime config, loaded best-effort from a JSON file readable by the QQ process.
 *  Falls back to sane defaults so the module works out-of-the-box. */
public final class Cfg {
    public volatile int port = 3001;
    public volatile String host = "127.0.0.1"; // forward WS is local-only; smaller network/detection surface
    public volatile String token = "";        // empty => no auth required
    public volatile boolean heartbeat = true;
    public volatile int heartbeatMs = 15000;
    public volatile boolean antiDetect = true;   // best-effort Java-level anti-detection (see AntiDetect)
    public volatile boolean mapsHide = false;    // EXPERIMENTAL native /proc/self/maps filter (default OFF)

    private static final String[] PATHS = new String[]{
        // QQ can always read its own external files dir under scoped storage
        "/sdcard/Android/data/com.tencent.mobileqq/files/onebot-qq.json",
        "/storage/emulated/0/Android/data/com.tencent.mobileqq/files/onebot-qq.json",
        "/sdcard/onebot-qq.json",
        "/storage/emulated/0/onebot-qq.json",
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
                L.i("Config loaded from " + p + " (port=" + c.port + ", auth=" + (c.token.isEmpty()?"off":"on") + ")");
                return c;
            } catch (Throwable t) {
                L.w("Failed reading config " + p + ": " + t);
            }
        }
        L.i("No config file found; using defaults (port=" + c.port + ", no auth)");
        return c;
    }
}
