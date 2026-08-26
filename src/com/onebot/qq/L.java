package com.onebot.qq;

import android.util.Log;
import de.robv.android.xposed.XposedBridge;

/** Logging: goes to both logcat (tag OneBotQQ) and the Xposed module log. */
public final class L {
    public static final String TAG = "OneBotQQ";
    private L() {}
    public static void i(String m) { Log.i(TAG, m); XposedBridge.log(TAG + ": " + m); }
    public static void w(String m) { Log.w(TAG, m); XposedBridge.log(TAG + " W: " + m); }
    public static void e(String m, Throwable t) {
        Log.e(TAG, m, t);
        XposedBridge.log(TAG + " E: " + m);
        if (t != null) XposedBridge.log(t);
    }
    public static void d(String m) { Log.d(TAG, m); }
}
