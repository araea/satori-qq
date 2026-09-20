package com.satori.qq.core;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.service.notification.StatusBarNotification;

import com.satori.qq.L;

/**
 * Resident status notification for the Satori service.
 *
 * <p>Posted from inside the QQ process, so it uses QQ's own {@link Context} and QQ's
 * POST_NOTIFICATIONS grant — the module declares no notification permission of its own.
 * A single low-importance (silent) channel carries one ongoing entry whose text tracks the
 * live service state, giving the operator a human-readable "is the bot alive" indicator that
 * mirrors the machine-readable {@code GET /healthz} line. 本类只负责构建与发布，不附带任何
 * 保活优先级。
 *
 * <p>Tapping the entry opens QQ. The module runs under QQ's identity, so the host package is QQ
 * itself and its launcher activity is the tap target. The {@link PendingIntent} is resolved once
 * and cached — this notification is rebuilt on every monitor tick.
 */
public final class StatusNotice {
    private static final String CHANNEL_ID = "satori-qq-status";
    private static final String CHANNEL_NAME = "知弦服务状态";
    // Stable id so every update() replaces the same entry in place, and so startForeground()
    // and notify() address one and the same notification.
    public static final int NOTIFY_ID = 0x5A710001;
    private static final int REQ_OPEN_APP = 0x5A710003;

    private static final int COLOR_ONLINE = 0xFF5D438B;   // expressive iris brand primary
    private static final int COLOR_WAIT = 0xFF775A0B;     // readable amber
    private static final int COLOR_DEGRADED = 0xFFBA1A1A; // Material error role

    /**
     * How soon the same state may be re-posted after the entry vanished.
     *
     * <p>QQ 每次回到前台都会把自家通知清一遍（{@code cancelAll()}），常驻条目因此消失；本类
     * 靠“条目还在不在”把它补回来。下限只是防止系统反复吞掉时把 notify 打成死循环。
     */
    private static final long REPOST_MIN_GAP_MS = 3000L;

