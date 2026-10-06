package com.satori.qq.core;

import com.satori.qq.L;
import com.satori.qq.qq.Convert;
import com.satori.qq.qq.Media;
import com.satori.qq.qq.QQClient;
import com.satori.qq.qq.Ref;
import com.satori.qq.satori.Codec;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 读消息：{@code message.get}、{@code message.list}、聊天截图，以及把内核记录或本地快照补成
 * 事件形状的那些整形函数。
 */
final class History {
    /** 一页历史里的一条：整形好的条目，加上排序与游标要用的内核字段。 */
    private record Row(JSONObject item, long msgId, long time, long seq, String text) {}

    /** 一次翻页过程中攒下的东西。 */
    private static final class Page {
        final List<Row> rows = new ArrayList<>();
        final Map<String, String> texts = new HashMap<>();
    }

    private final QQClient qq;
    private final MsgStore store;
    private final Convert conv;
    private final Identity identity;
    private final Directory directory;
    private final Lookup lookup;
    private final Resources resources;
    /** 最近一页历史里内核给出的 CQ 文本快照，{@code message.get} 在记录空壳时拿它兜底。 */
    private volatile Map<String, String> recentTexts = Map.of();

    History(QQClient qq, MsgStore store, Convert conv, Identity identity, Directory directory,
            Lookup lookup, Resources resources) {
        this.qq = qq;
        this.store = store;
        this.conv = conv;
        this.identity = identity;
        this.directory = directory;
        this.lookup = lookup;
        this.resources = resources;
    }

    void reset() { recentTexts = Map.of(); }

    // ------------------------------------------------------------------ message.get

    JSONObject message(JSONObject p) throws Exception {
        MsgStore.Rec rec = lookup.requireInChannel(p);
        JSONObject ob = onebot(rec.id);
        JSONObject ev = Codec.toSatoriEvent(ob.put("post_type", "message").put("self_id", identity.selfUin()),
                identity.loginSlim(), 0, identity.assetBase());
        JSONObject msg = ev == null ? null : ev.optJSONObject("message");
        if (msg == null) return new JSONObject();
        if (msg.optString("content", "").isEmpty()) {
            String text = snapshotContent(msg.optString("id", ""), rec);
            if (!text.isEmpty()) msg.put("content", text);
        }
        if (rec.msgTime > 0) msg.put("created_at", rec.msgTime * 1000L);
        return msg;
    }

    /** 一条本地登记过的消息，整形成 OneBot 风格的消息 JSON（Satori 事件由它再转一道）。 */
    JSONObject onebot(int messageId) throws Exception {
        MsgStore.Rec r = store.get(messageId);
        if (r == null) throw ApiError.notFound("message not found: " + messageId);
        String peer = r.peer();
        String liveText = qq.peekRecordText(r.msgRecord);
        if (r.msgId != 0 && (r.msgRecord == null || !hasContent(r.msgRecord))) {
            Object fetched = qq.fetchRecord(r.chatType, peer, r.msgId);
            String fetchedText = qq.peekRecordText(fetched);
            if (fetched != null && (r.msgRecord == null || !fetchedText.isEmpty())) {
                r.msgRecord = fetched;
                if (!fetchedText.isEmpty()) liveText = fetchedText;
            }
        }
        if ((liveText == null || liveText.isEmpty()) && r.msgId != 0 && (r.content == null || r.content.isEmpty())) {
            Object dbRec = findInDatabase(r.chatType, peer, r.msgId);
            if (dbRec != null) {
                r.msgRecord = dbRec;
                liveText = qq.peekRecordText(dbRec);
            }
        }
        if (!liveText.isEmpty() && (r.content == null || r.content.isEmpty())) r.content = liveText;
        JSONObject ev = r.msgRecord == null ? null : conv.recordToEvent(r.msgRecord, 0);
        if (ev != null) fillEmptyText(ev, r.msgRecord);
        if (ev == null || "notice".equals(ev.optString("post_type"))) ev = fromStored(r);
        if (ev == null) throw ApiError.failed("cannot render message");

        String mid = Codec.publicMessageId(ev);
        if (mid.isEmpty() && r.msgId != 0) mid = String.valueOf(r.msgId);
        if (mid.isEmpty()) mid = String.valueOf(messageId);
        long when = r.msgTime > 0 ? r.msgTime : Codec.eventTime(ev);
        if (when <= 0 && r.msgRecord != null) {
            when = qq.ref.getLong(Convert.unwrapRecord(r.msgRecord), "msgTime");
            if (when > 10_000_000_000L) when /= 1000L;
        }
        if (when > 0) r.msgTime = when;

        JSONObject d = new JSONObject().put("time", when);
        if (when > 0) d.put("msg_time", String.valueOf(when));
        d.put("message_type", ev.optString("message_type"))
                .put("message_id", mid)
                .put("real_id", mid);
        if (ev.has("qq_msg_id")) d.put("qq_msg_id", ev.opt("qq_msg_id"));
        else if (r.msgId != 0) d.put("qq_msg_id", r.msgId);
        d.put("user_id", ev.optLong("user_id", r.senderUin));
        if (ev.has("group_id") || r.chatType == QQClient.CT_GROUP) {
            d.put("group_id", ev.optLong("group_id", r.peerUin));
            directory.attachGroupName(d);
        }
        d.put("sender", ev.optJSONObject("sender")).put("message", ev.optJSONArray("message"));
        String text = ev.optString("raw_message", "");
        if (text.isEmpty()) text = snapshotText(mid, r);
        if (!text.isEmpty()) {
            d.put("raw_message", text);
            JSONArray segments = d.optJSONArray("message");
            if (segments == null || segments.length() == 0) {
                try {
                    d.put("message", Codec.cqToSegments(text));
                } catch (Exception ignore) {
                    // 转不出消息段就只留 raw_message
                }
            }
            if (r.content == null || r.content.isEmpty()) r.content = text;
        }
        return d;
    }

