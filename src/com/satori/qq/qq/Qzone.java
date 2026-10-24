package com.satori.qq.qq;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONObject;

/**
 * QZone（QQ 空间）的动态发布走空间自己的 H5 CGI，不从 QQNT 内核走——内核没有
 * 空间服务，客户端自己发动态也走这条 {@code proxy/domain/taotao.qq.com} 的代理。
 *
 * <p>凭据只有两样：空间域的 {@code p_skey}（内核 {@code IKernelTipOffService.getPskey}
 * 拉取）与由它算出的 {@code g_tk}。发布接口 {@code emotion_cgi_publish_v6} 是网页版
 * 空间一直在用的老 CGI，客户端 dex 里也还有同族的 {@code emotion_cgi_msgdetail_v6}，
 * 说明代理与凭据体系仍然被服务端接受。</p>
 */
public final class Qzone {
    private Qzone() {}

    private static final String PUBLISH_URL =
            "https://user.qzone.qq.com/proxy/domain/taotao.qq.com/cgi-bin/emotion_cgi_publish_v6";
    private static final int CONNECT_MS = 10_000;
    private static final int READ_MS = 20_000;

    /** 与网页端一致的 g_tk 算法：对 p_skey 做 5381 基数的滚动散列。 */
    public static long gtk(String skey) {
        if (skey == null) return 0;
        long hash = 5381;
        for (int i = 0; i < skey.length(); i++) {
            hash += (hash << 5) + skey.charAt(i);
        }
        return hash & 0x7fffffffL;
    }

    /**
     * 发一条说说（文本动态）。返回 {@code ok} 与空间 CGI 的 {@code code}/{@code subcode}；
     * 失败时附上原始回包片段，方便对着调。
     */
    public static JSONObject publish(String uin, String pskey, JSONObject p) throws Exception {
        String text = p.optString("text", p.optString("con", p.optString("content", ""))).trim();
        if (text.isEmpty()) throw new IllegalArgumentException("missing text");
        if (pskey == null || pskey.isEmpty()) throw new IllegalStateException("no p_skey for qzone.qq.com");
        long gtk = gtk(pskey);
        String referer = "https://user.qzone.qq.com/" + uin + "/main";

        Map<String, String> form = new LinkedHashMap<>();
        form.put("syn_tweet_verson", "1");
        form.put("paramstr", "1");
        form.put("pic_template", "");
        form.put("who", "1");
        form.put("con", text);
        form.put("feedflag", "1");
        form.put("autoSig", p.optBoolean("auto_sig", false) ? "1" : "0");
        form.put("sub", "");
        form.put("ico", "");
        form.put("sid", "");
        form.put("fonts", "");
        form.put("default", "0");
        form.put("Sensitive_flag", "0");
        form.put("Anonym_flag", "0");
        form.put("res_uin", "");
        form.put("to_sign", p.optBoolean("to_sign", true) ? "1" : "0");
        form.put("qzreferrer", referer);
        String token = p.optString("qzonetoken", "");
        if (!token.isEmpty()) form.put("qzonetoken", token);

        StringBuilder body = new StringBuilder();
        for (Map.Entry<String, String> entry : form.entrySet()) {
            if (body.length() > 0) body.append('&');
            body.append(URLEncoder.encode(entry.getKey(), "UTF-8")).append('=')
                    .append(URLEncoder.encode(entry.getValue(), "UTF-8"));
        }

        HttpURLConnection conn = (HttpURLConnection) new URL(PUBLISH_URL
                + "?g_tk=" + gtk + "&uin=" + uin + "&format=json").openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(CONNECT_MS);
        conn.setReadTimeout(READ_MS);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        conn.setRequestProperty("Referer", referer);
        conn.setRequestProperty("Origin", "https://user.qzone.qq.com");
        conn.setRequestProperty("X-Requested-With", "XMLHttpRequest");
        conn.setRequestProperty("Cookie", "uin=o" + uin + "; p_uin=o" + uin + "; p_skey=" + pskey);
        conn.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) "
                        + "Chrome/130.0.0.0 Mobile Safari/537.36");
        try (var out = conn.getOutputStream()) {
            out.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }
        int status = conn.getResponseCode();
        StringBuilder reply = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                status >= 400 ? conn.getErrorStream() : conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while (reader != null && (line = reader.readLine()) != null && reply.length() < 4000) {
                reply.append(line);
            }
        }
        return parse(status, reply.toString());
    }

    /** 空间回包要么是裸 JSON，要么是 {@code _Callback({...});} 包着的 JSONP。 */
    private static JSONObject parse(int status, String reply) throws Exception {
        JSONObject out = new JSONObject();
        String text = reply == null ? "" : reply.trim();
        int start = text.indexOf('(');
        int end = text.lastIndexOf(')');
        if (text.startsWith("_Callback") && start >= 0 && end > start) {
            text = text.substring(start + 1, end).trim();
        }
        JSONObject data = null;
        try {
            data = new JSONObject(text);
        } catch (Exception ignored) {
            // 回包不是 JSON：把原文带回去
        }
        out.put("http_status", status);
        if (data != null) {
            int code = data.optInt("code", data.optInt("ret", -1));
            out.put("ok", status < 400 && code == 0);
            out.put("code", code);
            out.put("subcode", data.optInt("subcode", 0));
            String msg = data.optString("message", data.optString("msg", ""));
            if (!msg.isEmpty()) out.put("message", msg);
            out.put("raw", data.toString());
        } else {
            out.put("ok", false);
            out.put("code", -1);
            out.put("raw", text);
        }
        return out;
    }
}
