package com.satori.qq.core;

import java.util.LinkedHashMap;
import org.json.JSONObject;

/** Optional message.create condition for short-lived, best-effort replies. */
public final class MessageFreshness {
    private static final int CAPACITY = 4096;
    private final LinkedHashMap<String, String> latest = new LinkedHashMap<>();

    public synchronized void observe(JSONObject event) {
        if (!"message-created".equals(event.optString("type"))) return;
        JSONObject channel = event.optJSONObject("channel");
        JSONObject message = event.optJSONObject("message");
        if (channel == null || message == null) return;
        String key = channel.optString("id", "");
        String id = message.optString("id", "");
        if (key.isEmpty() || id.isEmpty()) return;
        latest.remove(key);
        latest.put(key, id);
        while (latest.size() > CAPACITY) latest.remove(latest.keySet().iterator().next());
    }

    public static final class Condition {
        final String channelId;
        final String messageId;
        final long expiresAt;
        private Condition(String channelId, String messageId, long expiresAt) {
            this.channelId = channelId;
            this.messageId = messageId;
            this.expiresAt = expiresAt;
        }
    }

    /** No extension means ordinary sends retain their existing queue behaviour. */
    public static Condition condition(JSONObject params) {
        JSONObject ext = params.optJSONObject("satori_qq");
        if (ext == null || (!ext.has("if_latest_message_id") && !ext.has("expires_at"))) return null;
        String id = ext.optString("if_latest_message_id", "");
        long expires = ext.optLong("expires_at", 0);
        if (id.isEmpty() || expires <= 0) throw new IllegalArgumentException("invalid message freshness condition");
        return new Condition(params.optString("channel_id", ""), id, expires);
    }

    public synchronized boolean isCurrent(Condition condition, long now) {
        return condition == null || (now < condition.expiresAt
                && condition.messageId.equals(latest.get(condition.channelId)));
    }

    public void check(Condition condition) {
        if (!isCurrent(condition, System.currentTimeMillis())) throw new Stale();
    }

    /** A skipped best-effort message is a successful no-op, not a QQ transport failure. */
    public static final class Stale extends RuntimeException {}
}
