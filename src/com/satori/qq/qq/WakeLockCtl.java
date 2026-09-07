package com.satori.qq.qq;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.PowerManager;

import com.satori.qq.L;

/**
 * Termux-style, user-toggled wake lock exposed as a notification action button.
 *
 * <p>The foreground-service keepalive ({@link Keepalive}) keeps QQ's <em>process</em> resident, but
 * a process kept in the foreground-service priority band can still have its CPU parked once the
 * device enters Doze — enough to stall the Satori websocket for minutes. Mirroring Termux's
 * "ACQUIRE WAKELOCK / RELEASE WAKELOCK" toggle, this holds a {@code PARTIAL_WAKE_LOCK} (plus a
 * best-effort high-performance Wi-Fi lock) so the CPU and radio stay awake while the operator wants
 * maximum residency, and releases them to save battery otherwise.
 *
 * <p>It runs inside the QQ process under QQ's identity, so it needs no permission of its own (QQ
 * already holds {@code WAKE_LOCK}). The button posts a self-addressed broadcast to a runtime
 * receiver we register here; each tap flips the current state and asks the hub to redraw the
 * notification so the label tracks reality. Off by default at process start — the operator opts in.
 */
public final class WakeLockCtl {
    // Self-addressed action; the PendingIntent is explicit (setPackage) and the receiver is
    // NOT_EXPORTED, so no other app can flip our lock.
    private static final String ACTION_TOGGLE = "com.satori.qq.action.WAKELOCK_TOGGLE";
    private static final int REQ_TOGGLE = 0x5A710002;
    private static final String CPU_TAG = "satori-qq:wakelock";

    private final Context ctx;
    private final Runnable onChange;
    private final Object lock = new Object();
    private PowerManager.WakeLock cpu;
    private WifiManager.WifiLock wifi;
    private volatile boolean held;
    private volatile boolean registered;

    public WakeLockCtl(Context ctx, Runnable onChange) {
        this.ctx = ctx;
        this.onChange = onChange;
        register();
    }

    public boolean held() { return held; }

    /** Button label, matching the current state (Termux semantics: it names the action, not the state). */
    public String label() { return held ? "释放唤醒锁" : "获取唤醒锁"; }

    public String diag() { return (registered ? "reg" : "no-recv") + "/" + (held ? "held" : "idle"); }

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent it) {
            if (it == null || !ACTION_TOGGLE.equals(it.getAction())) return;
            toggle();
        }
    };

    private void register() {
        if (ctx == null || registered) return;
        try {
            IntentFilter f = new IntentFilter(ACTION_TOGGLE);
            if (Build.VERSION.SDK_INT >= 33) {
                ctx.registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED);
            } else {
                ctx.registerReceiver(receiver, f);
            }
            registered = true;
        } catch (Throwable t) {
            L.e("wakelock: register receiver", t);
        }
    }

    /** PendingIntent that drives the notification's action button; null when we could not register. */
    public PendingIntent toggleIntent() {
        if (ctx == null || !registered) return null;
        try {
            Intent it = new Intent(ACTION_TOGGLE).setPackage(ctx.getPackageName());
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_IMMUTABLE;
            return PendingIntent.getBroadcast(ctx, REQ_TOGGLE, it, flags);
        } catch (Throwable t) {
            L.e("wakelock: pending intent", t);
            return null;
        }
    }

    public void toggle() {
        if (held) release(); else acquire();
        if (onChange != null) {
            try { onChange.run(); } catch (Throwable t) { L.e("wakelock: onChange", t); }
        }
    }

    private void acquire() {
        synchronized (lock) {
            try {
                if (cpu == null) {
                    PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
                    if (pm != null) {
                        cpu = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, CPU_TAG);
                        cpu.setReferenceCounted(false);
                    }
                }
                if (cpu != null && !cpu.isHeld()) cpu.acquire();
            } catch (Throwable t) {
                L.e("wakelock: acquire cpu", t);
            }
            try {
                if (wifi == null) {
                    WifiManager wm = (WifiManager) ctx.getApplicationContext()
                            .getSystemService(Context.WIFI_SERVICE);
                    if (wm != null) {
                        int mode = Build.VERSION.SDK_INT >= 29
                                ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                                : WifiManager.WIFI_MODE_FULL_HIGH_PERF;
                        wifi = wm.createWifiLock(mode, CPU_TAG);
                        wifi.setReferenceCounted(false);
                    }
                }
                if (wifi != null && !wifi.isHeld()) wifi.acquire();
            } catch (Throwable t) {
                L.e("wakelock: acquire wifi", t);
            }
            held = true;
            L.i("wakelock acquired");
        }
    }

    private void release() {
        synchronized (lock) {
            try { if (cpu != null && cpu.isHeld()) cpu.release(); }
            catch (Throwable t) { L.e("wakelock: release cpu", t); }
            try { if (wifi != null && wifi.isHeld()) wifi.release(); }
            catch (Throwable t) { L.e("wakelock: release wifi", t); }
            held = false;
            L.i("wakelock released");
        }
    }
}
