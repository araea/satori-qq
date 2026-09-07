package com.satori.qq.core;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;

import com.satori.qq.L;

/**
 * Resident status notification for the Satori service.
 *
 * <p>Posted from inside the QQ process, so it uses QQ's own {@link Context} and QQ's
 * POST_NOTIFICATIONS grant — the module declares no notification permission of its own.
 * A single low-importance (silent) channel carries one ongoing entry whose text tracks the
 * live service state, giving the operator a human-readable "is the bot alive" indicator that
 * mirrors the machine-readable {@code GET /healthz} line. When foreground keepalive is on the
 * same {@link Notification} is handed to {@code startForeground} (see {@code Keepalive}); this
 * class only builds and posts it, it grants no keep-alive priority by itself.
 */
public final class StatusNotice {
    private static final String CHANNEL_ID = "satori-qq-status";
    private static final String CHANNEL_NAME = "Satori 服务状态";
    // Stable id so every update() replaces the same entry in place, and so startForeground()
    // and notify() address one and the same notification.
    public static final int NOTIFY_ID = 0x5A710001;

    private static final int COLOR_ONLINE = 0xFF2E7D32;   // green — running normally
    private static final int COLOR_WAIT = 0xFFF9A825;     // amber — waiting for login
    private static final int COLOR_DEGRADED = 0xFFC62828; // red   — port not listening

    private final Context ctx;
    private final NotificationManager nm;
    private volatile boolean channelReady;
    private volatile String lastKey = "";
    // Optional user-toggled wake lock, surfaced as a notification action button (Termux-style).
    private volatile com.satori.qq.qq.WakeLockCtl wake;

    public StatusNotice(Context ctx) {
        this.ctx = ctx;
        NotificationManager m = null;
        try {
            if (ctx != null) m = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        } catch (Throwable t) {
            L.e("notice: no NotificationManager", t);
        }
        this.nm = m;
    }

    public boolean available() { return nm != null; }

    /** Attach the wake-lock controller so its acquire/release toggle rides on the resident entry. */
    public void setWake(com.satori.qq.qq.WakeLockCtl w) { this.wake = w; }

    /** Diagnostic string for /healthz: whether the OS currently accepts our posts. */
    public String diag() {
        if (nm == null) return "no-nm";
        boolean en;
        try { en = nm.areNotificationsEnabled(); } catch (Throwable t) { en = true; }
        return (en ? "enabled" : "disabled") + "/posted=" + (lastKey.isEmpty() ? "no" : "yes")
                + "/chan=" + channelReady;
    }

    private void ensureChannel() {
        if (channelReady || nm == null) return;
        try {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Satori QQ 常驻服务的运行状态");
            ch.setShowBadge(false);
            ch.enableVibration(false);
            ch.enableLights(false);
            ch.setSound(null, null);
            nm.createNotificationChannel(ch);
            channelReady = true;
        } catch (Throwable t) {
            L.e("notice: create channel", t);
        }
    }

    private static final class View {
        String title, text, big, key;
        int color;
    }

    private View render(boolean online, boolean listening, String uin, String nick,
                        int port, int connections, long onlineSinceMs) {
        View v = new View();
        if (online && listening) {
            v.title = "Satori QQ · 运行中";
            StringBuilder who = new StringBuilder(uin == null || uin.isEmpty() ? "未知账号" : uin);
            if (nick != null && !nick.isEmpty()) who.append(" (").append(nick).append(')');
            v.text = who + " · 端口 " + port + " · 连接 " + connections;
            v.color = COLOR_ONLINE;
        } else if (!online) {
            v.title = "Satori QQ · 等待登录";
            v.text = "服务就绪，等待 QQ 登录";
            v.color = COLOR_WAIT;
        } else {
            v.title = "Satori QQ · 服务异常";
            v.text = "本地端口 " + port + " 未监听";
            v.color = COLOR_DEGRADED;
        }
        v.big = v.text;
        String coarse = "";
        if (online && listening && onlineSinceMs > 0) {
            long up = System.currentTimeMillis() - onlineSinceMs;
            coarse = String.valueOf(up / 60000L); // minute granularity for dedupe
            v.big = v.text + "\n在线 " + humanUptime(up);
        }
        com.satori.qq.qq.WakeLockCtl w = wake;
        String wk = w == null ? "w-" : (w.held() ? "w1" : "w0");
        v.key = v.title + '|' + v.text + '|' + coarse + '|' + wk;
        return v;
    }

    private Notification notif(View v) {
        Notification.Builder b = new Notification.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle(v.title)
                .setContentText(v.text)
                .setStyle(new Notification.BigTextStyle().bigText(v.big))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setColor(v.color);
        addWakeAction(b);
        return b.build();
    }

    /** Termux-style acquire/release wake-lock button. Silently skipped when no controller is set. */
    private void addWakeAction(Notification.Builder b) {
        com.satori.qq.qq.WakeLockCtl w = wake;
        if (w == null) return;
        try {
            android.app.PendingIntent pi = w.toggleIntent();
            if (pi == null) return;
            int icon = w.held() ? android.R.drawable.ic_lock_idle_lock
                                : android.R.drawable.ic_lock_lock;
            b.addAction(icon, w.label(), pi);
        } catch (Throwable t) {
            L.e("notice: wake action", t);
        }
    }

    /** Build the current-state notification (channel ensured), for handing to startForeground(). */
    public Notification build(boolean online, boolean listening, String uin, String nick,
                              int port, int connections, long onlineSinceMs) {
        if (nm == null) return null;
        ensureChannel();
        try {
            return notif(render(online, listening, uin, nick, port, connections, onlineSinceMs));
        } catch (Throwable t) {
            L.e("notice: build", t);
            return null;
        }
    }

    /** Refresh the resident notification. Cheap to call every tick: rebuilds only on a visible change. */
    public void update(boolean online, boolean listening, String uin, String nick,
                       int port, int connections, long onlineSinceMs) {
        if (nm == null) return;
        View v = render(online, listening, uin, nick, port, connections, onlineSinceMs);
        if (v.key.equals(lastKey)) return;

        // The post is dropped silently when QQ lacks POST_NOTIFICATIONS (the user disabled QQ
        // notifications, or Android 13+ hasn't granted it). Don't cache the key then, so the entry
        // self-heals the moment notifications are re-enabled instead of waiting for a state change.
        boolean enabled;
        try { enabled = nm.areNotificationsEnabled(); } catch (Throwable t) { enabled = true; }

        ensureChannel();
        try {
            nm.notify(NOTIFY_ID, notif(v));
            if (enabled) lastKey = v.key;
        } catch (Throwable t) {
            L.e("notice: notify", t);
        }
    }

    public void cancel() {
        lastKey = "";
        if (nm == null) return;
        try {
            nm.cancel(NOTIFY_ID);
        } catch (Throwable ignore) {}
    }

    static String humanUptime(long ms) {
        if (ms < 0) ms = 0;
        long s = ms / 1000L;
        long m = s / 60L;
        long h = m / 60L;
        long d = h / 24L;
        if (d > 0) return d + " 天 " + (h % 24) + " 小时";
        if (h > 0) return h + " 小时 " + (m % 60) + " 分";
        if (m > 0) return m + " 分";
        return s + " 秒";
    }
}