    private Object findInDatabase(int chatType, String peer, long msgId) {
        if (msgId == 0 || peer == null || peer.isEmpty()) return null;
        try {
            QQClient.MsgListResult db = qq.getLatestDbMsgs(chatType, peer, 40);
            if (db == null || db.records == null) return null;
            for (Object rec : db.records) {
                if (rec == null) continue;
                Object inner = Convert.unwrapRecord(rec);
                if (Ref.asLong(qq.ref.get(inner, "msgId")) == msgId) return inner;
            }
        } catch (Throwable ignore) {
            // 数据库里找不到就当没有
        }
        return null;
    }

    /** 记录里除了空文本之外有没有实质内容（有元素就是有）。 */
    private boolean hasContent(Object rec) {
        try {
            if (!(qq.ref.get(rec, "elements") instanceof List<?> elements)) return false;
            for (Object e : elements) {
                if (e == null) continue;
                int type = Ref.asInt(qq.ref.get(e, "elementType"));
                if (type == 1) {
                    Object text = qq.ref.get(e, "textElement");
                    String content = text == null ? "" : Ref.asStr(qq.ref.get(text, "content"));
                    if (content != null && !content.isEmpty()) return true;
                } else if (type != 0) {
                    return true;
                }
            }
        } catch (Throwable ignore) {
            // 读不出元素就当没有内容
        }
        return false;
    }

    // ------------------------------------------------------------------ message.list

