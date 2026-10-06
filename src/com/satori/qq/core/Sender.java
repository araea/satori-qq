package com.satori.qq.core;

import com.satori.qq.Cfg;
import com.satori.qq.L;
import com.satori.qq.qq.Convert;
import com.satori.qq.qq.QQClient;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/** 往 QQ 发一条消息的最底层：转元素、过内核、重试媒体上传、登记回执。 */
final class Sender {
    private static final long SEND_TIMEOUT_MS = 45_000;
    private static final long ECHO_TTL_MS = 120_000;

    private final Cfg cfg;
    private final QQClient qq;
    private final MsgStore store;
    private final Convert conv;
    private final Identity identity;
    private final Directory directory;
    private final OutboundGate gate;
    private final MessageFreshness freshness;
    /** HTTP 请求各在自己的线程里跑，发送条件绝不能在调用者之间共享。 */
    private final ThreadLocal<MessageFreshness.Condition> condition = new ThreadLocal<>();
    /** message.create 回声的 msgId；短期保留，覆盖内核的 add/update/recv 多路回调。 */
    private final Set<Long> echoes = ConcurrentHashMap.newKeySet();

    Sender(Cfg cfg, QQClient qq, MsgStore store, Convert conv, Identity identity, Directory directory,
           OutboundGate gate, MessageFreshness freshness) {
        this.cfg = cfg;
        this.qq = qq;
        this.store = store;
        this.conv = conv;
        this.identity = identity;
        this.directory = directory;
        this.gate = gate;
        this.freshness = freshness;
    }

    void reset() { echoes.clear(); }

    /** 这条 msgId 是不是我们自己 API 发出去的回声。 */
    boolean isEcho(long msgId) { return echoes.contains(msgId); }

    private void rememberEcho(long msgId) {
        if (msgId == 0 || !echoes.add(msgId)) return;
        Workers.scheduler().schedule(() -> echoes.remove(msgId), ECHO_TTL_MS, TimeUnit.MILLISECONDS);
    }

    /** 给本线程接下来的发送挂上「只在仍是最新时才发」的条件；传 null 摘掉。 */
    void condition(MessageFreshness.Condition value) {
        if (value == null) condition.remove();
        else condition.set(value);
    }

    QQClient.SendResult track(int chatType, String peer, List<Object> elements) {
        return track(chatType, peer, elements, SEND_TIMEOUT_MS);
    }

    QQClient.SendResult track(int chatType, String peer, List<Object> elements, long timeoutMs) {
        // Conversion/downloads and retry backoff can take time after leaving the queue.
        gate.checkAccountUnchanged();
        freshness.check(condition.get());
        return qq.sendMsg(chatType, peer, elements, this::rememberEcho, timeoutMs);
    }

    /**
     * Send segments, retrying when QQ's kernel fails to upload their media.
     *
     * <p>Image/voice/video/file payloads are transferred to QQ's rich-media servers from inside
     * {@code sendMsg}, before the message itself is dispatched. That transfer is the first thing to
     * break when the screen has been locked long enough for the radio to be parked — the callback
     * comes back {@code code=-1 "rich media transfer failed"} while plain text sent a second later
     * still rides the already-established MSF socket. Nothing reached the peer in that case, so
     * re-entering {@code sendMsg} cannot duplicate a delivered message.
     *
     * <p>Elements are rebuilt from the original segments on every attempt: the kernel writes upload
     * state (file ids, paths, sizes) into the MsgElement it was handed, and a half-filled element
     * from a failed attempt is not safe to hand back.
     *
     * <p>{@code skipEmpty} returns null instead of entering the kernel when nothing converted.
     */
    QQClient.SendResult sendSegments(int chatType, String peer, Object message, boolean skipEmpty)
            throws Exception {
        int attempts = 1 + (hasMedia(message) ? Math.max(0, cfg.mediaRetryAttempts) : 0);
        // Clients wait on one HTTP call, so the retries have to fit inside their timeout budget.
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(
                cfg.mediaRetryBudgetMs > 0 ? cfg.mediaRetryBudgetMs : SEND_TIMEOUT_MS);
        QQClient.SendResult result = null;
        for (int i = 0; i < attempts; i++) {
            if (i > 0) {
                long backoff = RetryPolicy.backoffMs(cfg.mediaRetryBackoffMs, i, cfg.mediaRetryBudgetMs);
                if (System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(backoff) >= deadline) {
                    L.e("media transfer failed (" + result.msg + "); retry budget spent", null);
                    return result;
                }
                L.e("media transfer failed (" + result.msg + "); retry " + i + "/" + (attempts - 1), null);
                Thread.sleep(backoff);
            }
            List<Object> elements = conv.toElements(message, chatType);
            if (skipEmpty && (elements == null || elements.isEmpty())) return null;
            long left = Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
            result = track(chatType, peer, elements, left);
            if (result.code == 0 || !isMediaTransferFailure(result)) return result;
        }
        return result;
    }

