package com.satori.qq.qq;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** QQ 空间说说：发表 / 列表 / 删除（HTTP + qzone.qq.com cookie）。 */
public final class QzoneSvc {
    public static final class Auth {
        public String uin = "";
        public String skey = "";
        public String pskey = "";
        public String gtk = "";
        public String cookie = "";
    }

    public static final class Mood {
        public String tid = "";
        public String content = "";
        public long createdTime;
        public int ugcRight;
    }

    private final QQClient qq;

    public QzoneSvc(QQClient qq) {
        this.qq = qq;
    }

    public Auth auth() throws Exception {
        Auth a = qq.fetchQzoneAuth();
        if (a.pskey == null || a.pskey.isEmpty())
            throw new IllegalStateException("qzone auth: missing p_skey for qzone.qq.com");
        if (a.skey == null) a.skey = "";
        String gtkSrc = !a.skey.isEmpty() ? a.skey : a.pskey;
        a.gtk = gtk(gtkSrc);
        StringBuilder cookie = new StringBuilder();
        cookie.append("p_uin=o").append(a.uin).append("; p_skey=").append(a.pskey).append("; uin=o").append(a.uin);
        if (!a.skey.isEmpty()) cookie.append("; skey=").append(a.skey);
        a.cookie = cookie.toString();
        return a;
    }

    public static String gtk(String skey) {
        long hash = 5381;
        for (int i = 0; i < skey.length(); i++) {
            hash += (hash << 5) + skey.charAt(i);
        }
        return String.valueOf(hash & 0x7fffffffL);
    }

    public JSONObject publish(String content, int ugcRight) throws Exception {
        final String con = content == null ? "" : content;
        final int right = ugcRight;
        Auth a = auth();
        String body = form(new LinkedHashMap<String, String>() {{
            put("syn_tweet_verson", "1");
            put("paramstr", "1");
            put("con", con);
            put("feedversion", "1");
            put("ver", "1");
            put("ugc_right", String.valueOf(right));
            put("to_sign", "0");
            put("hostuin", a.uin);
            put("code_version", "1");
            put("format", "json");
            put("qzreferrer", "https://user.qzone.qq.com/" + a.uin + "/main");
        }});
        String api = "https://user.qzone.qq.com/proxy/domain/taotao.qzone.qq.com/cgi-bin/emotion_cgi_publish_v6?g_tk="
                + a.gtk;
        JSONObject json = postJsonWithRetry(api, body, a.cookie);
        int sub = json.optInt("subcode", json.optInt("code", -1));
        if (sub != 0)
            throw new IllegalStateException("qzone publish failed: subcode=" + sub + " "
                    + json.optString("message", json.optString("msg", "")));
        String tid = firstNonEmpty(json.optString("t1_tid", ""), json.optString("tid", ""));
        if (tid.isEmpty()) throw new IllegalStateException("qzone publish: missing tid");
        return new JSONObject().put("tid", tid);
    }

    public JSONObject delete(String tid) throws Exception {
        if (tid == null || tid.trim().isEmpty()) throw new IllegalArgumentException("missing tid");
        final String tidFinal = tid.trim();
        Auth a = auth();
        String body = form(new LinkedHashMap<String, String>() {{
            put("hostuin", a.uin);
            put("tid", tidFinal);
            put("t1_source", "1");
            put("code_version", "1");
            put("format", "json");
            put("qzreferrer", "https://user.qzone.qq.com/" + a.uin);
        }});
        String api = "https://user.qzone.qq.com/proxy/domain/taotao.qzone.qq.com/cgi-bin/emotion_cgi_delete_v6?g_tk="
                + a.gtk;
        JSONObject json = postJsonWithRetry(api, body, a.cookie);
        int sub = json.optInt("subcode", json.optInt("code", -1));
        if (sub != 0)
            throw new IllegalStateException("qzone delete failed: subcode=" + sub + " "
                    + json.optString("message", json.optString("msg", "")));
        return new JSONObject().put("ok", true).put("tid", tidFinal);
    }

    private static boolean retryableQzoneError(int sub, String msg) {
        if (sub == -2 || sub == -3) return true;
        return msg != null && (msg.contains("稍后再试") || msg.contains("人数过多"));
    }

