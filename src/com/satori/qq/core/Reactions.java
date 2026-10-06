package com.satori.qq.core;

import com.satori.qq.L;
import com.satori.qq.qq.Convert;
import com.satori.qq.qq.QQClient;
import com.satori.qq.qq.Ref;
import com.satori.qq.satori.Codec;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/** 表情回应：加、撤、列，以及从消息更新里读出别人的回应变化推成事件。 */
final class Reactions {
    private static final String SET_CALLBACK = "com.tencent.qqnt.kernel.nativeinterface.ISetMsgEmojiLikesCallback";
    private static final String LIST_CALLBACK = "com.tencent.qqnt.kernel.nativeinterface.IGetMsgEmojiLikesListCallback";

    /** 内核「谁点了赞」回调攒出来的答复。 */
    private static final class Answer {
        int code = -1;
        String message = "";
        String next = "";
        List<?> users;
    }

    private final QQClient qq;
    private final Convert conv;
    private final Identity identity;
    private final Lookup lookup;
    private final Events events;
    /**
     * 本登录号在这条消息上加过的表情键（store 消息号 → emojiKey 集合）。
     *
     * <p>QQ 内核的「谁点了赞」列表不含自己，`reaction.list` 只能退化成读本地记录；而那条记录
     * 是内核推来的，滞后一拍（2026-09-19 实测：加完立刻回读是空、清完立刻回读还是 1）。
     * 自己做的动作自己记一份，回读才跟得上手。
     */
    private final Map<Integer, Set<String>> mine = new ConcurrentHashMap<>();
    /** 内核消息号 → 各表情的计数，用来在更新到来时算出谁加了、谁撤了。 */
    private final Map<Long, Map<String, Long>> counts = new ConcurrentHashMap<>();

    Reactions(QQClient qq, Convert conv, Identity identity, Lookup lookup, Events events) {
        this.qq = qq;
        this.conv = conv;
        this.identity = identity;
        this.lookup = lookup;
        this.events = events;
    }

    void reset() {
        mine.clear();
        counts.clear();
    }

    // ------------------------------------------------------------------ API

    void add(JSONObject p) throws Exception {
        String emoji = p.optString("emoji_id", "");
        MsgStore.Rec rec = lookup.requireInChannel(p);
        set(rec, Ids.emoji(emoji), emoji, true);
    }

    void remove(JSONObject p) throws Exception {
        String target = p.optString("user_id", "");
        if (!target.isEmpty() && !String.valueOf(identity.selfUin()).equals(target)) {
            throw ApiError.badRequest("QQ can only remove the current login's reaction");
        }
        String emoji = p.optString("emoji_id", "");
        MsgStore.Rec rec = lookup.requireInChannel(p);
        set(rec, Ids.emoji(emoji), emoji, false);
    }

    /** internal/reaction_clear：QQ 没有「清掉整条消息的回应」，只能撤本登录号自己加过的。 */
    JSONObject clearOwn(JSONObject p) throws Exception {
        String emoji = p.optString("emoji_id", "");
        MsgStore.Rec rec = lookup.requireInChannel(p);
        if (!emoji.trim().isEmpty()) {
            set(rec, Ids.emoji(emoji), emoji, false);
            return new JSONObject().put("cleared", 1).put("scope", "self");
        }
        List<String> keys = ownKeys(rec);
        int cleared = 0;
        String lastError = "";
        for (String key : keys) {
            try {
                set(rec, Ids.emoji(key), key, false);
                cleared++;
            } catch (ApiError e) {
                lastError = e.getMessage();
            }
        }
        if (!lastError.isEmpty()) {
            throw ApiError.failed("reaction clear partially failed; cleared=" + cleared + ": " + lastError);
        }
        return new JSONObject().put("cleared", cleared).put("scope", "self");
    }

    /** A local QQNT snapshot, never a fabricated list of reaction authors. */
    JSONObject summary(JSONObject p) throws Exception {
        MsgStore.Rec r = lookup.requireInChannel(p);
        Object record = qq.fetchRecord(r.chatType, r.peer(), r.msgId);
        if (record != null) r.msgRecord = record;
        record = r.msgRecord == null ? null : Convert.unwrapRecord(r.msgRecord);
        if (record == null) throw ApiError.notFound("message record unavailable");
        Map<String, Long> snapshot = snapshot(record);
        List<String> own = ownKeys(r);
        for (String id : own) snapshot.putIfAbsent(id, 0L);
        JSONArray data = new JSONArray();
        for (Map.Entry<String, Long> entry : snapshot.entrySet()) {
            data.put(new JSONObject().put("emoji_id", entry.getKey())
                    .put("count", entry.getValue()).put("self", own.contains(entry.getKey())));
        }
        return new JSONObject().put("message_id", String.valueOf(r.msgId))
                .put("data", data).put("source", "kernel_cache")
                .put("observed_at", System.currentTimeMillis());
    }

