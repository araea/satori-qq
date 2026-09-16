package com.satori.qq.control;

import android.content.Context;
import android.util.AtomicFile;
import org.json.JSONObject;
import com.satori.qq.Cfg;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

/** Explicit private file avoids frameworks redirecting module SharedPreferences between variants. */
public final class ControlStore {
    private static final Object LOCK = new Object();
    private final AtomicFile file;
    public ControlStore(Context context) { file = new AtomicFile(new File(context.getNoBackupFilesDir(), "zhixian-control.json")); }
    public JSONObject snapshot() {
        synchronized (LOCK) {
            try (FileInputStream in = file.openRead(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[2048]; int size;
                while ((size = in.read(buffer)) != -1) {
                    if (out.size() + size > 65536) throw new IllegalStateException("配置文件过大");
                    out.write(buffer, 0, size);
                }
                return new JSONObject(out.toString("UTF-8"));
            } catch (java.io.FileNotFoundException missing) { return new JSONObject(); }
            catch (Exception error) { throw new IllegalStateException("配置读取失败，请检查应用存储", error); }
        }
    }
    private void write(JSONObject value) {
        FileOutputStream out = null;
        try { out = file.startWrite(); out.write(value.toString().getBytes("UTF-8")); file.finishWrite(out); }
        catch (Exception error) { if (out != null) file.failWrite(out); throw new IllegalStateException("保存失败，请重试", error); }
    }
    public String overrides() { JSONObject value = snapshot().optJSONObject("overrides"); return value == null ? "" : value.toString(); }
    public long revision() { return snapshot().optLong("revision", 0); }
    public JSONObject runtime() { JSONObject value = snapshot().optJSONObject("runtime"); return value == null ? new JSONObject() : value; }
    public JSONObject editable() {
        try {
            JSONObject all = snapshot(); JSONObject overrides = all.optJSONObject("overrides");
            if (overrides != null) return overrides;
            JSONObject runtime = all.optJSONObject("runtime");
            JSONObject config = runtime == null ? null : runtime.optJSONObject("config");
            return config == null ? ManagedConfig.snapshot(new Cfg()) : config;
        } catch (Exception error) { throw new IllegalStateException("配置读取失败", error); }
    }
    public void save(JSONObject value) throws Exception {
        JSONObject clean = ManagedConfig.validate(value);
        synchronized (LOCK) { JSONObject all = snapshot(); all.put("overrides", clean).put("revision", all.optLong("revision", 0) + 1); write(all); }
    }
    public void useFile() {
        synchronized (LOCK) {
            try { JSONObject all = snapshot(); all.remove("overrides"); all.put("revision", all.optLong("revision", 0) + 1); write(all); }
            catch (Exception error) { throw new IllegalStateException("保存失败，请重试", error); }
        }
    }
    public void publish(JSONObject runtime) {
        synchronized (LOCK) {
            try { JSONObject all = snapshot(); all.put("runtime", runtime); write(all); }
            catch (Exception error) { throw new IllegalStateException("状态同步失败", error); }
        }
    }
}
