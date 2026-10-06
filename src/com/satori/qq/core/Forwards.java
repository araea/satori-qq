package com.satori.qq.core;

import android.graphics.BitmapFactory;
import com.satori.qq.Cfg;
import com.satori.qq.L;
import com.satori.qq.packet.LongMsg;
import com.satori.qq.packet.PacketSvc;
import com.satori.qq.qq.Convert;
import com.satori.qq.qq.Media;
import com.satori.qq.qq.QQClient;
import com.satori.qq.qq.Ref;
import com.satori.qq.satori.Codec;
import com.satori.qq.satori.Elements;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 合并转发：发出（原生卡片或 long-msg 假卡片）与读回（按 res_id 下载，或按内核父消息取）。
 *
 * <p>发送有两条可交付的路径，由 {@code forward_mode} 选：
 * <ul>
 *   <li><b>native</b> — 内层消息发到机器人自己的私聊（绝不进目的地），再经内核
 *       {@code multiForwardMsg} 打包。QQNT 9.3.55 实测：群里只看到一张卡片，点开正常。</li>
 *   <li><b>fake</b> — 节点经 {@code SsoSendLongMsg} 上传，再作为 type-16/13/ark 卡片投递。
 *       不会有任何东西落进聊天，但在 QQNT 9.3.55 上那张卡片是死胡同：能渲染，点不开。</li>
 * </ul>
 * {@code auto}（默认）先试 native，只有 native 彻底失败才退到 fake。
 */
final class Forwards {
    private final Cfg cfg;
    private final QQClient qq;
    private final MsgStore store;
    private final Convert conv;
    private final Identity identity;
    private final Directory directory;
    private final Sender sender;

    Forwards(Cfg cfg, QQClient qq, MsgStore store, Convert conv, Identity identity, Directory directory,
             Sender sender) {
        this.cfg = cfg;
        this.qq = qq;
        this.store = store;
        this.conv = conv;
        this.identity = identity;
        this.directory = directory;
        this.sender = sender;
    }

    /**
     * acumen (and go-cqhttp-style clients) send merge-forward as {@code send_msg}
     * whose message array is {@code node} segments, not {@code send_*_forward_msg}.
     */
    static boolean looksLikeForward(Object message) {
        if (!(message instanceof JSONArray segments)) return false;
        boolean anyNode = false;
        for (int i = 0; i < segments.length(); i++) {
            JSONObject seg = segments.optJSONObject(i);
            if (seg == null) continue;
            String type = seg.optString("type", "");
            if ("node".equals(type)) anyNode = true;
            else if (!type.isEmpty()) return false;
        }
        return anyNode;
    }

    // ------------------------------------------------------------------ 发出

    /** Exactly one of groupId/userId is non-zero. */
    JSONObject send(long groupId, long userId, Object messages) throws Exception {
        if (groupId == 0 && userId == 0) throw ApiError.badRequest("missing group_id/user_id");
        List<LongMsg.Node> nodes = parseNodes(messages, groupId != 0);
        if (nodes.isEmpty()) throw ApiError.badRequest("empty forward messages");
        String mode = cfg.forwardMode == null ? "auto" : cfg.forwardMode;
        if ("fake".equals(mode)) return sendFake(groupId, userId, nodes);
        JSONObject nativeSent = sendNative(groupId, userId, messages);
        if (nativeSent != null) return nativeSent;
        if ("native".equals(mode)) throw ApiError.failed("native forward failed (self-chat scaffolding); see logs");
        return sendFake(groupId, userId, nodes);
    }