    JSONObject list(JSONObject p) throws Exception {
        String emoji = p.optString("emoji_id", "").trim();
        MsgStore.Rec rec = lookup.requireInChannel(p);
        if (emoji.isEmpty()) throw ApiError.badRequest("missing emoji_id");
        return list(rec, Ids.emoji(emoji), emoji, p.optString("next", ""));
    }

    // ------------------------------------------------------------------ 内核调用

    /** QQ NT needs msgSeq; sent messages often land in MsgStore before seq is filled. */
    private long seqOf(MsgStore.Rec r) throws Exception {
        if (r.msgSeq != 0) return r.msgSeq;
        if (r.msgRecord != null) {
            long seq = qq.ref.getLong(Convert.unwrapRecord(r.msgRecord), "msgSeq");
            if (seq != 0) return r.msgSeq = seq;
        }
        if (r.msgId == 0) throw ApiError.badRequest("message missing seq");
        Object fetched = qq.fetchRecord(r.chatType, r.peer(), r.msgId);
        if (fetched != null) {
            r.msgRecord = fetched;
            long seq = qq.ref.getLong(Convert.unwrapRecord(fetched), "msgSeq");
            if (seq != 0) return r.msgSeq = seq;
        }
        throw ApiError.badRequest("message missing seq");
    }

    /** 1=QQ face, 2=unicode — id string length > 3 (NapCat/QQNT convention). */
    private static long emojiType(String key) { return key.length() > 3 ? 2L : 1L; }

    private static String emojiKey(String raw, long emojiId) {
        return raw != null && !raw.trim().isEmpty() ? raw.trim() : String.valueOf(emojiId);
    }

    private void set(MsgStore.Rec r, long emojiId, String emojiRaw, boolean on) throws Exception {
        Object msgService = qq.getMsgService();
        if (msgService == null) throw ApiError.failed("kernel not ready");
        Object contact = qq.ref.neu(QQClient.CONTACT, r.chatType, r.peer(), "");
        long msgSeq = seqOf(r);
        String key = emojiKey(emojiRaw, emojiId);
        KernelAck ack = new KernelAck();
        qq.ref.call(msgService, "setMsgEmojiLikes", contact, msgSeq, key, emojiType(key), on,
                ack.proxy(qq.ref, SET_CALLBACK, "onSetMsgEmojiLikes"));
        ack.await("reaction operation");
        if (r.msgId != 0) {
            Object fetched = qq.fetchRecord(r.chatType, r.peer(), r.msgId);
            if (fetched != null) r.msgRecord = fetched;
        }
        remember(r, key, on);
    }

    private JSONObject list(MsgStore.Rec r, long emojiId, String emojiRaw, String cursor) throws Exception {
        Object msgService = qq.getMsgService();
        if (msgService == null) throw ApiError.failed("kernel not ready");
        Object contact = qq.ref.neu(QQClient.CONTACT, r.chatType, r.peer(), "");
        long msgSeq = seqOf(r);
        String key = emojiKey(emojiRaw, emojiId);
        String cookie = cursor == null ? "" : cursor;
        CountDownLatch answered = new CountDownLatch(1);
        Answer answer = new Answer();
        Object callback = Proxy.newProxyInstance(qq.ref.cl, new Class<?>[]{qq.ref.cls(LIST_CALLBACK)},
                (proxy, m, args) -> {
                    try {
                        if (args != null) for (Object arg : args) absorb(arg, answer);
                    } catch (Throwable t) {
                        L.e("reactionList cb " + m.getName(), t);
                    }
                    answered.countDown();
                    return null;
                });
        qq.ref.call(msgService, "getMsgEmojiLikesList", contact, msgSeq, key, emojiType(key), cookie, true, 50, callback);
        if (!answered.await(15, TimeUnit.SECONDS)) throw ApiError.failed("reaction list timeout");
        if (answer.code == -1 && answer.users != null) answer.code = 0;
        JSONArray data = users(answer.users);
        if (answer.code == 0 && cookie.isEmpty()) {
            JSONArray own = ownUsers(r, key);
            for (int i = 0; i < own.length(); i++) {
                JSONObject user = own.getJSONObject(i);
                boolean found = false;
                for (int j = 0; j < data.length(); j++) {
                    if (user.optString("id").equals(data.getJSONObject(j).optString("id"))) found = true;
                }
                if (!found) data.put(user);
            }
        }
        if (answer.code != 0) throw ApiError.failed("reaction list failed: code=" + answer.code + " " + answer.message);
        JSONObject out = new JSONObject().put("data", data);
        if (answer.next != null && !answer.next.isEmpty() && !answer.next.equals(cursor)) out.put("next", answer.next);
        return out;
    }

