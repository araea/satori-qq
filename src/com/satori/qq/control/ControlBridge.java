package com.satori.qq.control;

import android.content.Context;
import android.os.Bundle;
import com.satori.qq.Cfg;
import com.satori.qq.L;
import org.json.JSONObject;

/** Runs once on the server worker, before binding the port; never blocks QQ's main thread. */
public final class ControlBridge {
    private ControlBridge() {}

    private static volatile String status = "pending";
    private static volatile long revision = -1;
    private static volatile boolean retrying;

    public static String status() { return status; }
    public static long revision() { return revision; }

    /**
     * 读一次管理页设置并回报运行状态。
     *
     * <p>开机早期去问 QQ 的 ContentResolver 会拿到 {@code Unknown URI}：QQ 是开机自启的，那时
     * 用户往往还没解锁，模块的 provider（不是 direct-boot aware）在凭据加密存储里还没挂上
     * （2026-09-19 首启实测）。所以失败不当作终局，改成后台继续重试，界面上的设置晚一点生效，
     * 而不是永远停在 provider-unavailable。
     */
    public static long bootstrap(Context context, Cfg config, String version) {
        Bundle result = tryRead(context, 4, 400);
        if (result == null) {
            startBackgroundRetry(context, config, version);
            return -1;
        }
        return apply(context, config, version, result);
    }

    /** 本模块包名。 */
    private static final String SELF_PACKAGE = "com.satori.qq";

    private static Bundle tryRead(Context context, int attempts, long gapMs) {
        Bundle result = null;
        String failure = "no-provider";
        for (int attempt = 0; attempt < attempts && result == null; attempt++) {
            try {
                result = context.getContentResolver().call(ControlProvider.URI, "bootstrap", null, null);
            } catch (Throwable error) {
                failure = describe(error);
            }
            if (result == null && attempt < attempts - 1) {
                try { Thread.sleep(gapMs); } catch (InterruptedException stop) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }
        if (result == null) status = "provider-unavailable:" + failure + " [" + visibilityProbe(context) + "]";
        return result;
    }

    /**
     * 把异常的类名、消息、cause 一起写进状态。
     *
     * <p>{@code ContentResolver.call} 在拿不到 provider 时抛的就是
     * {@code IllegalArgumentException("Unknown URI ...")}，只记类名看不出是「没权限」「看不见包」
     * 还是「provider 没起来」，所以消息必须留着。
     */
    private static String describe(Throwable t) {
        StringBuilder b = new StringBuilder(t.getClass().getSimpleName());
        String msg = t.getMessage();
        if (msg != null && !msg.isEmpty()) b.append('(').append(cap(msg, 160)).append(')');
        Throwable cause = t.getCause();
        if (cause != null && cause != t) {
            b.append(" <- ").append(cause.getClass().getSimpleName());
            String cm = cause.getMessage();
            if (cm != null && !cm.isEmpty()) b.append('(').append(cap(cm, 120)).append(')');
        }
        return b.toString();
    }

    private static String cap(String s, int max) {
        String one = s.replace('\n', ' ').replace('\r', ' ');
        return one.length() <= max ? one : one.substring(0, max) + "…";
    }

    /** 本模块的包在 QQ 进程里看不看得见——这条直接决定 provider 能不能被解析。 */
    private static String visibilityProbe(Context context) {
        try {
            context.getPackageManager().getApplicationInfo(SELF_PACKAGE, 0);
            return "self-package=visible";
        } catch (Throwable t) {
            return "self-package=" + t.getClass().getSimpleName();
        }
    }

    private static void startBackgroundRetry(Context context, Cfg config, String version) {
        synchronized (ControlBridge.class) {
            if (retrying) return;
            retrying = true;
        }
        Thread t = new Thread(() -> {
            try {
                for (int attempt = 0; attempt < 30; attempt++) {
                    try { Thread.sleep(20_000L); } catch (InterruptedException stop) { return; }
                    Bundle result = tryRead(context, 1, 0);
                    if (result != null) {
                        L.i("Management settings became available after " + (attempt + 1) + " retries");
                        apply(context, config, version, result);
                        return;
                    }
                }
                L.e("Management settings still unavailable after retries; keeping file/defaults", null);
            } finally {
                retrying = false;
            }
        }, "pool-5-thread-1");
        t.setDaemon(true);
        t.start();
    }

    private static long apply(Context context, Cfg config, String version, Bundle result) {
        final long rev;
        try {
            String raw = result.getString("config", "");
            if (!raw.isEmpty()) ManagedConfig.apply(config, new JSONObject(raw));
            rev = result.getLong("revision", 0);
        } catch (Exception invalid) {
            status = "invalid-settings";
            L.e("Management settings invalid; retaining file/default settings", null);
            return -1;
        }
        revision = rev;
        status = "applied";
        try {
            JSONObject runtime = new JSONObject().put("config", ManagedConfig.snapshot(config))
                    .put("host", config.host).put("revision", rev).put("version", version)
                    .put("started", System.currentTimeMillis());
            Bundle extra = new Bundle(); extra.putString("value", runtime.toString());
            context.getContentResolver().call(ControlProvider.URI, "runtime", null, extra);
        } catch (Throwable error) {
            status = "applied:status-sync-unavailable";
            L.e("Management status sync unavailable: " + error.getClass().getSimpleName(), null);
        }
        return rev;
    }
}
