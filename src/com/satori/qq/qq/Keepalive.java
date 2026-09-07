package com.satori.qq.qq;

import android.app.Notification;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

import com.satori.qq.L;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Cooperative, VPN-style foreground-service keepalive.
 *
 * <p>Instead of an external watchdog that reactively relaunches a killed process (which fights the
 * user — they cannot even close QQ), this promotes a QQ main-process service to a genuine
 * foreground service while the Satori service is "on". A foreground-service process is exempt from
 * the OEM app-freezer / low-memory kill the way a VPN or music app is, so QQ stays alive while
 * connected. When the user force-stops QQ, the service dies and Satori disconnects — no resurrection.
 *
 * <p>Host service: {@code com.qq.background.task.service.QQDataSyncService} — a QQ main-process
 * service that already declares {@code foregroundServiceType=dataSync}, which needs no runtime
 * permission (QQ holds FOREGROUND_SERVICE_DATA_SYNC). We start it with our own action and, in a
 * before-hook, promote it to foreground and short-circuit QQ's own {@code onStartCommand} body so
 * QQ's data-sync logic never runs for our intent. QQ's own starts of the service are left untouched.
 */
public final class Keepalive {
    private static final String SVC = "com.qq.background.task.service.QQDataSyncService";
    private static final String MARKER = "satori.keepalive";
    private static final int START_STICKY = 1;

    private final ClassLoader cl;
    private final Context ctx;
    private final int notifyId;
    private volatile Notification current;
    private volatile boolean hooked;
    private volatile boolean started;
    private volatile boolean batteryAsked;
    private volatile String info = "init";

    public String info() { return info; }

    public Keepalive(ClassLoader cl, Context ctx, int notifyId) {
        this.cl = cl;
        this.ctx = ctx;
        this.notifyId = notifyId;
    }

    public boolean hooked() { return hooked; }
    public boolean started() { return started; }

    /** Latest notification to display as the FGS entry; kept in sync by the hub each tick. */
    public void setNotification(Notification n) { if (n != null) current = n; }

    /** Install the onStartCommand hook once. Our marker intent promotes to FG; QQ's body is skipped. */
    public synchronized void install() {
        if (hooked) return;
        Class<?> svc = XposedHelpers.findClassIfExists(SVC, cl);
        if (svc == null) { info = "no-class"; return; }
        XC_MethodHook hook = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                Intent it = (Intent) p.args[0];
                if (it == null || !MARKER.equals(it.getAction())) return; // QQ's own start
                if (p.thisObject == null || !SVC.equals(p.thisObject.getClass().getName())) return;
                promote(p.thisObject);
                p.setResult(START_STICKY); // skip the service body for our intent
            }
        };
        // The service may not override onStartCommand itself (it can inherit it). Walk up until a
        // class declares it and hook there; that hook still fires for the subclass instance.
        try {
            for (Class<?> c = svc; c != null && c != Object.class; c = c.getSuperclass()) {
                int n = XposedBridge.hookAllMethods(c, "onStartCommand", hook).size();
                if (n > 0) {
                    hooked = true;
                    info = "hooked:" + c.getName() + ":" + n;
                    L.i("keepalive hook on " + c.getName());
                    return;
                }
            }
            info = "no-onStartCommand";
        } catch (Throwable t) {
            info = "err:" + t;
            L.e("keepalive install", t);
        }
    }

    private void promote(Object service) {
        Notification n = current;
        if (service == null || n == null) return;
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                XposedHelpers.callMethod(service, "startForeground", notifyId, n,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                XposedHelpers.callMethod(service, "startForeground", notifyId, n);
            }
            started = true;
        } catch (Throwable t) {
            L.e("keepalive startForeground", t);
        }
    }

    /** Start (or refresh) the foreground service. Safe to call repeatedly; a no-op without a hook/notif. */
    public void enable() {
        if (!hooked || current == null) return;
        try {
            Intent it = new Intent().setClassName(ctx.getPackageName(), SVC).setAction(MARKER);
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(it);
            else ctx.startService(it);
        } catch (Throwable t) {
            // Android 12+ can block a background FGS start until the app is battery-exempt or was
            // foregrounded once. Not fatal: the plain status notification still shows via notify().
            L.e("keepalive enable", t);
        }
    }

    /** Mark not-started after going offline so the next online transition re-promotes. */
    public void markStopped() { started = false; }

    /**
     * One-time cooperative request for Doze battery-optimization exemption (shows the system dialog
     * under QQ's identity). After it is granted, background FGS starts are always allowed. Best-effort
     * and asked at most once per process; skipped when already exempt.
     */
    public void requestBatteryExemptionOnce() {
        if (batteryAsked) return;
        batteryAsked = true;
        try {
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            String pkg = ctx.getPackageName();
            if (pm != null && pm.isIgnoringBatteryOptimizations(pkg)) return; // already exempt
            Intent it = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:" + pkg))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(it);
            L.i("keepalive requested battery exemption");
        } catch (Throwable t) {
            L.e("keepalive battery exemption", t);
        }
    }
}