    /** 回调的参数形态因 QQ 版本而异：数字是码，列表是用户，字符串是翻页 cookie，其余当结果对象读。 */
    private void absorb(Object arg, Answer answer) {
        try {
            if (arg == null) return;
            if (arg instanceof Number) {
                if (answer.code == -1) answer.code = Ref.asInt(arg);
            } else if (arg instanceof List<?> list) {
                answer.users = list;
            } else if (arg instanceof String) {
                String text = Ref.asStr(arg);
                if (!text.isEmpty() && answer.next.isEmpty()) answer.next = text;
            } else {
                String type = arg.getClass().getName();
                if (type.contains("Callback") || type.startsWith("java.") || type.startsWith("android.")) return;
                int parsed = Ref.asInt(qq.ref.get(arg, "result"));
                if (answer.code == -1 || parsed != 0) answer.code = parsed;
                String err = Ref.asStr(qq.ref.get(arg, "errMsg"));
                if (!err.isEmpty()) answer.message = err;
                String cookie = Ref.asStr(qq.ref.get(arg, "cookie"));
                if (!cookie.isEmpty()) answer.next = cookie;
                Object list = qq.ref.get(arg, "emojiLikesList");
                if (!(list instanceof List)) list = qq.ref.get(arg, "emojiLikeList");
                if (list instanceof List<?> found) answer.users = found;
            }
        } catch (Throwable ignore) {
            // 这个参数读不懂就跳过，别的参数可能带着答案
        }
    }

    private JSONArray users(List<?> users) throws Exception {
        JSONArray data = new JSONArray();
        if (users == null) return data;
        for (Object info : users) {
            JSONObject user = Codec.user(qq.ref.getLong(info, "tinyId"), Ref.asStr(qq.ref.get(info, "nickName")), "");
            String avatar = Ref.asStr(qq.ref.get(info, "headUrl"));
            if (!avatar.isEmpty()) user.put("avatar", avatar);
            data.put(user);
        }
        return data;
    }

    // ------------------------------------------------------------------ 自己的回应

    private void remember(MsgStore.Rec r, String key, boolean on) throws Exception {
        if (r.id == 0 || key == null || key.isEmpty()) return;
        Set<String> set = mine.get(r.id);
        if (set == null) {
            set = ConcurrentHashMap.newKeySet();
            set.addAll(ownKeys(r));
            mine.put(r.id, set);
        }
        if (on) set.add(key);
        else set.remove(key);
        if (mine.size() > 4000) mine.clear();
    }

    /**
     * 本地兜底：内核那条「谁点了赞」的记录里，本登录号自己那一条。
     *
     * <p>两个来源的优先级是**本地记账优先**：`mine` 记的是本进程亲眼看到的加/取消动作，
     * 而内核记录滞后一拍。反过来的写法会把「刚清掉的表情」重新读出来——2026-09-19 实测：
     * `reaction.clear` 之后立刻 `reaction.list` 会回 1 个人（就是我们自己），等到内核推来新
     * 记录才消失，于是巡检与客户端都会看到「清不掉」的假象。没有本地记账时（模块重启过）才退到
     * 内核记录的 `isClicked`。
     */
    private JSONArray ownUsers(MsgStore.Rec r, String key) throws Exception {
        JSONArray data = new JSONArray();
        Set<String> tracked = mine.get(r.id);
        boolean clicked = false;
        if (tracked != null) {
            clicked = tracked.contains(key);
        } else {
            Object rec = r.msgRecord;
            if (rec == null && r.msgId != 0) {
                rec = qq.fetchRecord(r.chatType, r.peer(), r.msgId);
                if (rec != null) r.msgRecord = rec;
            }
            rec = rec == null ? null : Convert.unwrapRecord(rec);
            if (rec != null && qq.ref.get(rec, "emojiLikesList") instanceof List<?> likes) {
                for (Object like : likes) {
                    if (!key.equals(Ref.asStr(qq.ref.get(like, "emojiId")))) continue;
                    if (qq.ref.getLong(like, "likesCnt") <= 0) continue;
                    Object flag = qq.ref.get(like, "isClicked");
                    clicked = flag instanceof Boolean b ? b : Ref.asInt(flag) != 0;
                    break;
                }
            }
        }
        if (!clicked) return data;
        JSONObject user = Codec.user(identity.selfUin(), qq.selfNick(), "");
        JSONObject login = identity.loginSlim().optJSONObject("user");
        if (login != null && !login.optString("avatar", "").isEmpty()) user.put("avatar", login.optString("avatar"));
        return data.put(user);
    }