    /** QQ's wording for an upload that never left the device; the send failed before dispatch. */
    private static boolean isMediaTransferFailure(QQClient.SendResult r) {
        if (r == null || r.code == 0 || r.code == -2 || r.msg == null) return false;
        String m = r.msg.toLowerCase(Locale.ROOT);
        return m.contains("rich media") || m.contains("media transfer") || m.contains("upload")
                || m.contains("富媒体");
    }

    /** True when the outgoing segments carry something QQ has to upload before it can send. */
    private static boolean hasMedia(Object message) {
        if (!(message instanceof JSONArray segments)) return false;
        for (int i = 0; i < segments.length(); i++) {
            JSONObject seg = segments.optJSONObject(i);
            if (seg == null) continue;
            switch (seg.optString("type", "")) {
                case "image", "record", "video", "file" -> { return true; }
                default -> { }
            }
        }
        return false;
    }

    JSONObject toGroup(long groupId, Object message, String content) throws Exception {
        if (groupId == 0) throw ApiError.badRequest("missing group_id");
        QQClient.SendResult r = sendSegments(QQClient.CT_GROUP, String.valueOf(groupId), message, false);
        return afterSend(r, QQClient.CT_GROUP, groupId, String.valueOf(groupId), content);
    }

    JSONObject toUser(long userId, Object message, String content) throws Exception {
        if (userId == 0) throw ApiError.badRequest("missing user_id");
        String uid = directory.uidOf(userId);
        if (uid.isEmpty()) throw ApiError.notFound("cannot resolve uid for user " + userId);
        QQClient.SendResult r = sendSegments(QQClient.CT_C2C, uid, message, false);
        return afterSend(r, QQClient.CT_C2C, userId, uid, content);
    }

    /** 发出去之后：把这条消息登记进本地存储，给调用方一个能回头引用、撤回的 id。 */
    JSONObject afterSend(QQClient.SendResult r, int chatType, long peerUin, String peerUid, String content)
            throws Exception {
        if (r.code != 0) throw ApiError.failed("send failed (code=" + r.code + "): " + r.msg);
        String peer = peerUid != null && !peerUid.isEmpty() ? peerUid : String.valueOf(peerUin);
        Object fetched = null;
        try {
            fetched = qq.fetchRecord(chatType, peer, r.msgId);
            qq.dumpSent(chatType, peer, r.msgId);
        } catch (Throwable t) {
            L.e("dumpSent", t);
        }
        MsgStore.Rec rec = new MsgStore.Rec();
        rec.chatType = chatType;
        rec.peerUin = peerUin;
        rec.peerUid = peer;
        rec.msgId = r.msgId;
        rec.senderUin = identity.selfUin();
        rec.msgRecord = fetched;
        if (fetched != null) rec.msgSeq = qq.ref.getLong(Convert.unwrapRecord(fetched), "msgSeq");
        rec.content = content == null ? "" : content;
        rec.contentIsElements = !rec.content.isEmpty();
        int id = store.put(rec);
        JSONObject out = new JSONObject().put("store_id", id);
        if (r.msgId != 0) out.put("message_id", String.valueOf(r.msgId)).put("qq_msg_id", r.msgId);
        else out.put("message_id", id);
        return out;
    }

    JSONObject afterSend(QQClient.SendResult r, int chatType, long peerUin, String peerUid) throws Exception {
        return afterSend(r, chatType, peerUin, peerUid, "");
    }
}
