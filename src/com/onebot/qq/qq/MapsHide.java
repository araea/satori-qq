package com.onebot.qq.qq;

import com.onebot.qq.L;

/**
 * Loads the native /proc/self/maps filter (libmapshide.so) into QQ's process and installs it.
 * DEFAULT OFF (config maps_hide). Experimental best-effort anti-detection — see docs/ANTIDETECT.md.
 * The native GOT hook only catches libc open/openat; a detector using raw syscalls bypasses it.
 */
public final class MapsHide {
    public static native int install();   // returns #GOT slots patched
    private static volatile boolean loaded = false;

    /** Poll for QQ's Application context, resolve our own extracted .so, load + install. Early. */
    public static void tryLoad(final Ref ref) {
        Thread t = new Thread(() -> {
            for (int i = 0; i < 60 && !loaded; i++) {
                try {
                    Object app = ref.callS("mqq.app.MobileQQ", "getMobileQQ");   // a Context
                    if (app != null) {
                        Object pm = ref.call(app, "getPackageManager");
                        Object ai = ref.call(pm, "getApplicationInfo", "com.onebot.qq", 0);
                        String dir = Ref.asStr(ref.get(ai, "nativeLibraryDir"));
                        String so = dir + "/libmapshide.so";
                        System.load(so);
                        int n = install();
                        loaded = true;
                        L.i("MapsHide: loaded " + so + ", patched " + n + " GOT slots");
                        return;
                    }
                } catch (Throwable ignore) {}
                try { Thread.sleep(300); } catch (InterruptedException e) { return; }
            }
            if (!loaded) L.w("MapsHide: could not load native lib (context/so not found)");
        }, "onebot-mapshide");
        t.setDaemon(true);
        t.start();
    }
}
