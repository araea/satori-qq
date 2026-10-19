package com.satori.qq.update;

import java.util.Locale;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * 一次发布：发布页附带的 {@code update.json}。
 *
 * <p>同一份文件有两个读者。Magisk / KernelSU / APatch 的模块页认顶层的 {@code version}、
 * {@code versionCode}、{@code zipUrl}、{@code changelog}（module.prop 里的 {@code updateJson} 指向它），
 * 管理应用另外认 {@code tag}、{@code notes}、{@code page}、模块包的 {@code sha256}/{@code size}，
 * 以及自己那一份 {@code app}。两边互不认识对方的字段，所以放在同一层不冲突。
 *
 * <p>下载地址只收 https（回环地址除外，给本机验收用）；校验值必须是 64 位十六进制。
 * 清单有一处写错就整份拒收：宁可检查失败，也不要拿着半份信息去替用户装东西。
 */
public final class Release {
    /** 发布里的一个安装包：应用 APK 或模块 zip。 */
    public static final class Part {
        public final String version;
        public final long code;
        public final String url;
        public final String sha256;
        public final long size;

        Part(String version, long code, String url, String sha256, long size) {
            this.version = version;
            this.code = code;
            this.url = url;
            this.sha256 = sha256;
            this.size = size;
        }
    }

    private static final long MAX_SIZE = 64L << 20;

    public final String tag;
    public final String notes;
    public final String page;
    /** 可空：这次发布没有附带应用。 */
    public final Part app;
    /** 可空：这次发布没有附带模块。 */
    public final Part module;

    private Release(String tag, String notes, String page, Part app, Part module) {
        this.tag = tag;
        this.notes = notes;
        this.page = page;
        this.app = app;
        this.module = module;
    }

    public static Release parse(String text) throws JSONException {
        JSONObject json = new JSONObject(text);
        String tag = json.getString("tag").trim();
        if (tag.isEmpty()) throw new JSONException("tag 为空");
        String page = json.optString("page", "");
        if (!page.isEmpty() && !allowed(page)) throw new JSONException("page 不是 https 链接");
        Part module = null;
        if (json.has("zipUrl")) {
            module = part(json.getString("version"), json.getLong("versionCode"), json.getString("zipUrl"),
                    json.getString("sha256"), json.getLong("size"));
        }
        Part app = null;
        JSONObject nested = json.optJSONObject("app");
        if (nested != null) {
            app = part(nested.getString("version"), nested.getLong("versionCode"), nested.getString("url"),
                    nested.getString("sha256"), nested.getLong("size"));
        }
        return new Release(tag, json.optString("notes", "").trim(), page, app, module);
    }

    private static Part part(String version, long code, String url, String sha256, long size) throws JSONException {
        if (version.trim().isEmpty() || code <= 0) throw new JSONException("版本信息不完整");
        if (!allowed(url)) throw new JSONException("下载地址不是 https 链接");
        String hash = sha256.trim().toLowerCase(Locale.ROOT);
        if (!hash.matches("[0-9a-f]{64}")) throw new JSONException("sha256 格式不对");
        if (size <= 0 || size > MAX_SIZE) throw new JSONException("size 不合理");
        return new Part(version.trim(), code, url.trim(), hash, size);
    }

    private static boolean allowed(String url) {
        String lower = url.trim().toLowerCase(Locale.ROOT);
        return lower.startsWith("https://") || lower.startsWith("http://127.0.0.1:") || lower.startsWith("http://localhost:");
    }

    /** 展示用的版本号：模块与应用同号时只写一个。 */
    public String headline() {
        if (module != null) return module.version;
        return app == null ? tag : app.version;
    }
}
