package com.onebot.qq.qq;

import com.onebot.qq.L;

/**
 * Loads the native /proc/self/maps filter (libmapshide.so) into QQ's process and installs it.
 * Full-stack default ON (config maps_hide). v3 locates libfekit via /proc/self/maps (cross linker
 * namespace) and patches open/openat/fopen/syscall/dl_iterate_phdr GOT slots.
 * v4: memfd load as jit-cache, GOT also covers access/faccessat/readlink,
 * nameless RX renamed to dalvik-jit-code-cache. JNI install() also installs
 * in-process seccomp (BPF_JMP|BPF_JEQ); glue confirmed on PID 18120: main
 * Seccomp_filters=2 / NoNewPrivs=1 vs MSF 1/0. Instant health ≠ anti-kick.
 */
public final class MapsHide {
    public static native int install();   // returns #GOT slots patched
    private static volatile boolean loaded = false;

    /** Poll for QQ's Application context, copy libmapshide into a memfd named like
     *  ART jit so maps do not show com.onebot.qq, then load + install. */
    public static void tryLoad(final Ref ref) {
        Thread t = new Thread(() -> {
            for (int i = 0; i < 60 && !loaded; i++) {
                try {
                    Object app = ref.callS("mqq.app.MobileQQ", "getMobileQQ");
                    if (app != null) {
                        Object pm = ref.call(app, "getPackageManager");
                        Object ai = ref.call(pm, "getApplicationInfo", "com.onebot.qq", 0);
                        String dir = Ref.asStr(ref.get(ai, "nativeLibraryDir"));
                        String so = dir + "/libmapshide.so";
                        loadMemfdOrPath(so);
                        loaded = true;
                        L.i("MapsHide: loaded");
                        int lastPatched = -1;
                        for (int k = 0; k < 20; k++) {
                            int n = install();
                            if (n != lastPatched) {
                                L.i("MapsHide: patched " + n + " detector GOT slots");
                                lastPatched = n;
                            }
                            try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
                        }
                        return;
                    }
                } catch (Throwable ex) { L.e("MapsHide load", ex); return; }
                try { Thread.sleep(300); } catch (InterruptedException e) { return; }
            }
            if (!loaded) L.w("MapsHide: could not load native lib (context/so not found)");
        }, "pool-6-thread-2");
        t.setDaemon(true);
        t.start();
    }

    private static java.io.FileDescriptor keepMemfd;

    private static void loadMemfdOrPath(String so) throws Exception {
        try {
            java.lang.reflect.Method memfd = android.system.Os.class.getMethod(
                    "memfd_create", String.class, int.class);
            keepMemfd = (java.io.FileDescriptor) memfd.invoke(null, "jit-cache", 0);
            java.lang.reflect.Field desc = java.io.FileDescriptor.class.getDeclaredField("descriptor");
            desc.setAccessible(true);
            int n = desc.getInt(keepMemfd);
            java.io.FileInputStream in = new java.io.FileInputStream(so);
            byte[] buf = new byte[16384];
            int r;
            while ((r = in.read(buf)) > 0) android.system.Os.write(keepMemfd, buf, 0, r);
            in.close();
            android.system.Os.lseek(keepMemfd, 0, android.system.OsConstants.SEEK_SET);
            System.load("/proc/self/fd/" + n);
            return;
        } catch (Throwable t) {
            L.w("MapsHide: memfd load failed, using file path: " + t);
        }
        System.load(so);
    }
}
