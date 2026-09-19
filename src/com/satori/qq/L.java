package com.satori.qq;

import android.util.Log;

/** Quiet-by-default logging. Verbose logs are useful for debugging but are observable. */
public final class L {
    public static final String TAG = "Q.Kernel";
    private static volatile boolean verbose;
    private L() {}
    public static void configure(boolean enableVerbose) { verbose = enableVerbose; }
    /** True when verbose logging is on. Gates diagnostics that cost I/O or leave files behind. */
    public static boolean verbose() { return verbose; }
    public static void i(String m) {
        if (!verbose) return;
        Log.i(TAG, m);
    }
    public static void w(String m) {
        if (!verbose) return;
        Log.w(TAG, m);
    }
    public static void e(String m, Throwable t) {
        Log.e(TAG, m, t);
    }
    public static void d(String m) { if (verbose) Log.d(TAG, m); }
}
