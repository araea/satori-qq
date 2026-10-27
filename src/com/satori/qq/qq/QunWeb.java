package com.satori.qq.qq;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 群公告与群精华的读走 qun.qq.com 的网页 CGI，不从 QQNT 内核走：内核的
 * {@code getGroupBulletinList} / {@code fetchGroupEssenceList} 在本机 9.3.75/9.3.80 上调用即段错误
 * （CrashDefend 吞掉 signal 11，连接直接断），而客户端自己的公告页与精华页本来就是 H5，
 * 网页侧的 CGI 是它们的数据源。
 *
 * <p>0.51.0 起两条读都换成社区实现里长期验证过的接口（{@code group_digest/digest_list}、
 * 带 {@code qid} 的 {@code announce/get_t_list}），回包在这里规整成固定字段：调用方拿到的是
 * 「谁、什么时候、说了什么」，不必再认服务器那套缩写。原回包只在失败时随 {@code raw} 带回。
 */
public final class QunWeb {
    private QunWeb() {}

    /** 网页凭据：qun.qq.com 的 p_skey，外加能拿到时的 skey（bkn 优先按它算）。 */
    public record Ticket(String uin, String pskey, String skey) {
        String cookie() {
            StringBuilder out = new StringBuilder("uin=o").append(uin).append("; p_uin=o").append(uin)
                    .append("; p_skey=").append(pskey);
            if (skey != null && !skey.isEmpty()) out.append("; skey=").append(skey);
            return out.toString();
        }

        /** bkn 的候选：网页按 skey 算，没有 skey 或被拒时再试 p_skey 算的那一个。 */
        List<Long> bkns() {
            List<Long> out = new ArrayList<>();
            if (skey != null && !skey.isEmpty()) out.add(Qzone.gtk(skey));
            long fromPskey = Qzone.gtk(pskey);
            if (!out.contains(fromPskey)) out.add(fromPskey);
            return out;
        }
    }

    /** 群公告：客户端公告页的数据源。{@code s=-1} 从最新一条起，{@code n} 是条数。 */
    private static final String NOTICE_LIST = "https://web.qun.qq.com/cgi-bin/announce/get_t_list"
            + "?bkn=%d&qid=%d&ft=23&ni=1&i=1&log_read=1&platform=1&s=-1&n=%d";
    private static final String NOTICE_REFERER = "https://web.qun.qq.com/mannounce/index.html";

    /** 群精华：精华 H5 页的数据源，按页翻（page_start 从 0 起）。 */
    private static final String ESSENCE_LIST = "https://qun.qq.com/cgi-bin/group_digest/digest_list"
            + "?bkn=%d&group_code=%d&page_start=%d&page_limit=%d";
    private static final String ESSENCE_REFERER = "https://qun.qq.com/essence/index?gc=%d";

    private static final int CONNECT_MS = 10_000;
    private static final int READ_MS = 20_000;
    private static final int MAX_REPLY = 400_000;

    /**
     * 群公告列表（置顶的在前）。返回 {ok, group, count, bulletins[]}；每条
     * {id, sender_id, published_at, title, text, pinned, read_count, images}。
     */
    public static JSONObject bulletinList(Ticket ticket, long group, int count) throws Exception {
        int n = Math.max(1, Math.min(count <= 0 ? 10 : count, 50));
        JSONObject rsp = null;
        for (long bkn : ticket.bkns()) {
            rsp = get(String.format(NOTICE_LIST, bkn, group, n), ticket, NOTICE_REFERER);
            if (rsp.optBoolean("ok", false)) break;
        }
        JSONArray out = new JSONArray();
        List<String> seen = new ArrayList<>();
        // inst 是置顶公告，feeds 是其余的；同一条两边都可能出现，按 fid 去重。
        for (String key : new String[]{"inst", "feeds"}) {
            JSONArray list = rsp.optJSONArray(key);
            if (list == null) continue;
            for (int i = 0; i < list.length(); i++) {
                JSONObject f = list.optJSONObject(i);
                if (f == null) continue;
                String id = f.optString("fid", f.optString("feed_id", ""));
                if (!id.isEmpty() && seen.contains(id)) continue;
                seen.add(id);
                JSONObject msg = f.optJSONObject("msg");
                if (msg == null) msg = new JSONObject();
                JSONArray images = new JSONArray();
                JSONArray pics = msg.optJSONArray("pics");
                for (int k = 0; pics != null && k < pics.length(); k++) {
                    JSONObject pic = pics.optJSONObject(k);
                    if (pic != null && !pic.optString("id", "").isEmpty()) {
                        images.put("https://gdynamic.qpic.cn/gdynamic/" + pic.optString("id") + "/0");
                    }
                }
                out.put(new JSONObject()
                        .put("id", id)
                        .put("sender_id", String.valueOf(f.optLong("u", 0)))
                        .put("published_at", f.optLong("pubt", 0))
                        .put("title", unescape(msg.optString("title", "")))
                        .put("text", unescape(msg.optString("text", msg.optString("text_face", ""))))
                        .put("pinned", "inst".equals(key))
                        .put("read_count", f.optInt("read_num", 0))
                        .put("images", images));
            }
        }
        JSONObject result = new JSONObject().put("ok", rsp.optBoolean("ok", false)).put("group", group)
                .put("count", out.length()).put("bulletins", out);
        if (!rsp.optBoolean("ok", false)) result.put("error", describe(rsp)).put("raw", rsp);
        return result;
    }