    private JSONObject getJsonWithRetry(String api, String cookie) throws Exception {
        Exception last = null;
        for (int i = 0; i < 4; i++) {
            try {
                JSONObject json = getJson(api, cookie);
                int sub = json.optInt("subcode", json.optInt("code", -1));
                String msg = json.optString("message", json.optString("msg", ""));
                if (sub != 0 && sub != -1 && retryableQzoneError(sub, msg)) {
                    last = new IllegalStateException("qzone rate limited: subcode=" + sub + " " + msg);
                    Thread.sleep(3000L * (i + 1));
                    continue;
                }
                return json;
            } catch (Exception e) {
                last = e;
                if (i < 3) Thread.sleep(2000L * (i + 1));
            }
        }
        if (last != null) throw last;
        throw new IllegalStateException("qzone request failed");
    }

    private JSONObject postJsonWithRetry(String api, String body, String cookie) throws Exception {
        Exception last = null;
        for (int i = 0; i < 4; i++) {
            try {
                JSONObject json = postJson(api, body, cookie);
                int sub = json.optInt("subcode", json.optInt("code", -1));
                String msg = json.optString("message", json.optString("msg", ""));
                if (sub != 0 && retryableQzoneError(sub, msg)) {
                    last = new IllegalStateException("qzone rate limited: subcode=" + sub + " " + msg);
                    Thread.sleep(3000L * (i + 1));
                    continue;
                }
                return json;
            } catch (Exception e) {
                last = e;
                if (i < 3) Thread.sleep(2000L * (i + 1));
            }
        }
        if (last != null) throw last;
        throw new IllegalStateException("qzone request failed");
    }

    /** 拉取说说列表（含仅自己可见，need_private_comment=1）。 */
    public List<Mood> listAll(int pageSize) throws Exception {
        if (pageSize < 1) pageSize = 20;
        if (pageSize > 100) pageSize = 100;
        Auth a = auth();
        ArrayList<Mood> out = new ArrayList<>();
        int pos = 0;
        for (int page = 0; page < 200; page++) {
            String api = "https://h5.qzone.qq.com/proxy/domain/taotao.qq.com/cgi-bin/emotion_cgi_msglist_v6"
                    + "?uin=" + enc(a.uin)
                    + "&hostUin=" + enc(a.uin)
                    + "&ftype=0&sort=0&pos=" + pos + "&num=" + pageSize
                    + "&replynum=0&g_tk=" + a.gtk
                    + "&code_version=1&format=jsonp&callback=_preloadCallback&need_private_comment=1";
            JSONObject json = getJsonWithRetry(api, a.cookie);
            int sub = json.optInt("subcode", json.optInt("code", -1));
            if (sub != 0 && sub != -1)
                throw new IllegalStateException("qzone list failed: subcode=" + sub + " "
                        + json.optString("message", json.optString("msg", "")));
            JSONArray msglist = json.optJSONArray("msglist");
            if (msglist == null || msglist.length() == 0) break;
            int before = out.size();
            for (int i = 0; i < msglist.length(); i++) {
                JSONObject item = msglist.optJSONObject(i);
                if (item == null) continue;
                Mood m = new Mood();
                m.tid = item.optString("tid", item.optString("t1_tid", ""));
                m.content = item.optString("content", item.optString("con", ""));
                m.createdTime = item.optLong("created_time", item.optLong("createTime", 0));
                m.ugcRight = item.optInt("ugc_right", item.optInt("right", 0));
                if (!m.tid.isEmpty()) out.add(m);
            }
            pos += msglist.length();
            if (out.size() == before) break;
        }
        return out;
    }

    public JSONObject deleteAll() throws Exception {
        List<Mood> moods = listAll(50);
        JSONArray deleted = new JSONArray();
        JSONArray failed = new JSONArray();
        for (Mood m : moods) {
            try {
                delete(m.tid);
                deleted.put(new JSONObject().put("tid", m.tid)
                        .put("content", m.content == null ? "" : m.content));
                Thread.sleep(1500);
                if (deleted.length() % 5 == 0) Thread.sleep(4000);
            } catch (Exception e) {
                failed.put(new JSONObject().put("tid", m.tid)
                        .put("error", String.valueOf(e.getMessage())));
            }
        }
        return new JSONObject()
                .put("total", moods.size())
                .put("deleted", deleted.length())
                .put("failed", failed.length())
                .put("items", deleted)
                .put("errors", failed);
    }