    synchronized JSONObject list(JSONObject p) throws Exception {
        String channelId = p.optString("channel_id", "");
        if (channelId.isEmpty() || Codec.channelPeer(channelId) == 0) {
            throw ApiError.badRequest("missing or invalid channel_id");
        }
        int limit = Math.max(1, Math.min(100, p.optInt("limit", 20)));
        long next = Ids.parse(p.optString("next", "0"));
        String direction = p.optString("direction", "before").toLowerCase(Locale.ROOT);
        String order = p.optString("order", "asc").toLowerCase(Locale.ROOT);
        if (!List.of("before", "after", "around").contains(direction)) throw ApiError.badRequest("invalid direction");
        if (!List.of("asc", "desc").contains(order)) throw ApiError.badRequest("invalid order");
        if (next == 0 && !"before".equals(direction)) throw ApiError.badRequest("direction requires next cursor");
        boolean group = !Codec.isPrivateChannel(channelId);
        long peer = Codec.channelPeer(channelId);

        Page page = new Page();
        if ("around".equals(direction)) {
            int older = Math.max(1, limit / 2);
            collect(group, peer, next, older, true, "before", page);
            collect(group, peer, next, Math.max(1, limit - older), false, "after", page);
        } else {
            collect(group, peer, next, limit, "before".equals(direction), direction, page);
        }
        recentTexts = Collections.unmodifiableMap(page.texts);

        List<Row> sorted = new ArrayList<>(page.rows);
        sorted.sort(Comparator.comparingLong(Row::seq));
        if (sorted.size() > limit) {
            int from = "after".equals(direction) ? 0 : sorted.size() - limit;
            sorted = new ArrayList<>(sorted.subList(from, from + limit));
        }
        if ("desc".equals(order)) Collections.reverse(sorted);

        JSONArray data = new JSONArray();
        long minSeq = Long.MAX_VALUE, maxSeq = 0;
        for (Row row : sorted) {
            JSONObject item = row.item();
            item.put("post_type", "message").put("self_id", identity.selfUin());
            if (group) {
                item.put("group_id", peer);
                directory.attachGroupName(item);
                if (item.optLong("user_id", 0) == 0) {
                    JSONObject sender = item.optJSONObject("sender");
                    if (sender != null) item.put("user_id", sender.optLong("user_id", 0));
                }
            } else if (item.optLong("user_id", 0) == 0) {
                item.put("user_id", peer);
            }
            JSONObject ev = Codec.toSatoriEvent(item, identity.loginSlim(), 0, identity.assetBase());
            JSONObject src = ev == null ? null : ev.optJSONObject("message");
            if (src == null) continue;
            JSONObject msg = Json.copy(src);
            if (msg.optString("content", "").isEmpty() && !row.text().isEmpty()) {
                msg.put("content", Codec.fromCqText(row.text(), identity.assetBase()));
            }
            if (msg.optString("content", "").isEmpty()) {
                String text = snapshotContent(String.valueOf(row.msgId()), store.getByMsgId(row.msgId()));
                if (!text.isEmpty()) msg.put("content", text);
            }
            if (row.time() > 0) msg.put("created_at", row.time() * 1000L);
            // QQ NT history cursor, also used by the bounded screenshot range reader.
            msg.put("message_seq", row.seq());
            data.put(msg);
            if (row.seq() > 0) {
                minSeq = Math.min(minSeq, row.seq());
                maxSeq = Math.max(maxSeq, row.seq());
            }
        }
        JSONObject out = new JSONObject().put("data", data);
        if (maxSeq > 0) {
            if ("around".equals(direction)) {
                out.put("prev", String.valueOf(minSeq)).put("next", String.valueOf(maxSeq));
            } else {
                String cursor = String.valueOf("before".equals(direction) ? minSeq : maxSeq);
                out.put("prev", cursor).put("next", cursor);
            }
        }
        return out;
    }

    private void collect(boolean group, long peer, long cursor, int count, boolean queryOrder,
                         String direction, Page page) throws Exception {
        if (group) {
            if (peer == 0) throw ApiError.badRequest("missing group_id");
            collect(qq.getHistory(QQClient.CT_GROUP, String.valueOf(peer), cursor, count, queryOrder),
                    cursor, direction, QQClient.CT_GROUP, peer, String.valueOf(peer), page);
        } else {
            if (peer == 0) throw ApiError.badRequest("missing user_id");
            String uid = directory.uidFor(0, peer);
            collect(qq.getHistory(QQClient.CT_C2C, uid, cursor, count, queryOrder),
                    cursor, direction, QQClient.CT_C2C, peer, uid, page);
        }
    }