    /**
     * Fake path: upload nodes to the long-msg store, then send a card referencing the resId.
     *
     * <p>Confirmed unusable on QQNT 9.3.55 (2026-09-02, group 280183116): the card renders in
     * the chat but tapping it fails to open — the viewer resolves content via kernel
     * {@code getMultiMsg(msgId)}, which only works for cards built by {@code multiForwardMsg}
     * from real local messages. Kept as an explicit {@code forward_mode=fake} opt-in for
     * re-testing on other clients; {@code auto} prefers the native path.
     */
    private JSONObject sendFake(long groupId, long userId, List<LongMsg.Node> nodes) throws Exception {
        String selfUid = qq.resolveUid(identity.selfUin());
        if (groupId == 0 && (selfUid == null || selfUid.isEmpty())) throw ApiError.failed("cannot resolve self uid");
        if (selfUid == null) selfUid = "";
        String fileName = UUID.randomUUID().toString();
        PacketSvc.Result r = qq.packets().sendSso(LongMsg.CMD, LongMsg.buildUploadReq(groupId, selfUid, nodes, fileName));
        if (!r.ok()) throw ApiError.failed("forward upload failed: " + r.describe());
        String resId = LongMsg.parseResId(r.body);
        if (resId == null || resId.isEmpty()) throw ApiError.failed("forward upload: no resId in reply");
        LongMsg.Card card = LongMsg.buildCard(resId, nodes, groupId != 0, fileName);
        JSONObject sent = sendCard(groupId, userId, conv.toMultiForward(card.json, resId, card.fileName), "type16");
        if (sent == null) sent = sendCard(groupId, userId, conv.toStructLongMsg(card.xml, resId), "type13");
        if (sent == null) {
            JSONArray ark = new JSONArray().put(new JSONObject().put("type", "json")
                    .put("data", new JSONObject().put("data", card.json)));
            sent = groupId != 0 ? sender.toGroup(groupId, ark, "") : sender.toUser(userId, ark, "");
        }
        return sent.put("res_id", resId).put("forward_id", resId).put("filename", fileName);
    }