    private final Context ctx;
    private final NotificationManager nm;
    private volatile boolean channelReady;
    private volatile String lastKey = "";
    private volatile long lastPostAt;
    private volatile long repostCount;
    // Optional user-toggled wake lock, surfaced as a notification action button (Termux-style).
    private volatile com.satori.qq.qq.WakeLockCtl wake;
    // Tap target, resolved once (see openAppIntent); openAppTried keeps a failed lookup from
    // repeating a package query on every rebuild.
    private volatile PendingIntent openApp;
    private volatile boolean openAppTried;

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
                + "/chan=" + channelReady + "/reposts=" + repostCount;
    }

    /**
     * Decide whether this tick should (re)post the entry.
     *
     * <p>Pure so the regression test can pin the QQ-foreground case: the content key never changes
     * there, and only {@code alive=false} (the entry was cleared) may drive a re-post.
     *
     * @param changed content differs from what was last posted
     * @param enabled notifications are enabled for the host package
     * @param cached  something has been posted before
     * @param alive   our entry is still in the shade
     * @param sinceMs milliseconds since the last post
     * @param minGapMs throttle for identical re-posts
     */
    static boolean shouldPost(boolean changed, boolean enabled, boolean cached,
                              boolean alive, long sinceMs, long minGapMs) {
        if (changed) return true;
        if (!enabled || !cached) return false;
        if (sinceMs < minGapMs) return false;
        return !alive;
    }

    private void ensureChannel() {
        if (channelReady || nm == null) return;
        try {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("知弦 Satori QQ 服务的运行状态");
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
            v.title = "知弦 · 运行中";
            StringBuilder who = new StringBuilder(uin == null || uin.isEmpty() ? "未知账号" : uin);
            if (nick != null && !nick.isEmpty()) who.append(" (").append(nick).append(')');
            v.text = connections == 0 ? "等待客户端连接 · 端口 " + port
                    : "已连接 " + connections + " 个客户端 · 端口 " + port;
            v.big = "QQ " + who + "\n" + v.text;
            v.color = COLOR_ONLINE;
        } else if (!online) {
            v.title = "知弦 · 等待登录";
            v.text = listening ? "打开 QQ 登录，即可连接服务" : "等待 QQ 登录与本地服务启动";
            v.color = COLOR_WAIT;
        } else {
            v.title = "知弦 · 服务异常";
            v.text = "本地端口 " + port + " 未监听";
            v.color = COLOR_DEGRADED;
        }
        if (v.big == null) v.big = v.text;
        String coarse = "";
        if (online && listening && onlineSinceMs > 0) {
            long up = System.currentTimeMillis() - onlineSinceMs;
            coarse = String.valueOf(up / 60000L); // minute granularity for dedupe
            v.big += "\n已在线 " + humanUptime(up);
        }
        com.satori.qq.qq.WakeLockCtl w = wake;
        String wk = w == null ? "w-" : (w.held() ? "w1" : "w0");
        v.key = v.title + '|' + v.text + '|' + uin + '|' + nick + '|' + coarse + '|' + wk;
        return v;
    }

    private Notification notif(View v) {
        Notification.Builder b = new Notification.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(StatusIcon.get())
                .setContentTitle(v.title)
                .setContentText(v.text)
                .setStyle(new Notification.BigTextStyle().bigText(v.big))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setColor(v.color);
        PendingIntent open = openAppIntent();
        if (open != null) b.setContentIntent(open);
        addWakeAction(b);
        return b.build();
    }

    /**
     * PendingIntent that brings QQ to the front when the entry is tapped.
     *
     * <p>The host package is QQ itself, so its launcher activity is the tap target; the launcher
     * Intent already carries {@code FLAG_ACTIVITY_NEW_TASK}, which reuses the running task instead
     * of stacking a second one. Resolved once and cached: an unresolvable launcher activity leaves
     * the entry tappable but inert, which is the behaviour without this method.
     */
    private PendingIntent openAppIntent() {
        PendingIntent cached = openApp;
        if (cached != null || openAppTried) return cached;
        openAppTried = true;
        try {
            Intent it = ctx.getPackageManager().getLaunchIntentForPackage(ctx.getPackageName());
            if (it == null) {
                L.e("notice: no launcher activity for " + ctx.getPackageName(), null);
                return null;
            }
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_IMMUTABLE;
            L.i("notice: tap opens " + it.getComponent());
            openApp = PendingIntent.getActivity(ctx, REQ_OPEN_APP, it, flags);
            return openApp;
        } catch (Throwable t) {
            L.e("notice: open app intent", t);
            return null;
        }
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

    /**
     * Refresh the resident notification.
     *
     * <p>Cheap to call every tick: it rebuilds only on a visible change, and otherwise just checks
     * whether our entry is still in the shade. That check is what fixes "QQ 在前台时常驻通知消失" —
     * QQ clears its own notifications when its main window comes to the front, and a key-only cache
     * would never re-post the identical entry.
     */
    public void update(boolean online, boolean listening, String uin, String nick,
                       int port, int connections, long onlineSinceMs) {
        if (nm == null) return;
        View v = render(online, listening, uin, nick, port, connections, onlineSinceMs);
        boolean enabled;
        try { enabled = nm.areNotificationsEnabled(); } catch (Throwable t) { enabled = true; }
        boolean changed = !v.key.equals(lastKey);
        long now = System.currentTimeMillis();
        boolean alive = changed || stillPosted();
        if (!shouldPost(changed, enabled, !lastKey.isEmpty(),
                alive, now - lastPostAt, REPOST_MIN_GAP_MS)) return;

        ensureChannel();
        try {
            nm.notify(NOTIFY_ID, notif(v));
            lastPostAt = now;
            if (!changed && enabled) {
                repostCount++;
                L.i("notice: re-posted after removal (reposts=" + repostCount + ")");
            }
            // Cache even while notifications are disabled: the stillPosted() check re-posts it as
            // soon as they come back, so self-healing no longer depends on a state change.
            lastKey = v.key;
        } catch (Throwable t) {
            L.e("notice: notify", t);
        }
    }

    /**
     * Whether our own entry is still posted. {@link NotificationManager#getActiveNotifications()}
     * only ever returns the calling app's notifications, so no listener permission is involved.
     * On failure we assume it is alive — a wrong guess then is a missing entry, not a repost loop.
     */
    private boolean stillPosted() {
        if (nm == null) return true;
        try {
            StatusBarNotification[] active = nm.getActiveNotifications();
            if (active == null) return true;
            String pkg = ctx == null ? null : ctx.getPackageName();
            for (StatusBarNotification sbn : active) {
                if (sbn == null || sbn.getId() != NOTIFY_ID) continue;
                if (pkg == null || pkg.equals(sbn.getPackageName())) return true;
            }
            return false;
        } catch (Throwable t) {
            return true;
        }
    }

    public void cancel() {
        lastKey = "";
        lastPostAt = 0;
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