    private JSONObject postJson(String api, String body, String cookie) throws Exception {
        String raw = http("POST", api, body, cookie, "application/x-www-form-urlencoded");
        return parseJsonBody(raw);
    }

    private JSONObject getJson(String api, String cookie) throws Exception {
        String raw = http("GET", api, null, cookie, null);
        return parseJsonBody(raw);
    }

    private static JSONObject parseJsonBody(String raw) throws Exception {
        if (raw == null) raw = "";
        raw = raw.trim();
        if (raw.isEmpty()) throw new IllegalStateException("qzone empty response");
        if (raw.startsWith("_preloadCallback(") || raw.contains("frameElement.callback")) {
            int l = raw.indexOf('(');
            int r = raw.lastIndexOf(')');
            if (l >= 0 && r > l) raw = raw.substring(l + 1, r);
        }
        try {
            return new JSONObject(raw);
        } catch (Exception e) {
            throw new IllegalStateException("qzone bad json: " + raw.substring(0, Math.min(200, raw.length())));
        }
    }

    private static String http(String method, String api, String body, String cookie, String contentType)
            throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(api).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);
        c.setRequestMethod(method);
        c.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36");
        c.setRequestProperty("Cookie", cookie);
        c.setRequestProperty("Referer", "https://user.qzone.qq.com/");
        if (contentType != null) c.setRequestProperty("Content-Type", contentType);
        if (body != null) {
            c.setDoOutput(true);
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            c.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream o = c.getOutputStream()) { o.write(bytes); }
        }
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        if (in == null) in = c.getInputStream();
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        if (in != null) {
            byte[] tmp = new byte[8192];
            int n;
            while ((n = in.read(tmp)) > 0) buf.write(tmp, 0, n);
        }
        c.disconnect();
        if (code >= 400)
            throw new IllegalStateException("qzone HTTP " + code + ": " + buf.toString(StandardCharsets.UTF_8.name()));
        return buf.toString(StandardCharsets.UTF_8.name());
    }

    static Map<String, String> cookiesFromJump(String jumpUrl) throws Exception {
        LinkedHashMap<String, String> jar = new LinkedHashMap<>();
        HttpURLConnection c = (HttpURLConnection) new URL(jumpUrl).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(false);
        c.setRequestMethod("GET");
        c.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36");
        for (int hop = 0; hop < 8; hop++) {
            int code = c.getResponseCode();
            mergeSetCookie(jar, c.getHeaderField("Set-Cookie"));
            mergeSetCookie(jar, c.getHeaderField("set-cookie"));
            if (code >= 300 && code < 400) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                if (loc == null || loc.isEmpty()) break;
                c = (HttpURLConnection) new URL(loc).openConnection();
                c.setConnectTimeout(20000);
                c.setReadTimeout(30000);
                c.setInstanceFollowRedirects(false);
                c.setRequestMethod("GET");
                c.setRequestProperty("User-Agent",
                        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36");
                if (!jar.isEmpty()) c.setRequestProperty("Cookie", cookieHeader(jar));
                continue;
            }
            try (InputStream in = c.getInputStream()) {
                byte[] tmp = new byte[4096];
                while (in.read(tmp) >= 0) { /* drain */ }
            } catch (Throwable ignore) {}
            c.disconnect();
            break;
        }
        return jar;
    }

    private static void mergeSetCookie(Map<String, String> jar, String header) {
        if (header == null || header.isEmpty()) return;
        String part = header.split(";", 2)[0].trim();
        int eq = part.indexOf('=');
        if (eq <= 0) return;
        jar.put(part.substring(0, eq).trim(), part.substring(eq + 1).trim());
    }

    static String cookieHeader(Map<String, String> jar) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : jar.entrySet()) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    private static String form(Map<String, String> fields) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : fields.entrySet()) {
            if (sb.length() > 0) sb.append('&');
            sb.append(enc(e.getKey())).append('=').append(enc(e.getValue() == null ? "" : e.getValue()));
        }
        return sb.toString();
    }

    private static String enc(String s) throws Exception {
        return URLEncoder.encode(s, "UTF-8");
    }

    private static String firstNonEmpty(String a, String b) {
        if (a != null && !a.isEmpty()) return a;
        return b == null ? "" : b;
    }
}
