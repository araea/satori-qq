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
 * mirrors the machine-readable {@code GET /healthz} line. It is not a foreground service and
 * grants no keep-alive priority; process residency stays the watchdog's job.
 */
public final class StatusNotice {
    private static final String CHANNEL_ID = "satori-qq-status";
    private static final String CHANNEL_NAME = "Satori 服务状态";
    // Stable id so every update() replaces the same entry in place rather than stacking.
    private static final int NOTIFY_ID = 0x5A710001;

    private static final int COLOR_ONLINE = 0xFF2E7D32;   // green — running normally
    private static final int COLOR_WAIT = 0xFFF9A825;     // amber — waiting for login
    private static final int COLOR_DEGRADED = 0xFFC62828; // red   — port not listening

    private final Context ctx;
    private final NotificationManager nm;
    private volatile boolean channelReady;
    private volatile String lastKey = "";

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

    /** Refresh the resident notification. Cheap to call every tick: rebuilds only on a visible change. */
    public void update(boolean online, boolean listening, String uin, String nick,
                       int port, int connections, long onlineSinceMs) {
        if (nm == null) return;
        String title;
        String text;
        int color;
        if (online && listening) {
            title = "Satori QQ · 运行中";
            StringBuilder who = new StringBuilder(uin == null || uin.isEmpty() ? "未知账号" : uin);
            if (nick != null && !nick.isEmpty()) who.append(" (").append(nick).append(')');
            text = who + " · 端口 " + port + " · 连接 " + connections;
            color = COLOR_ONLINE;
        } else if (!online) {
            title = "Satori QQ · 等待登录";
            text = "服务就绪，等待 QQ 登录";
            color = COLOR_WAIT;
        } else {
            title = "Satori QQ · 服务异常";
            text = "本地端口 " + port + " 未监听";
            color = COLOR_DEGRADED;
        }

        String big = text;
        String coarseUptime = "";
        if (online && listening && onlineSinceMs > 0) {
            long up = System.currentTimeMillis() - onlineSinceMs;
            coarseUptime = String.valueOf(up / 60000L); // minute granularity for dedupe
            big = text + "\n在线 " + humanUptime(up);
        }
        // Skip the notify() when nothing the user would see has changed (title/text/minute).
        String key = title + '|' + text + '|' + coarseUptime;
        if (key.equals(lastKey)) return;

        // The post is dropped silently when QQ lacks POST_NOTIFICATIONS (the user disabled QQ
        // notifications, or Android 13+ hasn't granted it). Don't cache the key then, so the entry
        // self-heals the moment notifications are re-enabled instead of waiting for a state change.
        boolean enabled;
        try { enabled = nm.areNotificationsEnabled(); } catch (Throwable t) { enabled = true; }

        ensureChannel();
        try {
            Notification.Builder b = new Notification.Builder(ctx, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_notify_sync)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(new Notification.BigTextStyle().bigText(big))
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setShowWhen(false)
                    .setColor(color);
            nm.notify(NOTIFY_ID, b.build());
            if (enabled) lastKey = key;
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
