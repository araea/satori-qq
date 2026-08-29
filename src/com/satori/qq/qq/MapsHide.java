package com.satori.qq.qq;

import com.satori.qq.L;

/**
 * Loads the native /proc/self/maps filter (libmapshide.so) into QQ's process and installs it.
 * Full-stack default ON (config maps_hide). v3 locates libfekit via /proc/self/maps (cross linker
 * namespace) and patches open/openat/fopen/syscall/dl_iterate_phdr GOT slots.
 * v4: memfd load as jit-cache, GOT also covers access/faccessat/readlink,
 * v5: also GOT-patches libturingxq; resolves openat dirfd; cloaks /proc status
 * Seccomp_filters; covers opendir/popen/stat/dladdr/__system_property_get.
 * Loaded in every QQ process (main + MSF). Re-patches until ckguard/ZRes appears.
 * Instant health ≠ anti-kick.
 * v5.1 (0.5.7): also GOT-patches __system_property_find.
 * v5.2 (0.8.0): also GOT-patches dlsym (detector libs only).
 * v5.3 (0.8.1): also GOT-patches readdir; syscall/seccomp getdents64.
 * v5.4 (0.8.2): /proc/net/tcp{,6} drops :0BB9 (listen and peer); GOT __open_2.
 * v5.6 (0.8.4): raw-svc mprotect for GOT; environ scrub after patch.
 * v5.7 (0.8.5): mprotect raw then libc fallback (main fekit RELRO).
 * v5.8 (0.8.6): hide-loop self-audit (leak_* + loop_ok).
 * v5.9 (0.8.7): loop_ok ignores unverified tcp/env (-1); audit before seccomp.
 * v5.10 (0.8.8): locate so beside the module APK first; retry transient PM misses.
 * v5.11 (0.8.9): ZWSP path match + detector usb.config rewrite.
 */
public final class MapsHide {
    public static native int install();   // returns #GOT slots patched
    private static volatile boolean loaded = false;

    /** Poll for the module so, copy libmapshide into a memfd named like
     *  ART jit so maps do not show com.satori.qq, then load + install. */
    public static void tryLoad(final Ref ref) {
        Thread t = new Thread(() -> {
            for (int i = 0; i < 60 && !loaded; i++) {
                try {
                    String so = findLibMapshide(ref);
                    if (so != null) {
                        loadMemfdOrPath(so);
                        loaded = true;
                        L.i("MapsHide: loaded");
                        int lastPatched = -1;
                        for (int k = 0; ; k++) {
                            int n = install();
                            if (n != lastPatched) {
                                L.i("MapsHide: patched " + n + " detector GOT slots");
                                lastPatched = n;
                            }
                            long waitMs = k < 30 ? 1000L : (k < 90 ? 15000L : 60000L);
                            try { Thread.sleep(waitMs); } catch (InterruptedException e) { return; }
                        }
                    }
                } catch (Throwable ex) {
                    L.e("MapsHide load", ex);
                }
                try { Thread.sleep(300); } catch (InterruptedException e) { return; }
            }
            if (!loaded) L.w("MapsHide: could not load native lib (context/so not found)");
        }, "pool-6-thread-2");
        t.setDaemon(true);
        t.start();
    }

    /** Prefer the module APK's own lib dir so we do not depend on a hooked PM. */
    private static String findLibMapshide(Ref ref) {
        String beside = soBesideModuleApk();
        if (beside != null) return beside;
        try {
            Object app = ref.callS("mqq.app.MobileQQ", "getMobileQQ");
            if (app == null) return null;
            Object pm = ref.call(app, "getPackageManager");
            Object ai = ref.call(pm, "getApplicationInfo", "com.satori.qq", 0);
            String dir = Ref.asStr(ref.get(ai, "nativeLibraryDir"));
            if (dir.isEmpty()) return null;
            String so = dir + "/libmapshide.so";
            return readableFile(so) ? so : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String soBesideModuleApk() {
        try {
            java.security.ProtectionDomain pd = MapsHide.class.getProtectionDomain();
            if (pd == null || pd.getCodeSource() == null || pd.getCodeSource().getLocation() == null)
                return null;
            String loc = pd.getCodeSource().getLocation().getPath();
            if (loc == null || loc.isEmpty()) return null;
            int bang = loc.indexOf('!');
            if (bang >= 0) loc = loc.substring(0, bang);
            java.io.File apk = new java.io.File(loc);
            boolean archive = loc.endsWith(".apk") || loc.endsWith(".zip") || loc.endsWith(".jar");
            java.io.File dir = archive ? apk.getParentFile() : apk;
            if (dir == null) return null;
            String[] rel = { "lib/arm64/libmapshide.so", "lib/arm64-v8a/libmapshide.so" };
            for (String r : rel) {
                String so = new java.io.File(dir, r).getPath();
                if (readableFile(so)) return so;
            }
        } catch (Throwable ignore) {}
        return null;
    }

    /** File.exists/isFile are hooked and deny *mapshide* paths; open instead. */
    private static boolean readableFile(String path) {
        try {
            java.io.FileInputStream in = new java.io.FileInputStream(path);
            in.close();
            return true;
        } catch (Throwable t) {
            return false;
        }
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