    /**
     * 群精华一页。返回 {ok, group, page, is_end, count, essences[]}；每条
     * {message_seq, message_random, sender_id, sender_name, sent_at, operator_id, operator_name,
     * added_at, text, images}。
     */
    public static JSONObject essenceList(Ticket ticket, long group, int page, int limit) throws Exception {
        int size = Math.max(1, Math.min(limit <= 0 ? 20 : limit, 50));
        int start = Math.max(0, page);
        JSONObject rsp = null;
        for (long bkn : ticket.bkns()) {
            rsp = get(String.format(ESSENCE_LIST, bkn, group, start, size), ticket,
                    String.format(ESSENCE_REFERER, group));
            if (rsp.optBoolean("ok", false)) break;
        }
        JSONObject data = rsp.optJSONObject("data");
        if (data == null) data = new JSONObject();
        JSONArray list = data.optJSONArray("msg_list");
        JSONArray out = new JSONArray();
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject m = list.optJSONObject(i);
            if (m == null) continue;
            StringBuilder text = new StringBuilder();
            JSONArray images = new JSONArray();
            JSONArray content = m.optJSONArray("msg_content");
            for (int k = 0; content != null && k < content.length(); k++) {
                JSONObject part = content.optJSONObject(k);
                if (part == null) continue;
                switch (part.optInt("msg_type", 0)) {
                    case 1 -> text.append(part.optString("text", ""));
                    case 2 -> text.append("[表情]");
                    case 3 -> {
                        text.append("[图片]");
                        String url = part.optString("image_url", "");
                        if (!url.isEmpty()) images.put(url);
                    }
                    case 4 -> text.append("[文件").append(part.optString("file_name", "").isEmpty()
                            ? "" : "：" + part.optString("file_name")).append(']');
                    default -> {
                        String plain = part.optString("text", "");
                        text.append(plain.isEmpty() ? "[其他]" : plain);
                    }
                }
            }
            out.put(new JSONObject()
                    .put("message_seq", m.optLong("msg_seq", 0))
                    .put("message_random", m.optLong("msg_random", 0))
                    .put("sender_id", m.optString("sender_uin", ""))
                    .put("sender_name", m.optString("sender_nick", ""))
                    .put("sent_at", m.optLong("sender_time", 0))
                    .put("operator_id", m.optString("add_digest_uin", ""))
                    .put("operator_name", m.optString("add_digest_nick", ""))
                    .put("added_at", m.optLong("add_digest_time", 0))
                    .put("text", text.toString())
                    .put("images", images));
        }
        JSONObject result = new JSONObject().put("ok", rsp.optBoolean("ok", false)).put("group", group)
                .put("page", start).put("is_end", data.optBoolean("is_end", out.length() < size))
                .put("count", out.length()).put("essences", out);
        if (!rsp.optBoolean("ok", false)) result.put("error", describe(rsp)).put("raw", rsp);
        return result;
    }

    private static String describe(JSONObject rsp) {
        return "http=" + rsp.optInt("http_status", 0) + " code=" + rsp.optInt("ec", -1)
                + (rsp.optString("em", "").isEmpty() ? "" : " " + rsp.optString("em"));
    }

    /** 公告正文是 HTML 转义过的（换行写成 &#10;）。 */
    static String unescape(String text) {
        if (text == null || text.indexOf('&') < 0) return text == null ? "" : text;
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            int end = c == '&' ? text.indexOf(';', i) : -1;
            if (end < 0 || end - i > 10) {
                out.append(c);
                continue;
            }
            String entity = text.substring(i + 1, end);
            String plain = switch (entity) {
                case "amp" -> "&";
                case "lt" -> "<";
                case "gt" -> ">";
                case "quot" -> "\"";
                case "apos", "#39" -> "'";
                case "nbsp" -> " ";
                default -> {
                    try {
                        if (entity.startsWith("#x") || entity.startsWith("#X")) {
                            yield new String(Character.toChars(Integer.parseInt(entity.substring(2), 16)));
                        }
                        if (entity.startsWith("#")) {
                            yield new String(Character.toChars(Integer.parseInt(entity.substring(1))));
                        }
                    } catch (RuntimeException ignored) {
                        // 不认识的实体原样留着
                    }
                    yield null;
                }
            };
            if (plain == null) {
                out.append(c);
            } else {
                out.append(plain);
                i = end;
            }
        }
        return out.toString();
    }

    private static JSONObject get(String url, Ticket ticket, String referer) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(CONNECT_MS);
        conn.setReadTimeout(READ_MS);
        conn.setRequestProperty("Cookie", ticket.cookie());
        conn.setRequestProperty("Referer", referer);
        conn.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) "
                        + "Chrome/130.0.0.0 Mobile Safari/537.36 QQ/9.3.80");
        int status = conn.getResponseCode();
        StringBuilder reply = new StringBuilder();
        InputStream stream = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
        if (stream != null) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null && reply.length() < MAX_REPLY) {
                    reply.append(line).append('\n');
                }
            }
        }
        JSONObject out = new JSONObject();
        out.put("http_status", status);
        String text = reply.toString().trim();
        try {
            JSONObject data = new JSONObject(text);
            // 两个 CGI 的返回码字段不同：公告是 ec/em，精华是 retcode/retmsg。
            int code = data.has("retcode") ? data.optInt("retcode", -1)
                    : data.optInt("ec", data.optInt("code", data.optInt("ret", -1)));
            out.put("ok", status < 400 && code == 0).put("ec", code);
            String message = data.optString("em", data.optString("retmsg", ""));
            if (!message.isEmpty()) out.put("em", message);
            for (Iterator<String> it = data.keys(); it.hasNext(); ) {
                String k = it.next();
                if (!"ok".equals(k)) out.put(k, data.opt(k));
            }
        } catch (Exception e) {
            out.put("ok", false).put("body", text.length() > 400 ? text.substring(0, 400) : text);
        }
        return out;
    }
}
