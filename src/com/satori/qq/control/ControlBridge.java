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
    public static String status() { return status; }
    public static long bootstrap(Context context, Cfg config, String version) {
        Bundle result = null;
        String failure = "no-provider";
        // Provider startup/package replacement can transiently fail even after Application.attach.
        for (int attempt = 0; attempt < 4 && result == null; attempt++) {
            try { result = context.getContentResolver().call(ControlProvider.URI, "bootstrap", null, null); }
            catch (Throwable error) { failure = error.getClass().getSimpleName(); }
            if (result == null && attempt < 3) {
                try { Thread.sleep(400); } catch (InterruptedException stop) { Thread.currentThread().interrupt(); return -1; }
            }
        }
        if (result == null) { status = "provider-unavailable:" + failure; L.e("Management settings unavailable: " + failure, null); return -1; }
        final long revision;
        try {
            String raw = result.getString("config", "");
            if (!raw.isEmpty()) ManagedConfig.apply(config, new JSONObject(raw));
            revision = result.getLong("revision", 0);
        } catch (Exception invalid) { status = "invalid-settings"; L.e("Management settings invalid; retaining file/default settings", null); return -1; }
        status = "applied";
        try {
            JSONObject runtime = new JSONObject().put("config", ManagedConfig.snapshot(config))
                    .put("host", config.host).put("revision", revision).put("version", version)
                    .put("started", System.currentTimeMillis());
            Bundle extra = new Bundle(); extra.putString("value", runtime.toString());
            context.getContentResolver().call(ControlProvider.URI, "runtime", null, extra);
        } catch (Throwable error) { status = "applied:status-sync-unavailable"; L.e("Management status sync unavailable: " + error.getClass().getSimpleName(), null); }
        return revision;
    }
}
