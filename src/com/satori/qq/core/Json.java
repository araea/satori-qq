package com.satori.qq.core;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** org.json 上的几个小零件：消息段、拷贝、反射导出、分页。 */
final class Json {
    private Json() {}

    /** 内部消息段 {@code {type:"text", data:{text}}}。 */
    static JSONObject textSegment(String text) throws Exception {
        return new JSONObject().put("type", "text").put("data", new JSONObject().put("text", text));
    }

    static JSONArray textSegments(String text) throws Exception {
        return new JSONArray().put(textSegment(text));
    }

    static JSONObject copy(JSONObject src) throws Exception {
        JSONObject out = new JSONObject();
        if (src == null) return out;
        for (Iterator<?> keys = src.keys(); keys.hasNext(); ) {
            String key = String.valueOf(keys.next());
            out.put(key, src.get(key));
        }
        return out;
    }

    /** 客户端有没有传这个字符串键（传了空串也算没传）。 */
    static boolean blank(String s) { return s == null || s.trim().isEmpty(); }

    /** Reflect a kernel payload into JSON so read actions stay tolerant of QQ's field churn. */
    static Object reflect(Object v) throws Exception { return reflect(v, 4, 50); }

    private static Object reflect(Object v, int depth, int listCap) throws Exception {
        if (v == null) return JSONObject.NULL;
        if (v instanceof String || v instanceof Number || v instanceof Boolean) return v;
        if (v instanceof byte[] bytes) return "bytes:" + bytes.length;
        if (v instanceof Character || v.getClass().isEnum() || depth <= 0) return String.valueOf(v);
        if (v instanceof Map<?, ?> map) {
            JSONObject o = new JSONObject();
            int n = 0;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (n++ >= 200) break;
                putQuiet(o, String.valueOf(e.getKey()), reflect(e.getValue(), depth - 1, listCap));
            }
            return o;
        }
        if (v instanceof Collection<?> items) {
            JSONArray a = new JSONArray();
            int n = 0;
            for (Object e : items) {
                if (n++ >= listCap) {
                    a.put("...");
                    break;
                }
                a.put(reflect(e, depth - 1, listCap));
            }
            return a;
        }
        JSONObject o = new JSONObject();
        for (Class<?> c = v.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                try {
                    f.setAccessible(true);
                    putQuiet(o, f.getName(), reflect(f.get(v), depth - 1, listCap));
                } catch (Throwable ignore) {
                    // 读不出来的字段就不导出
                }
            }
        }
        return o;
    }

    private static void putQuiet(JSONObject o, String key, Object value) {
        try {
            o.put(key, value);
        } catch (Throwable ignore) {
            // 键值写不进去就跳过这一项
        }
    }

    /**
     * Satori {@code List}: `{data, next}`. A request that passes neither `next` nor `limit` keeps
     * the whole result set, which is what the earlier releases returned; a request that pages gets
     * an opaque offset token back. Callers that just want one page pass `limit`.
     */
    static JSONObject page(JSONArray all, JSONObject params, int defaultLimit) throws Exception {
        JSONArray data = all == null ? new JSONArray() : all;
        String token = params == null ? "" : params.optString("next", "");
        int limit = params == null ? 0 : params.optInt("limit", 0);
        if (limit <= 0 && token.isEmpty()) return new JSONObject().put("data", data);
        if (limit <= 0) limit = defaultLimit;
        long offset = 0;
        if (!token.isEmpty()) {
            // A malformed cursor must fail rather than quietly restart at page zero: clients
            // following `next` would otherwise repeat rows (or loop forever).
            if (!token.matches("[0-9]+")) throw ApiError.badRequest("invalid next token: " + token);
            try {
                offset = Long.parseLong(token);
            } catch (NumberFormatException e) {
                throw ApiError.badRequest("invalid next token: " + token);
            }
        }
        JSONArray slice = new JSONArray();
        for (long i = offset; i < data.length() && slice.length() < limit; i++) slice.put(data.opt((int) i));
        JSONObject out = new JSONObject().put("data", slice);
        long nextOffset = offset + slice.length();
        if (nextOffset < data.length()) out.put("next", String.valueOf(nextOffset));
        return out;
    }
}
