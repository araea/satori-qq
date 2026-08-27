package com.onebot.qq;

import android.util.Log;
import de.robv.android.xposed.XposedBridge;

/** Quiet-by-default logging. Verbose/Xposed logs are useful for debugging but are observable. */
public final class L {
    public static final String TAG = "Q.Kernel";
    private static volatile boolean verbose;
    private L() {}
    public static void configure(boolean enableVerbose) { verbose = enableVerbose; }
    public static void i(String m) {
        if (!verbose) return;
        Log.i(TAG, m);
        XposedBridge.log(TAG + ": " + m);
    }
    public static void w(String m) {
        if (!verbose) return;
        Log.w(TAG, m);
        XposedBridge.log(TAG + " W: " + m);
    }
    public static void e(String m, Throwable t) {
        Log.e(TAG, m, t);
        if (verbose) {
            XposedBridge.log(TAG + " E: " + m);
            if (t != null) XposedBridge.log(t);
        }
    }
    public static void d(String m) { if (verbose) Log.d(TAG, m); }
}
