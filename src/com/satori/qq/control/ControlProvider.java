package com.satori.qq.control;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;
import org.json.JSONObject;

/** Narrow Binder channel. Only this UID and the installed QQ UID may read bootstrap secrets. */
public final class ControlProvider extends ContentProvider {
    public static final Uri URI = Uri.parse("content://com.satori.qq.control");
    @Override public boolean onCreate() { return true; }
    private void authorize() {
        int uid = Binder.getCallingUid();
        if (uid == Process.myUid()) return;
        try {
            int qqUid = getContext().getPackageManager().getApplicationInfo("com.tencent.mobileqq", 0).uid;
            if (uid == qqUid) return;
        } catch (android.content.pm.PackageManager.NameNotFoundException ignored) {}
        throw new SecurityException("Caller is not QQ or the module");
    }
    @Override public Bundle call(String method, String arg, Bundle extras) {
        authorize();
        ControlStore store = new ControlStore(getContext());
        Bundle result = new Bundle();
        if ("bootstrap".equals(method)) {
            JSONObject values = store.snapshot();
            JSONObject overrides = values.optJSONObject("overrides");
            result.putString("config", overrides == null ? "" : overrides.toString());
            result.putLong("revision", values.optLong("revision", 0));
            return result;
        }
        if ("runtime".equals(method)) {
            String value = extras == null ? "" : extras.getString("value", "");
            if (value.length() > 8192) throw new IllegalArgumentException("Runtime too large");
            try {
                JSONObject raw = new JSONObject(value);
                // Publish only known fields, never arbitrary files/commands or hook data.
                JSONObject clean = new JSONObject().put("config", raw.getJSONObject("config"))
                        .put("host", raw.optString("host", "127.0.0.1"))
                        .put("revision", raw.getLong("revision"))
                        .put("version", raw.getString("version"))
                        .put("started", raw.getLong("started"));
                store.publish(clean);
            } catch (Exception error) { throw new IllegalArgumentException("Invalid runtime snapshot"); }
            return result;
        }
        throw new IllegalArgumentException("Unknown method");
    }
    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { authorize(); throw new UnsupportedOperationException(); }
    @Override public String getType(Uri u) { authorize(); return null; }
    @Override public Uri insert(Uri u, ContentValues v) { authorize(); throw new UnsupportedOperationException(); }
    @Override public int delete(Uri u, String s, String[] a) { authorize(); throw new UnsupportedOperationException(); }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { authorize(); throw new UnsupportedOperationException(); }
}