    /**
     * Emoji ids this login itself clicked on a message. {@code MsgEmojiLikes.isClicked} is the
     * kernel's own flag for that, so nothing has to be guessed from the elsewhere-unkeyed counts.
     */
    private List<String> ownKeys(MsgStore.Rec r) {
        Set<String> tracked = mine.get(r.id);
        if (tracked != null) return new ArrayList<>(tracked);
        List<String> out = new ArrayList<>();
        Object rec = r.msgRecord;
        if (rec == null && r.msgId != 0) rec = qq.fetchRecord(r.chatType, r.peer(), r.msgId);
        if (rec == null) return out;
        if (qq.ref.get(Convert.unwrapRecord(rec), "emojiLikesList") instanceof List<?> likes) {
            for (Object like : likes) {
                String id = Ref.asStr(qq.ref.get(like, "emojiId"));
                if (!id.isEmpty() && Ref.asBool(qq.ref.get(like, "isClicked"))) out.add(id);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ 别人的回应

    private Map<String, Long> snapshot(Object record) {
        Map<String, Long> out = new LinkedHashMap<>();
        if (qq.ref.get(record, "emojiLikesList") instanceof List<?> likes) {
            for (Object like : likes) {
                String id = Ref.asStr(qq.ref.get(like, "emojiId"));
                if (!id.isEmpty()) out.put(id, Ref.asLong(qq.ref.get(like, "likesCnt")));
            }
        }
        return out;
    }

    /** 收到一条消息时记下它当前的回应计数，作为之后算差量的基线。 */
    void observe(Object record) {
        long msgId = Ref.asLong(qq.ref.get(record, "msgId"));
        if (msgId == 0) return;
        counts.put(msgId, snapshot(record));
        if (counts.size() > 8000) counts.clear();
    }

    /** 消息更新里回应计数变了：按表情逐个推 reaction-added / reaction-removed。 */
    void emitChanges(Object record) throws Exception {
        long kernelId = Ref.asLong(qq.ref.get(record, "msgId"));
        if (kernelId == 0) return;
        Map<String, Long> current = snapshot(record);
        Map<String, Long> previous = counts.put(kernelId, current);
        if (previous == null) return;
        JSONObject ob = conv.recordToEvent(record, 0);
        if (ob == null || !"message".equals(ob.optString("post_type"))) return;
        Set<String> ids = new HashSet<>(previous.keySet());
        ids.addAll(current.keySet());
        for (String emojiId : ids) {
            long before = previous.getOrDefault(emojiId, 0L);
            long after = current.getOrDefault(emojiId, 0L);
            if (before == after) continue;
            boolean group = "group".equals(ob.optString("message_type"));
            long peer = group ? ob.optLong("group_id") : ob.optLong("user_id");
            JSONObject body = new JSONObject()
                    .put("sn", 0)
                    .put("type", after > before ? "reaction-added" : "reaction-removed")
                    .put("timestamp", System.currentTimeMillis())
                    .put("login", identity.loginSlim())
                    .put("_type", "satori-qq/reaction")
                    .put("_data", new JSONObject().put("before", before).put("count", after).put("delta", after - before))
                    .put("emoji", new JSONObject().put("id", emojiId))
                    .put("message", new JSONObject().put("id", Codec.publicMessageId(ob)))
                    .put("channel", Codec.channel(group ? QQClient.CT_GROUP : QQClient.CT_C2C, peer, ""));
            if (group) body.put("guild", Codec.guild(peer, ""));
            events.emitSatori(body);
        }
    }
}
