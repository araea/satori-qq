package com.satori.qq.ui;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import org.json.JSONObject;

/** Read-only loopback probe with bounded time and response size. */
public final class HealthClient {
    private HealthClient() {}
    public static JSONObject read(int port) throws Exception {
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid port");
        HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/healthz").openConnection();
        connection.setConnectTimeout(1800);
        connection.setReadTimeout(2500);
        connection.setInstanceFollowRedirects(false);
        connection.setUseCaches(false);
        try {
            int code = connection.getResponseCode();
            if (code != 200 && code != 503) throw new IllegalStateException("Unexpected health response");
            InputStream stream = code == 503 ? connection.getErrorStream() : connection.getInputStream();
            if (stream == null) throw new IllegalStateException("Empty health response");
            try (InputStream in = stream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = in.read(buffer)) != -1) {
                    if (out.size() + count > 512 * 1024) throw new IllegalStateException("Response too large");
                    out.write(buffer, 0, count);
                }
                JSONObject json = new JSONObject(out.toString("UTF-8"));
                if (!"satori-qq".equals(json.optString("name")) || !(json.opt("online") instanceof Boolean)
                        || !(json.opt("listening") instanceof Boolean)) throw new IllegalStateException("Not a Satori QQ service");
                return json;
            }
        } finally { connection.disconnect(); }
    }

    public static String report(JSONObject health, String installedVersion, int port, long checkedAt) {
        StringBuilder out = new StringBuilder("知弦 · 诊断报告\n");
        out.append("应用版本：").append(installedVersion).append('\n');
        out.append("本机端口：").append(port).append('\n');
        if (health == null) return out.append("状态：未能连接本机服务\n不包含令牌、QQ 账号或消息内容。\n").toString();
        out.append("检查时间：").append(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.CHINA).format(new java.util.Date(checkedAt))).append('\n');
        for (String key : new String[]{"version", "qq_version", "online", "listening", "connections", "config_revision", "config_status"}) {
            out.append(key).append(": ").append(health.opt(key)).append('\n');
        }
        JSONObject compat = health.optJSONObject("compat");
        if (compat != null) out.append("接口检查：").append(compat.optInt("passed")).append('/').append(compat.optInt("total")).append('\n');
        JSONObject sso = health.optJSONObject("sso");
        if (sso != null) out.append("请求失败：").append(sso.optInt("failures")).append("；会话错误：").append(sso.optInt("session_errors")).append('\n');
        return out.append("不包含令牌、QQ 账号或消息内容。\n").toString();
    }
}
