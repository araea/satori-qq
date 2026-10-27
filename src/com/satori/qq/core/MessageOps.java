package com.satori.qq.core;

import com.satori.qq.qq.ExtraSvc;
import com.satori.qq.qq.QQClient;
import com.satori.qq.satori.Codec;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 以一条已有消息为对象的 QQ 动作：原生转发、语音转文字。
 *
 * <p>两者都先把 Satori 的 {@code message_id}（QQ NT msgId）找回成内核的那条记录，再交给
 * {@link ExtraSvc}；找记录的规矩与 {@code message.get} 相同（{@link Lookup}）。
 */
final class MessageOps {
    private final QQClient qq;
    private final Lookup lookup;
    private final Directory directory;

    MessageOps(QQClient qq, Lookup lookup, Directory directory) {
        this.qq = qq;
        this.lookup = lookup;
        this.directory = directory;
    }

    /**
     * internal/message_forward：把一条或几条消息原样转发到一个或几个会话。
     *
     * <p>参数：{@code message_id} 或 {@code message_ids[]}（同一来源会话），可选 {@code channel_id}
     * （来源；消息不在缓存里时用来找回），{@code target} 或 {@code targets[]}（频道 ID：群号或
     * {@code private:QQ号}）。与 {@code message.create} 重发不同，图片、文件、语音不重新上传，
     * 文件与长视频也转得动；代价是看不出「转发自谁」。
     */
    JSONObject forward(JSONObject p) throws Exception {
        List<String> raw = list(p, "message_ids", "message_id");
        if (raw.isEmpty()) throw ApiError.badRequest("missing message_id");
        List<String> targetIds = list(p, "targets", "target");
        if (targetIds.isEmpty()) throw ApiError.badRequest("missing target");
        if (raw.size() > 100) throw ApiError.badRequest("at most 100 messages per forward");
        if (targetIds.size() > 10) throw ApiError.badRequest("at most 10 targets per forward");

        List<Long> ids = new ArrayList<>();
        MsgStore.Rec first = null;
        for (String id : raw) {
            MsgStore.Rec rec = lookup.require(id, p);
            if (first == null) first = rec;
            else if (rec.chatType != first.chatType || rec.peerUin != first.peerUin) {
                throw ApiError.badRequest("all messages must come from the same channel");
            }
            ids.add(rec.msgId);
        }
        ExtraSvc extra = qq.extra();
        List<Object> targets = new ArrayList<>();
        for (String channel : targetIds) targets.add(contactFor(extra, channel));
        ExtraSvc.Result r = extra.forward(first.chatType, first.peer(), ids, targets);
        if (!r.ok()) throw ApiError.failed("forward: " + r.describe());
        return new JSONObject().put("ok", true).put("forwarded", ids.size()).put("targets", new JSONArray(targetIds));
    }

    /**
     * internal/voice_to_text：QQ 自带的语音转文字（长按语音「转文字」）。参数 {@code message_id}，
     * 可选 {@code channel_id}、{@code wait}（秒，默认 12）。同一条语音转过一次之后直接读缓存。
     */
    JSONObject voiceToText(JSONObject p) throws Exception {
        MsgStore.Rec rec = lookup.requireInChannel(p);
        Object record = rec.msgRecord;
        if (record == null) record = qq.fetchRecord(rec.chatType, rec.peer(), rec.msgId);
        if (record == null) throw ApiError.notFound("message record unavailable: " + rec.msgId);
        long wait = Math.max(2, Math.min(p.optInt("wait", 12), 25)) * 1000L;
        try {
            return qq.extra().pttToText(rec.chatType, rec.peer(), rec.msgId, record, wait)
                    .put("message_id", String.valueOf(rec.msgId));
        } catch (IllegalArgumentException e) {
            throw ApiError.badRequest(e.getMessage());
        }
    }

    private Object contactFor(ExtraSvc extra, String channel) throws Exception {
        long peer = Codec.channelPeer(channel);
        if (peer == 0) throw ApiError.badRequest("invalid target: " + channel);
        if (!Codec.isPrivateChannel(channel)) return extra.contact(QQClient.CT_GROUP, String.valueOf(peer));
        String uid = directory.uidFor(0, peer);
        if (uid == null || uid.isEmpty()) throw ApiError.notFound("cannot resolve uid for user " + peer);
        return extra.contact(QQClient.CT_C2C, uid);
    }

    /** 同一个参数既可以给一个值，也可以给数组（{@code message_ids} / {@code message_id}）。 */
    private static List<String> list(JSONObject p, String plural, String single) {
        List<String> out = new ArrayList<>();
        JSONArray many = p.optJSONArray(plural);
        for (int i = 0; many != null && i < many.length(); i++) {
            String v = many.optString(i, "").trim();
            if (!v.isEmpty()) out.add(v);
        }
        String one = p.optString(single, "").trim();
        if (!one.isEmpty() && !out.contains(one)) out.add(0, one);
        return out;
    }
}