    private void collect(QQClient.MsgListResult hist, long cursorSeq, String direction, int chatType,
                         long peerUin, String peerUid, Page page) throws Exception {
        if (hist.timedOut) throw ApiError.failed(hist.describe());
        if (chatType == QQClient.CT_GROUP) qq.ensureGroupMembers(peerUin);
        int collected = 0;
        int skipped = 0;
        if (hist.records != null) {
            for (Object rec : hist.records) {
                Object inner = Convert.unwrapRecord(rec);
                long recId = inner == null ? 0 : qq.ref.getLong(inner, "msgId");
                long recTime = inner == null ? 0 : qq.ref.getLong(inner, "msgTime");
                if (recTime > 10_000_000_000L) recTime /= 1000L;
                long recSeq = inner == null ? 0 : qq.ref.getLong(inner, "msgSeq");
                JSONObject ev = conv.recordToEvent(rec, 0);
                if (ev == null) {
                    if (++skipped == 1 && rec != null) L.e("recordToEvent skip class=" + rec.getClass().getName(), null);
                    continue;
                }
                fillEmptyText(ev, rec);
                fillEmptyTextFromMap(ev, hist);
                JSONObject item = entry(ev, cursorSeq, direction);
                if (item == null) continue;
                String text = hist.texts.get(recId != 0 ? String.valueOf(recId) : String.valueOf(item.opt("message_id")));
                if (text == null) text = hist.texts.get(item.optString("message_id", ""));
                item.put("raw_message", text == null ? "" : text);
                if (text != null) item.put("message", Json.textSegments(text));
                page.rows.add(new Row(item, recId, recTime, recSeq, text == null ? "" : text));
                collected++;
            }
        }
        if (collected == 0) {
            // 内核一条都没给（或都是空壳）：退到本进程登记过的这几条。
            for (MsgStore.Rec rec : store.listPeer(chatType, peerUin, peerUid, 20)) {
                JSONObject ev = rec.msgRecord == null ? null : conv.recordToEvent(rec.msgRecord, 0);
                if (ev == null || "notice".equals(ev.optString("post_type"))) ev = fromStored(rec);
                if (ev == null) continue;
                JSONObject item = entry(ev, cursorSeq, direction);
                if (item == null) continue;
                page.rows.add(new Row(item, rec.msgId, rec.msgTime, rec.msgSeq, rec.content == null ? "" : rec.content));
                collected++;
            }
        }
        if (collected == 0 && !hist.ok()) throw ApiError.failed("history failed: " + hist.describe());
        page.texts.putAll(hist.texts);
        persistTexts(hist, chatType, peerUin, peerUid);
    }

    /** 事件 → 历史条目；通知不是消息，游标之外的也不要。 */
    private JSONObject entry(JSONObject ev, long cursorSeq, String direction) throws Exception {
        if (ev == null || "notice".equals(ev.optString("post_type"))) return null;
        long seq = ev.optLong("message_seq");
        if (cursorSeq > 0 && "before".equals(direction) && seq >= cursorSeq) return null;
        if (cursorSeq > 0 && "after".equals(direction) && seq <= cursorSeq) return null;
        ev.put("self_id", identity.selfUin());
        long uid = ev.optLong("user_id", 0);
        if (uid == 0) {
            JSONObject sender = ev.optJSONObject("sender");
            if (sender != null) uid = sender.optLong("user_id", 0);
        }
        String mid = Codec.publicMessageId(ev);
        if (mid.isEmpty()) mid = String.valueOf(ev.opt("message_id"));
        JSONObject item = new JSONObject()
                .put("time", ev.optLong("time"))
                .put("msg_time", ev.optString("msg_time", ev.optString("time", "")))
                .put("message_type", ev.optString("message_type"))
                .put("message_id", mid)
                .put("real_id", mid)
                .put("message_seq", seq)
                .put("user_id", uid)
                .put("sender", ev.optJSONObject("sender"))
                .put("message", ev.optJSONArray("message"))
                .put("raw_message", ev.optString("raw_message"));
        if (ev.has("qq_msg_id")) item.put("qq_msg_id", ev.opt("qq_msg_id"));
        if (ev.has("group_id")) {
            item.put("group_id", ev.optLong("group_id"));
            directory.attachGroupName(item);
        }
        return item;
    }

    /** 内核历史给了文本的消息，登记进本地存储，之后 {@code message.get} 还认得。 */
    private void persistTexts(QQClient.MsgListResult hist, int chatType, long peerUin, String peerUid) {
        if (hist == null || hist.texts == null || hist.texts.isEmpty()) return;
        for (Map.Entry<String, String> entry : hist.texts.entrySet()) {
            String text = entry.getValue();
            long id = Ids.parse(entry.getKey());
            if (text == null || text.isEmpty() || id == 0) continue;
            MsgStore.Rec rec = store.getByMsgId(id);
            if (rec == null) {
                rec = new MsgStore.Rec();
                rec.chatType = chatType;
                rec.peerUin = peerUin;
                rec.peerUid = peerUid;
                rec.msgId = id;
                rec.content = text;
                store.put(rec);
            } else if (rec.content == null || rec.content.isEmpty()) {
                rec.content = text;
            }
        }
    }

    // ------------------------------------------------------------------ 聊天截图

