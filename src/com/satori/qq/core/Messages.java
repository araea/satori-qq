package com.satori.qq.core;

import com.satori.qq.L;
import com.satori.qq.qq.QQClient;
import com.satori.qq.satori.Batching;
import com.satori.qq.satori.Codec;
import com.satori.qq.satori.Elements;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** 发消息（{@code message.create}）与撤回（{@code message.delete}）。 */
final class Messages {
    private final QQClient qq;
    private final Identity identity;
    private final Lookup lookup;
    private final Resources resources;
    private final Sender sender;
    private final Forwards forwards;
    private final History history;
    private final Events events;
    private final MessageFreshness freshness;

    Messages(QQClient qq, Identity identity, Lookup lookup, Resources resources, Sender sender,
             Forwards forwards, History history, Events events, MessageFreshness freshness) {
        this.qq = qq;
        this.identity = identity;
        this.lookup = lookup;
        this.resources = resources;
        this.sender = sender;
        this.forwards = forwards;
        this.history = history;
        this.events = events;
        this.freshness = freshness;
    }

    JSONArray create(JSONObject p) throws Exception {
        String channelId = p.optString("channel_id", "");
        if (channelId.isEmpty()) throw ApiError.badRequest("missing channel_id");
        boolean group = !Codec.isPrivateChannel(channelId);
        long peer = Codec.channelPeer(channelId);
        if (peer == 0) throw ApiError.badRequest("invalid channel_id");
        MessageFreshness.Condition condition;
        try {
            condition = MessageFreshness.condition(p);
        } catch (IllegalArgumentException e) {
            throw ApiError.badRequest(e.getMessage());
        }
        sender.condition(condition);
        JSONArray result = new JSONArray();
        try {
            freshness.check(condition); // after OutboundGuard's queue and minimum interval
            // 顺媒体（语音/视频/群文件）与别的段落放同一条消息里时 QQ 渲染不出来，
            // 在这里兼容成几条发出去——见 Batching。
            List<List<Elements.El>> sends = new ArrayList<>();
            for (List<Elements.El> batch : split(Elements.parse(p.optString("content", "")))) {
                sends.addAll(Batching.splitChunkMedia(batch));
            }
            for (List<Elements.El> batch : sends) {
                freshness.check(condition);
                String sentContent = Elements.stringify(batch);
                resolveLinks(batch);
                JSONArray segments = segments(batch);
                if (segments.length() == 0) continue;
                JSONObject sent;
                if (Forwards.looksLikeForward(segments)) {
                    sent = group ? forwards.send(peer, 0, segments) : forwards.send(0, peer, segments);
                } else {
                    sent = group ? sender.toGroup(peer, segments, sentContent)
                            : sender.toUser(peer, segments, sentContent);
                }
                String mid = Codec.publicMessageId(sent);
                JSONObject msg = new JSONObject()
                        .put("id", mid.isEmpty() ? String.valueOf(sent.opt("message_id")) : mid)
                        .put("content", sentContent)
                        .put("channel", Codec.channel(group ? QQClient.CT_GROUP : QQClient.CT_C2C, peer, ""));
                if (group) msg.put("guild", Codec.guild(peer, ""));
                msg.put("user", Codec.user(identity.selfUin(), qq.selfNick(), ""))
                        .put("created_at", System.currentTimeMillis());
                result.put(msg);
            }
        } catch (MessageFreshness.Stale ignored) {
            L.d("message.create: skipped stale conditional send");
        } finally {
            sender.condition(null);
        }
        return result;
    }

    /** 顶层元素拆成要发的几条：{@code <message>} 是分隔符，转发与嵌套 message 各自成条。 */
    private static List<List<Elements.El>> split(List<Elements.El> roots) {
        List<List<Elements.El>> out = new ArrayList<>();
        List<Elements.El> current = new ArrayList<>();
        for (Elements.El el : roots) {
            if (!"message".equals(el.type)) {
                current.add(el);
                continue;
            }
            if (!current.isEmpty()) {
                out.add(current);
                current = new ArrayList<>();
            }
            if (el.attrs.optBoolean("forward", false) || hasChildMessage(el)) {
                out.add(List.of(el));
            } else if (!el.children.isEmpty()) {
                List<Elements.El> body = new ArrayList<>();
                for (Elements.El child : el.children) {
                    if (!"author".equals(child.type)) body.add(child);
                }
                if (!body.isEmpty()) out.add(body);
            }
            // An empty <message/> is only a separator.
        }
        if (!current.isEmpty()) out.add(current);
        return out;
    }

    private static boolean hasChildMessage(Elements.El el) {
        for (Elements.El child : el.children) {
            if ("message".equals(child.type)) return true;
        }
        return false;
    }

    private JSONArray segments(List<Elements.El> batch) throws Exception {
        if (batch.size() == 1) {
            Elements.El el = batch.get(0);
            if ("message".equals(el.type) && el.attrs.optBoolean("forward", false)
                    && el.children.isEmpty() && !el.attr("id").isEmpty()) {
                JSONArray source = history.onebot(lookup.require(el.attr("id")).id).optJSONArray("message");
                return source == null ? new JSONArray() : source;
            }
        }
        return Codec.toSegments(batch);
    }

    private void resolveLinks(List<Elements.El> elements) throws Exception {
        for (Elements.El el : elements) {
            String src = el.attr("src");
            if (src.startsWith("internal:")) el.attrs.put("src", resources.resolveLink(src));
            if (!el.children.isEmpty()) resolveLinks(el.children);
        }
    }

    void delete(JSONObject p) throws Exception {
        MsgStore.Rec rec = lookup.requireInChannel(p);
        long published = Ids.parse(p.optString("message_id", ""));
        if (rec.msgId == 0 && published != 0 && String.valueOf(published).length() >= 16) rec.msgId = published;
        recall(rec);
    }

    private void recall(MsgStore.Rec r) throws Exception {
        Object msgService = qq.getMsgService();
        if (msgService == null) throw ApiError.failed("kernel not ready");
        Object contact = qq.ref.neu(QQClient.CONTACT, r.chatType, r.peer(), "");
        KernelAck ack = new KernelAck();
        qq.ref.call(msgService, "recallMsg", contact, new ArrayList<>(List.of(r.msgId)),
                ack.proxy(qq.ref, QQClient.IOPERATE_CB, "onResult"));
        ack.await("recall");
        events.recalled(r.chatType == QQClient.CT_GROUP, r.peerUin, r.senderUin, identity.selfUin(), r.id, r.msgId);
    }
}