    /**
     * Android QQNT opens merge-forward via kernel getMultiMsg(msgId). That only works for cards
     * created by {@code multiForwardMsg} from real local messages, not fake SsoSendLongMsg ark/16.
     *
     * <p>The inner scaffolding messages are sent to the bot's own self-chat, never to the
     * destination: multiForwardMsg only needs their msgIds, and this way the group never sees
     * them — not even as recallable flashes. They simply stay in the self-chat as private
     * by-products of building the card.
     */
    private JSONObject sendNative(long groupId, long userId, Object messages) throws Exception {
        int dstChatType = groupId != 0 ? QQClient.CT_GROUP : QQClient.CT_C2C;
        String dstPeer = groupId != 0 ? String.valueOf(groupId) : directory.uidOf(userId);
        if (dstPeer.isEmpty()) return null;
        long self = identity.selfUin();
        String selfUid = qq.resolveUid(self);
        if (selfUid == null || selfUid.isEmpty()) {
            L.e("native forward: cannot resolve self uid", null);
            return null;
        }
        List<Long> ids = new ArrayList<>();
        List<String> names = new ArrayList<>();
        JSONArray arr = messages instanceof JSONArray a ? a : new JSONArray();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject seg = arr.optJSONObject(i);
            JSONObject d = seg == null ? null : seg.optJSONObject("data");
            if (d == null) continue;
            Object content = d.opt("content");
            // Elements are built for the self-chat they land in (C2C), not the destination.
            QQClient.SendResult sr = sender.sendSegments(QQClient.CT_C2C, selfUid, content == null ? "" : content, true);
            if (sr == null) continue;
            if (sr.code != 0 || sr.msgId == 0) {
                L.e("native forward inner send failed code=" + sr.code + " " + sr.msg, null);
                return null;
            }
            ids.add(sr.msgId);
            names.add(d.optString("nickname", d.optString("name", "")));
            sender.afterSend(sr, QQClient.CT_C2C, self, selfUid);
        }
        if (ids.isEmpty()) return null;
        // Anything the destination chat already had is older than this card.
        long baselineSeq = newestSeq(dstChatType, dstPeer);
        QQClient.SendResult fw = qq.multiForward(QQClient.CT_C2C, selfUid, dstChatType, dstPeer,
                ids, names);
        if (fw.code != 0) {
            L.e("multiForwardMsg failed code=" + fw.code + " " + fw.msg, null);
            return null;
        }
        JSONObject found = null;
        for (int attempt = 0; attempt < 10 && found == null; attempt++) {
            try {
                Thread.sleep(attempt == 0 ? 800 : 500);
            } catch (InterruptedException ignore) {
                // 被打断就直接去查，不再多等
            }
            found = findCard(dstChatType, dstPeer, groupId, userId, ids, baselineSeq);
        }
        if (found != null) return found;
        L.e("multiForward card not in history yet inners=" + ids.size(), null);
        return new JSONObject().put("native_forward", true).put("message_id", 0);
    }

    /**
     * Newest merge-forward in the destination chat. Preferred match is a record that parsed
     * into a {@code forward} segment; when the kernel stored the card without one, the newest
     * own message past {@code baselineSeq} is taken instead — the card has to be registered
     * either way, because without a stored message id it cannot be recalled.
     */
    private JSONObject findCard(int chatType, String peer, long groupId, long userId, List<Long> innerIds,
                                long baselineSeq) throws Exception {
        // Merge-forward cards are authored by self and may only surface through the DB/AIO
        // caches, not the plain getMsgsIncludeSelf view. Reuse getHistory (the same query
        // message.list relies on) so the freshly sent card is found reliably.
        QQClient.MsgListResult hist = qq.getHistory(chatType, peer, 0, 20, true);
        if (hist.records == null) return null;
        // Prefer a record whose elements are a merge-forward (type 16) or struct long msg (13);
        // fall back to the newest qualifying record so a card we can't structurally detect is
        // still registered and therefore recallable.
        Object bestRec = null;
        long bestId = 0, bestSeq = 0;
        for (int i = hist.records.size() - 1; i >= 0; i--) {
            Object rec = hist.records.get(i);
            long msgId = recordLong(rec, "msgId");
            if (msgId == 0 || innerIds.contains(msgId)) continue;
            long seq = recordLong(rec, "msgSeq");
            if (baselineSeq > 0 && seq <= baselineSeq) continue;
            if (isForwardRecord(rec)) {
                return register(rec, msgId, seq, chatType, groupId, userId, peer, forwardData(rec));
            }
            if (bestRec == null || seq > bestSeq) {
                bestRec = rec;
                bestId = msgId;
                bestSeq = seq;
            }
        }
        return bestRec == null ? null : register(bestRec, bestId, bestSeq, chatType, groupId, userId, peer, null);
    }

    /** 历史记录里某个数值字段；记录拆不开就是 0。 */
    private long recordLong(Object rec, String field) {
        Object inner = Convert.unwrapRecord(rec);
        if (inner == null) return 0;
        try {
            return qq.ref.getLong(inner, field);
        } catch (Throwable ignore) {
            return 0;
        }
    }

    /** True when a history record's elements are a merge-forward (16) or struct long msg (13). */
    private boolean isForwardRecord(Object rec) {
        Object inner = Convert.unwrapRecord(rec);
        if (inner == null) return false;
        try {
            if (!(qq.ref.get(inner, "elements") instanceof List<?> elements)) return false;
            for (Object e : elements) {
                if (e == null) continue;
                int type = Ref.asInt(qq.ref.get(e, "elementType"));
                if (type == 16 || type == 13) return true;
            }
        } catch (Throwable ignore) {
            // 读不出元素就不是转发卡片
        }
        return false;
    }

    /** Best-effort forward-segment data (for res_id); null when the record can't be parsed. */
    private JSONObject forwardData(Object rec) {
        try {
            JSONObject ev = conv.recordToEvent(rec, 0);
            JSONArray msg = ev == null ? null : ev.optJSONArray("message");
            if (msg == null) return null;
            for (int i = 0; i < msg.length(); i++) {
                JSONObject s = msg.optJSONObject(i);
                if (s != null && "forward".equals(s.optString("type"))) return s.optJSONObject("data");
            }
        } catch (Throwable ignore) {
            // 解析不了就不带 res_id
        }
        return null;
    }

    /** Newest msgSeq in a chat; the baseline for spotting messages that arrive later. */
    private long newestSeq(int chatType, String peer) {
        // Include self: the merge-forward card is self-authored and must count toward baseline.
        // getHistory mirrors message.list so it sees the card even when plain getMsgs would not.
        QQClient.MsgListResult hist = qq.getHistory(chatType, peer, 0, 5, true);
        if (hist.records == null) return 0;
        long max = 0;
        for (Object rec : hist.records) max = Math.max(max, recordLong(rec, "msgSeq"));
        return max;
    }

    /**
     * Store the card so it resolves like any other message. The returned id is the QQ msgId,
     * which is what {@code message.delete} and {@code message.get} accept.
     */
    private JSONObject register(Object rec, long msgId, long seq, int chatType, long groupId, long userId,
                                String peer, JSONObject data) throws Exception {
        MsgStore.Rec stored = new MsgStore.Rec();
        stored.chatType = chatType;
        stored.peerUin = groupId != 0 ? groupId : userId;
        stored.peerUid = groupId != 0 ? "" : peer;
        stored.msgId = msgId;
        stored.msgSeq = seq;
        stored.senderUin = identity.selfUin();
        stored.msgRecord = rec;
        JSONObject out = new JSONObject()
                .put("message_id", store.put(stored))
                .put("qq_msg_id", msgId)
                .put("native_forward", true);
        if (data != null) {
            String resId = data.optString("id", "");
            if (!resId.isEmpty()) out.put("res_id", resId).put("forward_id", resId);
            if (data.has("filename")) out.put("filename", data.optString("filename"));
            if (data.has("element_type")) out.put("element_type", data.optInt("element_type"));
        }
        if (msgId != 0) qq.prefetchForward(chatType, peer, msgId);
        return out;
    }

    /** 把一张卡片元素发给群或好友；发不出去返回 null，让调用方换下一种卡片。 */
    private JSONObject sendCard(long groupId, long userId, List<Object> elements, String label) throws Exception {
        if (elements == null || elements.isEmpty()) {
            L.e("multiForward " + label + " element empty", null);
            return null;
        }
        int chatType = groupId != 0 ? QQClient.CT_GROUP : QQClient.CT_C2C;
        long peerUin = groupId != 0 ? groupId : userId;
        String peer;
        if (groupId != 0) {
            peer = String.valueOf(groupId);
        } else {
            peer = directory.uidOf(userId);
            if (peer.isEmpty()) throw ApiError.notFound("cannot resolve uid for user " + userId);
        }
        QQClient.SendResult sr = sender.track(chatType, peer, elements);
        if (sr.code != 0) {
            L.e("multiForward " + label + " send failed code=" + sr.code + " " + sr.msg, null);
            return null;
        }
        JSONObject sent = sender.afterSend(sr, chatType, peerUin, groupId != 0 ? "" : peer);
        qq.prefetchForward(chatType, peer, sr.msgId);
        return sent;
    }

    // ------------------------------------------------------------------ 读回

    /** internal/get_forward：按 res_id 下载，或 {@code native:<父消息 ID>} 走内核的那份。 */
    JSONObject get(JSONObject p) throws Exception {
        String id = p == null ? "" : p.optString("id", p.optString("message_id", ""));
        if (id.startsWith("native:")) return getNative(id, p);
        if (id.isEmpty()) throw ApiError.badRequest("missing id (forward res_id)");
        String selfUid = qq.resolveUid(identity.selfUin());
        if (selfUid == null || selfUid.isEmpty()) throw ApiError.failed("cannot resolve self uid");
        PacketSvc.Result r = qq.packets().sendSso(LongMsg.RECV_CMD, LongMsg.buildDownloadReq(selfUid, id));
        if (!r.ok()) throw ApiError.failed("get_forward_msg failed: " + r.describe());
        List<LongMsg.Node> nodes = LongMsg.parseDownload(r.body);
        if (nodes.isEmpty()) throw ApiError.notFound("forward not found or empty: " + id);
        JSONArray data = new JSONArray();
        for (LongMsg.Node node : nodes) {
            String nickname = node.senderName == null ? "" : node.senderName;
            Elements.El author = Elements.empty("author");
            author.attrs.put("id", String.valueOf(node.senderUin));
            author.attrs.put("name", nickname);
            Elements.El msg = Elements.empty("message");
            msg.children.add(author);
            msg.children.addAll(Elements.parse(Codec.fromSegments(segments(node), identity.assetBase())));
            data.put(new JSONObject()
                    .put("id", "")
                    .put("content", msg.toString(false))
                    .put("user", Codec.user(node.senderUin, nickname, "")));
        }
        return new JSONObject().put("data", data);
    }

    /**
     * The kernel copy of a merge-forward: real per-node ids, images and timestamps, unlike the
     * resId path whose fake-node protocol drops NT media entirely. `getMultiMsg` only needs the
     * contact plus the parent msgId, so a caller that knows the channel can still reach it after
     * our own LRU dropped the parent (module restart, or just an old message).
     */
    private JSONObject getNative(String id, JSONObject p) throws Exception {
        long kernelMsgId = Ids.parse(id.substring("native:".length()));
        if (kernelMsgId == 0) throw ApiError.badRequest("invalid native forward id");
        MsgStore.Rec parent = store.getByMsgId(kernelMsgId);
        int chatType = parent == null ? 0 : parent.chatType;
        String peer = parent == null ? null : parent.peerUid;
        if (parent != null && (peer == null || peer.isEmpty()) && parent.chatType == QQClient.CT_GROUP) {
            peer = String.valueOf(parent.peerUin);
        }
        if (peer == null || peer.isEmpty()) {
            String channelId = p == null ? "" : p.optString("channel_id", "");
            long peerUin = Codec.channelPeer(channelId);
            if (peerUin == 0) throw ApiError.notFound("native forward is not cached; pass channel_id");
            boolean group = !Codec.isPrivateChannel(channelId);
            chatType = group ? QQClient.CT_GROUP : QQClient.CT_C2C;
            peer = group ? String.valueOf(peerUin) : directory.uidFor(0, peerUin);
        }
        QQClient.MsgListResult result = qq.getMultiMsg(chatType, peer, kernelMsgId);
        if (!result.ok()) throw ApiError.failed("get native forward failed: " + result.describe());
        if (result.records == null || result.records.isEmpty()) throw ApiError.notFound("native forward is empty");
        JSONArray data = new JSONArray();
        for (Object record : result.records) {
            JSONObject ob = conv.recordToEvent(record, 0);
            if (ob == null) continue;
            JSONObject event = Codec.toSatoriEvent(ob.put("post_type", "message").put("self_id", identity.selfUin()),
                    identity.loginSlim(), 0, identity.assetBase());
            JSONObject message = event == null ? null : event.optJSONObject("message");
            if (message != null) data.put(message);
        }
        if (data.length() == 0) throw ApiError.notFound("native forward has no readable messages");
        return new JSONObject().put("data", data);
    }

    /** 下载回来的节点内容 → 内部消息段；图片与文件顺手登记成资源。 */
    private JSONArray segments(LongMsg.Node node) throws Exception {
        JSONArray content = new JSONArray();
        if (node.segs.isEmpty()) return content.put(Json.textSegment(node.text == null ? "" : node.text));
        for (LongMsg.Seg s : node.segs) {
            JSONObject data = new JSONObject();
            switch (s.type) {
                case "at" -> {
                    data.put("qq", s.qq == null || s.qq.isEmpty() ? "all" : s.qq);
                    if (s.text != null && !s.text.isEmpty()) data.put("name", s.text);
                    content.put(new JSONObject().put("type", "at").put("data", data));
                }
                case "face", "reply" -> content.put(new JSONObject().put("type", s.type)
                        .put("data", new JSONObject().put("id", s.id)));
                case "image" -> {
                    String id = s.file == null ? "" : s.file;
                    if (!id.isEmpty()) store.putResource("image", id, "", s.url, s.name, s.size);
                    data.put("file", id);
                    if (s.url != null && !s.url.isEmpty()) data.put("url", s.url);
                    if (s.size > 0) data.put("file_size", s.size);
                    content.put(new JSONObject().put("type", "image").put("data", data));
                }
                case "file" -> {
                    String id = s.file == null ? "" : s.file;
                    if (!id.isEmpty()) store.putResource("file", id, "", s.url, s.name, s.size);
                    data.put("file", id).put("file_id", id);
                    if (s.name != null && !s.name.isEmpty()) data.put("name", s.name);
                    if (s.size > 0) data.put("file_size", s.size);
                    if (s.busid > 0) data.put("busid", s.busid);
                    content.put(new JSONObject().put("type", "file").put("data", data));
                }
                default -> content.put(Json.textSegment(s.text == null ? "" : s.text));
            }
        }
        return content;
    }

    // ------------------------------------------------------------------ 节点编码

    private List<LongMsg.Node> parseNodes(Object messages, boolean group) {
        List<LongMsg.Node> out = new ArrayList<>();
        if (!(messages instanceof JSONArray arr)) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject seg = arr.optJSONObject(i);
            JSONObject d = seg == null ? null : seg.optJSONObject("data");
            if (d == null) continue;
            String type = seg.optString("type", "node");
            if (!type.isEmpty() && !"node".equals(type)) continue;
            LongMsg.Node node = new LongMsg.Node();
            node.senderUin = nodeUin(d);
            node.senderName = d.optString("name", d.optString("nickname", String.valueOf(node.senderUin)));
            node.time = d.optLong("time", 0);
            Object content = d.opt("content");
            node.text = plainText(content);
            encode(node, content, group);
            out.add(node);
        }
        return out;
    }

    /** acumen {@code node_custom} serializes user_id as a JSON string. */
    private long nodeUin(JSONObject d) {
        long uin = d.optLong("uin", d.optLong("user_id", 0));
        if (uin != 0) return uin;
        String raw = d.optString("uin", d.optString("user_id", ""));
        if (!raw.isEmpty()) {
            try {
                return Long.parseLong(raw.trim());
            } catch (NumberFormatException ignored) {
                // 不是数字就退回自己
            }
        }
        return identity.selfUin();
    }

    /** Encode node content into im_msg_body Elems (text/at/face/reply/image/file). */
    private void encode(LongMsg.Node n, Object content, boolean group) {
        if (content instanceof String text) {
            n.elems.add(LongMsg.elemText(text));
            return;
        }
        if (!(content instanceof JSONArray a)) {
            if (n.text != null && !n.text.isEmpty()) n.elems.add(LongMsg.elemText(n.text));
            return;
        }
        for (int i = 0; i < a.length(); i++) {
            JSONObject s = a.optJSONObject(i);
            if (s == null) {
                String t = a.optString(i);
                if (!t.isEmpty()) n.elems.add(LongMsg.elemText(t));
                continue;
            }
            JSONObject d = s.optJSONObject("data");
            if (d == null) d = new JSONObject();
            switch (s.optString("type", "text")) {
                case "text" -> n.elems.add(LongMsg.elemText(d.optString("text", "")));
                case "at" -> n.elems.add(atElem(d));
                case "face" -> n.elems.add(LongMsg.elemFace((int) Ids.parse(d.optString("id", "0"))));
                case "reply" -> {
                    String id = d.optString("id", "");
                    MsgStore.Rec rec = store.get((int) Ids.parse(id));
                    long seq = rec != null ? rec.msgSeq : Ids.parse(id);
                    n.elems.add(LongMsg.elemReply(seq, rec != null ? rec.senderUin : 0, 0,
                            rec != null ? rec.msgId : 0, rec != null ? rec.senderUid : "", "[回复]"));
                }
                case "image" -> n.elems.add(LongMsg.elemImage(image(d, group)));
                case "file" -> n.elems.add(LongMsg.elemFile(file(d)));
                default -> n.elems.add(LongMsg.elemText(plainText(new JSONArray().put(s))));
            }
        }
        if (n.elems.isEmpty()) n.elems.add(LongMsg.elemText(n.text == null ? "" : n.text));
    }

    private byte[] atElem(JSONObject d) {
        String target = d.optString("qq", "");
        boolean all = "all".equalsIgnoreCase(target);
        long uin = all ? 0 : Ids.parse(target);
        String uid = "";
        if (uin != 0) {
            try {
                uid = directory.uidOf(uin);
            } catch (Exception ignore) {
                // 解析不出 uid 就只带显示名
            }
        }
        return LongMsg.elemAt(d.optString("name", all ? "@全体成员" : "@" + target), all, uin, uid);
    }

    private LongMsg.Pic image(JSONObject d, boolean group) {
        LongMsg.Pic pic = new LongMsg.Pic();
        pic.group = group;
        String spec = d.optString("file", d.optString("file_id", ""));
        String url = d.optString("url", "");
        MsgStore.Resource res = store.getResource(spec);
        if (res != null) {
            if (url.isEmpty() && res.url != null) url = res.url;
            pic.size = (int) res.size;
            pic.fileName = res.name == null ? "" : res.name;
            pic.md5 = LongMsg.md5Hex(res.id);
            if (pic.md5 == null) pic.md5 = LongMsg.md5Hex(res.name);
        }
        if (pic.md5 == null) pic.md5 = LongMsg.md5Hex(spec);
        File local = Media.resolve(spec, url);
        if (local == null && res != null) local = Media.resolve(res.path, res.url);
        if (local != null) {
            if (pic.md5 == null) pic.md5 = LongMsg.md5Of(local);
            pic.size = (int) Math.min(Integer.MAX_VALUE, local.length());
            if (pic.fileName.isEmpty()) pic.fileName = local.getName();
            try {
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(local.getAbsolutePath(), bounds);
                if (bounds.outWidth > 0) pic.width = bounds.outWidth;
                if (bounds.outHeight > 0) pic.height = bounds.outHeight;
            } catch (Throwable ignore) {
                // 量不出尺寸就不带
            }
        }
        pic.origUrl = url;
        if (pic.md5 == null) pic.md5 = new byte[0];
        return pic;
    }

    private LongMsg.FileRef file(JSONObject d) {
        LongMsg.FileRef f = new LongMsg.FileRef();
        f.fileId = Ids.firstNonEmpty(d.optString("file_id", ""), d.optString("file", ""));
        f.name = d.optString("name", "");
        f.size = d.optLong("file_size", d.optLong("size", 0));
        f.busId = d.optInt("busid", d.optInt("bus_id", 102));
        MsgStore.Resource res = store.getResource(f.fileId);
        if (res != null) {
            if (f.name.isEmpty() && res.name != null) f.name = res.name;
            if (f.size == 0) f.size = res.size;
            f.md5 = LongMsg.md5Hex(res.id);
        }
        if (f.md5 == null) f.md5 = LongMsg.md5Hex(f.fileId);
        return f;
    }

    /** Flatten node content (string or segment array) to display text for a forward node. */
    private static String plainText(Object content) {
        if (content == null) return "";
        if (content instanceof String text) return text;
        if (!(content instanceof JSONArray a)) return String.valueOf(content);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < a.length(); i++) {
            JSONObject s = a.optJSONObject(i);
            if (s == null) {
                sb.append(a.optString(i));
                continue;
            }
            JSONObject sd = s.optJSONObject("data");
            switch (s.optString("type", "")) {
                case "text" -> sb.append(sd == null ? "" : sd.optString("text", ""));
                case "at" -> sb.append(sd == null ? "" : sd.optString("name", "@" + sd.optString("qq", "")));
                case "face" -> sb.append("[表情]");
                case "image" -> sb.append("[图片]");
                case "file" -> sb.append("[文件]");
                case "reply" -> sb.append("[回复]");
                case "json", "lightapp" -> sb.append("[卡片]");
                default -> { }
            }
        }
        return sb.toString();
    }
}
