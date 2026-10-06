package com.satori.qq.core;

import com.satori.qq.qq.Convert;
import com.satori.qq.qq.QQClient;
import com.satori.qq.satori.Codec;
import org.json.JSONObject;

/** 把客户端手里的 message_id 找回成本地登记的消息；本地没有就向内核要。 */
final class Lookup {
    private final QQClient qq;
    private final MsgStore store;
    private final Convert conv;
    private final Directory directory;

    Lookup(QQClient qq, MsgStore store, Convert conv, Directory directory) {
        this.qq = qq;
        this.store = store;
        this.conv = conv;
        this.directory = directory;
    }

    MsgStore.Rec require(String raw) { return require(raw, null); }

    MsgStore.Rec require(String raw, JSONObject p) {
        MsgStore.Rec rec = store.resolve(raw);
        if (rec == null) rec = fetch(raw, p);
        if (rec == null) {
            // 只有真的没给才算 1400；给了但查不到（含非数字这种本模块不认的 id）是 404，
            // 否则客户端拿着一个可疑 id 会看到 "missing message_id" 这种误导性的说法。
            if (Json.blank(raw)) throw ApiError.badRequest("missing message_id");
            throw ApiError.notFound("message not found: " + raw);
        }
        return rec;
    }

    private MsgStore.Rec fetch(String raw, JSONObject p) {
        if (p == null) return null;
        long msgId = Ids.parse(raw);
        String channelId = p.optString("channel_id", "");
        if (msgId == 0 || channelId.isEmpty()) return null;
        boolean group = !Codec.isPrivateChannel(channelId);
        long peer = Codec.channelPeer(channelId);
        if (peer == 0) return null;
        int chatType = group ? QQClient.CT_GROUP : QQClient.CT_C2C;
        // Quotes emitted before the msgId fix carry a msgSeq. Resolve those inside this channel
        // rather than letting them fall through to an unrelated legacy store id. A real msgId is
        // 19 digits and never collides with a seq, and this runs only after resolve() missed.
        MsgStore.Rec bySeq = store.findByPeerSeq(chatType, peer, null, msgId);
        if (bySeq != null) return bySeq;
        try {
            String peerUid = group ? String.valueOf(peer) : directory.uidFor(0, peer);
            Object rec = qq.fetchRecord(chatType, peerUid, msgId);
            if (rec == null) return null;
            conv.recordToEvent(rec, 0);
            return store.getByMsgId(msgId);
        } catch (Exception e) {
            return null;
        }
    }

    /** 请求里带了 channel_id 的话，这条消息必须真属于那个频道。 */
    void validateChannel(JSONObject p, int messageId) {
        String channelId = p.optString("channel_id", "");
        if (channelId.isEmpty()) return; // accepted for older local callers
        MsgStore.Rec rec = store.get(messageId);
        if (rec == null) return;
        boolean privateChannel = Codec.isPrivateChannel(channelId);
        long peer = Codec.channelPeer(channelId);
        boolean matchesType = rec.chatType == (privateChannel ? QQClient.CT_C2C : QQClient.CT_GROUP);
        if (peer == 0 || !matchesType || (rec.peerUin != 0 && rec.peerUin != peer)) {
            throw ApiError.notFound("message not found in channel: " + channelId);
        }
    }

    /** {@link #require} 加上频道校验：绝大多数按 message_id 操作的 API 都是这个形状。 */
    MsgStore.Rec requireInChannel(JSONObject p) {
        MsgStore.Rec rec = require(p.optString("message_id", ""), p);
        validateChannel(p, rec.id);
        return rec;
    }
}
