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
 * Termux-style, user-toggled wake lock exposed as a notification action button, plus a
 * ref-counted hold the module takes on its own around outbound work.
 *
 * <p>The foreground-service keepalive ({@link Keepalive}) keeps QQ's <em>process</em> resident, but
 * a process kept in the foreground-service priority band can still have its CPU parked once the
 * device enters Doze — enough to stall the Satori websocket for minutes. Mirroring Termux's
 * "ACQUIRE WAKELOCK / RELEASE WAKELOCK" toggle, this holds a {@code PARTIAL_WAKE_LOCK} (plus a
 * best-effort high-performance Wi-Fi lock) so the CPU and radio stay awake while the operator wants
 * maximum residency, and releases them to save battery otherwise.
 *
 * <p>The toggle is off by default, so a scheduled push landing on a locked screen used to run with
 * the CPU and Wi-Fi radio parked: QQ's kernel uploads image/voice/video payloads inline while
 * {@code sendMsg} runs, and a parked radio makes that transfer time out ("rich media transfer
 * failed") while plain text still rides the already-established MSF socket. {@link #begin()} /
 * {@link #end()} therefore take the same locks for the duration of an outbound mutation regardless
 * of the toggle; the holds nest, and the lock is released again as soon as the last one ends unless
 * the operator asked for a permanent hold. {@link #sustainWifi(boolean)} keeps just the Wi-Fi lock
 * for as long as the service is usable, because inbound events queue behind the same power save.
 *
 * <p>The Wi-Fi lock asks for {@code WIFI_MODE_FULL_HIGH_PERF} on every API level, deprecation
 * notwithstanding: it is the only mode {@code WifiLockManager} ever maps to "power save off" with
 * the screen off, whereas {@code WIFI_MODE_FULL_LOW_LATENCY} is activated only while the screen is
 * on and the holder is foreground. Be clear about what this buys, though — Android 14+ remaps a
 * high-perf request to low latency (config_wifiHighPerfLockDeprecated), so on a current ROM the
 * lock alone does <em>not</em> keep the radio out of screen-off power save; measured on this
 * device, screen-off RTT to the AP goes 4ms → 27ms avg / 48ms peak either way. What the held lock
 * does give is a lock for {@code cmd wifi force-hi-perf-mode enabled} to act on — with no lock
 * acquired at all, {@code getStrongestLockMode()} short-circuits to NO_LOCKS_HELD and the forced
 * mode never applies. Defeating screen-off power save outright is a device-side decision, not
 * something an app on Android 14+ can take on its own.
 *
 * <p>It runs inside the QQ process under QQ's identity, so it needs no permission of its own (QQ
 * already holds {@code WAKE_LOCK}). The button posts a self-addressed broadcast to a runtime
 * receiver we register here; each tap flips the current state and asks the hub to redraw the
 * notification so the label tracks reality.
 */
public final class WakeLockCtl {
    // Self-addressed action; the PendingIntent is explicit (setPackage) and the receiver is
    // NOT_EXPORTED, so no other app can flip our lock.
    private static final String ACTION_TOGGLE = "com.satori.qq.action.WAKELOCK_TOGGLE";
    private static final int REQ_TOGGLE = 0x5A710002;
    private static final String CPU_TAG = "satori-qq:wakelock";
    /** Safety net: an automatic hold can never outlive a wedged send by more than this. */
    private static final long AUTO_TIMEOUT_MS = 180_000L;

    private final Context ctx;
    private final Runnable onChange;
    private final Object lock = new Object();
    private PowerManager.WakeLock cpu;
    private WifiManager.WifiLock wifi;
    /** Operator intent from the notification button; this is what the button label tracks. */
    private volatile boolean userWants;
    /** Nesting depth of module-driven holds around outbound work. */
    private int autoDepth;
    /** Keep the Wi-Fi radio out of screen-off power save for as long as the service is usable. */
    private boolean sustainWifi;
    /** Whether the CPU lock we currently hold was taken without a timeout. */
    private boolean untimed;
    private volatile boolean registered;

    public WakeLockCtl(Context ctx, Runnable onChange) {
        this(ctx, false, onChange);
    }

    /**
     * @param autoAcquire take the operator-level hold immediately, so the module boots with the
     *                    CPU already locked instead of waiting for the first outbound mutation.
     */
    public WakeLockCtl(Context ctx, boolean autoAcquire, Runnable onChange) {
        this.ctx = ctx;
        this.onChange = onChange;
        register();
        if (autoAcquire) {
            synchronized (lock) {
                userWants = true;
                apply();
            }
        }
    }

    /** Operator intent, not the OS-level state: the button names the action to take next. */
    public boolean held() { return userWants; }

    /** Button label, matching the current state (Termux semantics: it names the action, not the state). */
    public String label() { return userWants ? "释放唤醒锁" : "获取唤醒锁"; }

    /** Reports what the OS actually holds, not what we asked for — a silently dropped lock shows. */
    public String diag() {
        StringBuilder sb = new StringBuilder(registered ? "reg" : "no-recv");
        sb.append('/').append(userWants ? "user=on" : "user=off");
        synchronized (lock) {
            if (autoDepth > 0) sb.append("/auto=").append(autoDepth);
            sb.append('/').append(isCpuHeld() ? "cpu=held" : "cpu=idle");
            if (wifi != null) sb.append('/').append(wifiHeld() ? "wifi=held" : "wifi=idle");
        }
        return sb.toString();
    }

    /**
     * Hold the locks for the duration of one outbound operation. Always paired with {@link #end()}
     * in a finally block; nested calls share one underlying lock.
     */
    public void begin() {
        synchronized (lock) {
            autoDepth++;
            apply();
        }
    }

    public void end() {
        synchronized (lock) {
            if (autoDepth > 0) autoDepth--;
            apply();
        }
    }

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
        synchronized (lock) {
            userWants = !userWants;
            apply();
        }
        if (onChange != null) {
            try { onChange.run(); } catch (Throwable t) { L.e("wakelock: onChange", t); }
        }
    }

    /**
     * Ask the module to keep the Wi-Fi radio at full power for as long as the Satori service is
     * usable, independent of the CPU lock. Idempotent; safe to call from the status tick.
     */
    public void sustainWifi(boolean on) {
        synchronized (lock) {
            if (sustainWifi == on) return;
            sustainWifi = on;
            apply();
        }
    }

    /** Bring the OS locks in line with the three reasons we might want them. Caller holds {@link #lock}. */
    private void apply() {
        boolean wantCpu = userWants || autoDepth > 0;
        boolean before = isCpuHeld() || wifiHeld();
        if (wantCpu) acquireCpu(); else releaseCpu();
        // The radio outlives a single send: inbound events queue up behind screen-off power save
        // just as badly as an upload fails behind it.
        if (wantCpu || sustainWifi) acquireWifi(); else releaseWifi();
        boolean after = isCpuHeld() || wifiHeld();
        if (after != before) L.i(after ? "wakelock acquired " + diagUnlocked() : "wakelock released");
    }

    private void acquireCpu() {
        try {
            if (cpu == null) {
                PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
                if (pm == null) return;
                cpu = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, CPU_TAG);
                cpu.setReferenceCounted(false);
            }
            // A permanent hold is untimed; an automatic one expires on its own if a send wedges.
            // Promoting a timed lock has to release first: PowerManager cancels the pending
            // timeout releaser only on release(), so re-acquiring over it would still expire.
            if (userWants) {
                if (cpu.isHeld() && !untimed) cpu.release();
                if (!cpu.isHeld()) cpu.acquire();
                untimed = true;
            } else {
                cpu.acquire(AUTO_TIMEOUT_MS); // re-arms the deadline on every nested hold
                untimed = false;
            }
        } catch (Throwable t) {
            L.e("wakelock: acquire cpu", t);
        }
    }

    private void acquireWifi() {
        try {
            if (wifi == null) {
                WifiManager wm = (WifiManager) ctx.getApplicationContext()
                        .getSystemService(Context.WIFI_SERVICE);
                if (wm == null) return;
                // HIGH_PERF, not LOW_LATENCY: a low-latency lock is only activated while the
                // screen is ON and the holder is foreground, which is never when it matters here.
                // Android 14+ remaps this request to low latency anyway (see the class javadoc);
                // asking for high perf still costs nothing and is what older ROMs honour.
                wifi = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, CPU_TAG);
                wifi.setReferenceCounted(false);
            }
            if (!wifi.isHeld()) wifi.acquire();
        } catch (Throwable t) {
            L.e("wakelock: acquire wifi", t);
        }
    }

    private void releaseCpu() {
        try { if (cpu != null && cpu.isHeld()) cpu.release(); untimed = false; }
        catch (Throwable t) { L.e("wakelock: release cpu", t); }
    }

    private void releaseWifi() {
        try { if (wifi != null && wifi.isHeld()) wifi.release(); }
        catch (Throwable t) { L.e("wakelock: release wifi", t); }
    }

    private boolean isCpuHeld() {
        try { return cpu != null && cpu.isHeld(); } catch (Throwable t) { return false; }
    }

    private boolean wifiHeld() {
        try { return wifi != null && wifi.isHeld(); } catch (Throwable t) { return false; }
    }

    /** {@link #diag()} without re-entering the monitor; only called with {@link #lock} held. */
    private String diagUnlocked() {
        return (isCpuHeld() ? "cpu=held" : "cpu=idle") + "/" + (wifiHeld() ? "wifi=held" : "wifi=idle");
    }
}
