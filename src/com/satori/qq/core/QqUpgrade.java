package com.satori.qq.core;

import com.satori.qq.L;
import com.satori.qq.Paths;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 记住上一次跑在哪个 QQ 版本上。QQ 升级后第一次自检就能说出「从哪版升到哪版、断了哪几处」，
 * 不必等哪个动作超时了才发现。
 *
 * <p>记录在模块数据目录的 {@code qq-version.json}：{@code version}（当前）、{@code previous}、
 * {@code changed_at}，以及升级后第一次自检的结论。healthz 在升级后 7 天内带出 {@code qq_upgrade}。
 */
final class QqUpgrade {
    private static final String FILE = "qq-version.json";
    private static final long SHOW_MS = 7L * 24 * 3600 * 1000;

    private final File store;
    private volatile JSONObject state;

    QqUpgrade() {
        this(Paths.of(FILE));
    }

    QqUpgrade(File store) {
        this.store = store;
        this.state = load();
    }

    /** 每次算完自检调一次；版本没变什么也不做（只在首次见到某版本时写盘）。 */
    synchronized void observe(String version, JSONObject compat) {
        if (version == null || version.isEmpty() || compat == null || compat.has("error")) return;
        String known = state.optString("version", "");
        if (version.equals(known)) return;
        try {
            JSONArray missing = compat.optJSONArray("missing");
            JSONObject next = new JSONObject()
                    .put("version", version)
                    .put("previous", known)
                    .put("changed_at", System.currentTimeMillis())
                    .put("compat_ok", compat.optBoolean("ok"))
                    .put("compat_passed", compat.optInt("passed"))
                    .put("compat_total", compat.optInt("total"))
                    .put("missing", missing == null ? new JSONArray() : missing);
            state = next;
            save(next);
            if (!known.isEmpty()) {
                L.i("QQ 版本 " + known + " → " + version + "，内核接口面 " + compat.optInt("passed") + "/"
                        + compat.optInt("total") + (compat.optBoolean("ok") ? "，全部对得上" : "，缺 " + missing));
            }
        } catch (Throwable t) {
            L.e("qq-version record", t);
        }
    }

    /** 升级后 7 天内给 healthz 的一段；首次安装（没有 previous）与过了期都返回 null。 */
    JSONObject recent() {
        JSONObject s = state;
        if (s.optString("previous", "").isEmpty()) return null;
        if (System.currentTimeMillis() - s.optLong("changed_at", 0) > SHOW_MS) return null;
        try {
            JSONArray missing = s.optJSONArray("missing");
            return new JSONObject().put("from", s.optString("previous")).put("to", s.optString("version"))
                    .put("at", s.optLong("changed_at")).put("compat_ok", s.optBoolean("compat_ok"))
                    .put("missing", missing == null ? 0 : missing.length());
        } catch (Exception e) {
            return null;
        }
    }

    private JSONObject load() {
        try {
            if (store != null && store.isFile()) {
                return new JSONObject(new String(Files.readAllBytes(store.toPath()), StandardCharsets.UTF_8));
            }
        } catch (Throwable t) {
            L.e("qq-version load", t);
        }
        return new JSONObject();
    }

    private void save(JSONObject s) throws Exception {
        if (store == null) return;
        File parent = store.getParentFile();
        if (parent != null) parent.mkdirs();
        File tmp = new File(store.getPath() + ".tmp");
        Files.write(tmp.toPath(), s.toString().getBytes(StandardCharsets.UTF_8));
        if (!tmp.renameTo(store)) throw new IllegalStateException("rename failed");
    }
}