    /** An off-screen history rendering, not a pixel capture of QQ's conversation activity. */
    JSONObject screenshot(JSONObject p) throws Exception {
        String channel = p.optString("channel_id", "");
        if (Codec.channelPeer(channel) <= 0) throw ApiError.badRequest("missing or invalid channel_id");
        String startId = p.optString("start_message_id", "");
        String endId = p.optString("end_message_id", "");
        if (Ids.parse(startId) <= 0 || Ids.parse(endId) <= 0) {
            throw ApiError.badRequest("start_message_id and end_message_id must be QQ message IDs");
        }
        MsgStore.Rec start = lookup.require(startId, p);
        MsgStore.Rec end = lookup.require(endId, p);
        lookup.validateChannel(p, start.id);
        lookup.validateChannel(p, end.id);
        if (start.msgId != Ids.parse(startId) || end.msgId != Ids.parse(endId)) {
            throw ApiError.badRequest("range endpoints must be QQ message IDs, not sequence cursors");
        }
        long first = start.msgSeq, last = end.msgSeq;
        if (first <= 1 || last <= 0 || first > last || (first == last && !startId.equals(endId))) {
            throw ApiError.badRequest("range endpoints have missing or reversed message sequences");
        }
        ShotRange range = new ShotRange(startId, endId);
        long cursor = first - 1;
        for (int page = 0; page < 8 && !range.finished(); page++) {
            JSONObject history = list(new JSONObject().put("channel_id", channel)
                    .put("direction", "after").put("order", "asc")
                    .put("next", String.valueOf(cursor)).put("limit", 50));
            try {
                range.add(history.optJSONArray("data"));
            } catch (IllegalArgumentException e) {
                throw ApiError.badRequest(e.getMessage());
            }
            if (range.finished()) break;
            long next = Ids.parse(history.optString("next", "0"));
            if (next <= cursor || next >= last) {
                throw ApiError.notFound("end message not present in contiguous history pages");
            }
            cursor = next;
        }
        if (!range.finished()) throw ApiError.notFound("range incomplete in history pages");
        byte[] png;
        try {
            png = ChatShot.render(range.messages(), identity.selfUin(), channel);
        } catch (IllegalArgumentException e) {
            throw ApiError.badRequest(e.getMessage());
        }
        File file = Media.storeUpload(png, "chat.png", "image/png");
        return new JSONObject()
                .put("file", resources.publish("image", file, "chat.png"))
                .put("mime", "image/png")
                .put("count", range.messages().size())
                .put("width", 720)
                .put("height_limit", 8192)
                .put("rendering", "text_and_placeholders");
    }

    // ------------------------------------------------------------------ 补文本与整形

    /**
     * 快照有两种来源：本端 API 发出的消息存的是 Satori 元素串，内核历史存的是 CQ 文本。
     * 前者原样返回，后者按协议转成元素串。
     */
    private String snapshotContent(String msgId, MsgStore.Rec rec) {
        String text = snapshotText(msgId, rec);
        if (text.isEmpty()) return "";
        MsgStore.Rec r = rec != null ? rec : store.getByMsgId(Ids.parse(msgId));
        if (r != null && r.contentIsElements && text.equals(r.content)) return text;
        return Codec.fromCqText(text, identity.assetBase());
    }

    private String snapshotText(String msgId, MsgStore.Rec rec) {
        if (msgId != null && !msgId.isEmpty()) {
            String text = recentTexts.get(msgId);
            if (text != null && !text.isEmpty()) return text;
            if (rec == null) rec = store.getByMsgId(Ids.parse(msgId));
        }
        if (rec != null) {
            if (rec.content != null && !rec.content.isEmpty()) return rec.content;
            String peek = qq.peekRecordText(rec.msgRecord);
            if (peek != null && !peek.isEmpty()) {
                rec.content = peek;
                return peek;
            }
        }
        return "";
    }

    /** 事件里连文本都没有时，用记录里能读出来的文本补一段。 */
    void fillEmptyText(JSONObject ev, Object rec) {
        if (ev == null || hasText(ev)) return;
        setText(ev, qq.peekRecordText(rec));
    }

    void fillEmptyTextFromMap(JSONObject ev, QQClient.MsgListResult hist) {
        if (ev == null || hist == null || hist.texts == null || hist.texts.isEmpty() || hasText(ev)) return;
        String id = ev.optString("message_id", "");
        if (id.isEmpty() && ev.has("qq_msg_id")) id = String.valueOf(ev.opt("qq_msg_id"));
        setText(ev, hist.texts.get(id));
    }

    private static void setText(JSONObject ev, String text) {
        if (text == null || text.isEmpty()) return;
        try {
            ev.put("message", Json.textSegments(text)).put("raw_message", text);
        } catch (Exception ignore) {
            // 补不上就保持原样
        }
    }

