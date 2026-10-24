package com.satori.qq.qq;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 群公告与群精华的读走 qun.qq.com 的网页 CGI，不从 QQNT 内核走：内核的
 * {@code getGroupBulletinList} / {@code fetchGroupEssenceList} 在本机 9.3.75 上调用即段错误
 * （CrashDefend 吞掉 signal 11，连接直接断），而客户端自己的公告页与精华页本来就是 H5，
 * 网页侧的 CGI 是它们的数据源，凭据同样是空间系的 p_skey + bkn。
 */
public final class QunWeb {
    private QunWeb() {}

    /** 群公告列表：客户端公告页用的就是它（classes35.dex 里有原样字符串）。 */
    private static final String NOTICE_LIST =
            "https://web.qun.qq.com/cgi-bin/announce/get_t_list?bkn=%d&group_code=%d";
    private static final String NOTICE_REFERER = "https://qun.qq.com/notice/";

    /** 群精华列表：精华 H5 页（qun.qq.com/essence/index）的数据接口。 */
    private static final String ESSENCE_LIST =
            "https://qun.qq.com/cgi-bin/essence/get_essence_list?gc=%d&bkn=%d";
    private static final String ESSENCE_REFERER = "https://qun.qq.com/essence/index?gc=%d";

    private static final int CONNECT_MS = 10_000;
    private static final int READ_MS = 20_000;

    /** 群公告列表。返回 {ok, raw, bulletins[]}；条目字段以服务器为准，原样透传。 */
    public static JSONObject bulletinList(String uin, String pskey, long group) throws Exception {
        JSONObject rsp = get(String.format(NOTICE_LIST, Qzone.gtk(pskey), group), uin, pskey, NOTICE_REFERER);
        JSONArray out = rsp.optJSONArray("li");
        if (out == null) {
            JSONObject data = rsp.optJSONObject("data");
            if (data != null) out = data.optJSONArray("li");
        }
        if (out != null) rsp.put("bulletins", out);
        return rsp;
    }

    /** 群精华列表。返回 {ok, raw, essences[]}；条目原样透传。 */
    public static JSONObject essenceList(String uin, String pskey, long group) throws Exception {
        JSONObject rsp = get(String.format(ESSENCE_LIST, group, Qzone.gtk(pskey)), uin, pskey,
                String.format(ESSENCE_REFERER, group));
        JSONArray out = null;
        JSONObject data = rsp.optJSONObject("data");
        if (data != null) out = data.optJSONArray("list");
        if (out == null) out = rsp.optJSONArray("list");
        if (out != null) rsp.put("essences", out);
        return rsp;
    }

    private static JSONObject get(String url, String uin, String pskey, String referer) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(CONNECT_MS);
        conn.setReadTimeout(READ_MS);
        conn.setRequestProperty("Cookie", "uin=o" + uin + "; p_uin=o" + uin + "; p_skey=" + pskey);
        conn.setRequestProperty("Referer", referer);
        conn.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) "
                        + "Chrome/130.0.0.0 Mobile Safari/537.36");
        int status = conn.getResponseCode();
        StringBuilder reply = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                status >= 400 ? conn.getErrorStream() : conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while (reader != null && (line = reader.readLine()) != null && reply.length() < 60_000) {
                reply.append(line);
            }
        }
        JSONObject out = new JSONObject();
        out.put("http_status", status);
        String text = reply.toString().trim();
        try {
            JSONObject data = new JSONObject(text);
            int code = data.optInt("ec", data.optInt("code", data.optInt("ret", -1)));
            out.put("ok", status < 400 && code == 0).put("ec", code);
            if (data.has("em")) out.put("em", data.optString("em"));
            // 条目原样带回去，字段名随服务器。
            for (Iterator<String> it = data.keys(); it.hasNext(); ) {
                String k = it.next();
                if (!"ok".equals(k)) out.put(k, data.opt(k));
            }
        } catch (Exception e) {
            out.put("ok", false).put("raw", text);
        }
        return out;
    }
}