    private static boolean hasText(JSONObject ev) {
        if (!ev.optString("raw_message", "").isEmpty()) return true;
        JSONArray segments = ev.optJSONArray("message");
        if (segments == null) return false;
        for (int i = 0; i < segments.length(); i++) {
            JSONObject seg = segments.optJSONObject(i);
            JSONObject data = seg == null ? null : seg.optJSONObject("data");
            if (data != null && (!data.optString("text", "").isEmpty() || !data.optString("file", "").isEmpty())) {
                return true;
            }
        }
        return false;
    }

    /** 内核解析不出的入站记录：用本地登记过的快照，或记录里的文本拼一个消息事件。 */
    JSONObject synthesizeInbound(Object rec, QQClient.MsgListResult hist) throws Exception {
        Object inner = Convert.unwrapRecord(rec);
        if (inner == null) return null;
        long msgId = qq.ref.getLong(inner, "msgId");
        long msgSeq = qq.ref.getLong(inner, "msgSeq");
        long msgTime = qq.ref.getLong(inner, "msgTime");
        if (msgTime > 10_000_000_000L) msgTime /= 1000L;
        long senderUin = qq.ref.getLong(inner, "senderUin");
        long peerUin = qq.ref.getLong(inner, "peerUin");
        int chatType = Ref.asInt(qq.ref.get(inner, "chatType"));
        if (senderUin == 0) return null;
        if (msgId != 0) {
            MsgStore.Rec stored = store.getByMsgId(msgId);
            if (stored != null) {
                JSONObject ev = fromStored(stored);
                if (ev != null && !ev.optString("raw_message", "").isEmpty()) return ev;
            }
        }
        String text = "";
        if (msgId != 0 && hist != null && hist.texts != null) {
            String t = hist.texts.get(String.valueOf(msgId));
            if (t != null) text = t;
        }
        if (text.isEmpty()) text = qq.peekRecordText(rec);
        if (text.isEmpty()) return null;
        boolean group = chatType == QQClient.CT_GROUP;
        String nick = Ref.asStr(qq.ref.get(inner, "sendNickName"));
        String card = Ref.asStr(qq.ref.get(inner, "sendMemberName"));
        JSONObject sender = new JSONObject()
                .put("user_id", senderUin)
                .put("nickname", nick == null ? "" : nick);
        if (group) sender.put("card", card == null ? "" : card);
        JSONObject ev = new JSONObject()
                .put("post_type", "message")
                .put("message_type", group ? "group" : "private")
                .put("sub_type", group ? "normal" : "friend")
                .put("message_id", msgId != 0 ? String.valueOf(msgId) : "0")
                .put("qq_msg_id", msgId)
                .put("user_id", senderUin)
                .put("sender", sender)
                .put("message", Codec.toSegments(text))
                .put("raw_message", text)
                .put("message_seq", msgSeq);
        if (msgTime > 0) ev.put("time", msgTime).put("msg_time", String.valueOf(msgTime));
        if (group) ev.put("group_id", peerUin);
        else ev.put("peer_id", peerUin);
        return ev;
    }

    /** 从本地登记的记录拼一个消息事件（没有内核记录可用时的退路）。 */
    private JSONObject fromStored(MsgStore.Rec r) throws Exception {
        if (r == null || (r.msgId == 0 && r.senderUin == 0)) return null;
        boolean group = r.chatType == QQClient.CT_GROUP;
        JSONObject sender = new JSONObject()
                .put("user_id", r.senderUin)
                .put("nickname", r.senderUin == identity.selfUin() ? qq.selfNick() : "");
        JSONArray segments = new JSONArray();
        if (r.content != null && !r.content.isEmpty()) {
            try {
                segments = Codec.toSegments(r.content);
            } catch (Exception ignore) {
                // 内容转不成消息段就留空
            }
        }
        JSONObject ev = new JSONObject()
                .put("post_type", "message")
                .put("message_type", group ? "group" : "private")
                .put("message_id", r.msgId != 0 ? String.valueOf(r.msgId) : String.valueOf(r.id))
                .put("qq_msg_id", r.msgId)
                .put("user_id", r.senderUin)
                .put("sender", sender)
                .put("time", System.currentTimeMillis() / 1000)
                .put("message_seq", r.msgSeq)
                .put("message", segments);
        if (group) ev.put("group_id", r.peerUin);
        else ev.put("peer_id", r.peerUin);
        return ev;
    }
}
