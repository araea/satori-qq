package com.satori.qq.core;

import com.satori.qq.Cfg;
import com.satori.qq.L;
import com.satori.qq.qq.Convert;
import com.satori.qq.qq.QQClient;
import com.satori.qq.qq.Ref;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONArray;
import org.json.JSONObject;

/** QQ 内核推来的回调 → Satori 事件：消息、撤回、成员变动、申请、群列表变化。 */
final class Inbound implements QQClient.Listener {
    /** 一条记录走完投递流程的结果。 */
    private enum Outcome { EMITTED, ECHO, DUPLICATE, EMPTY, UNREADABLE }

    private static final long[] SELF_SEND_RETRY_MS = {100, 250, 500, 1000, 2000, 4000};

    private final Cfg cfg;
    private final QQClient qq;
    private final MsgStore store;
    private final Convert conv;
    private final Identity identity;
    private final Directory directory;
    private final History history;
    private final Reactions reactions;
    private final Requests requests;
    private final Events events;
    private final Sender sender;

    /** 已成功投递的 msgId，防内核重复推送。 */
    private final Set<Long> emitted = ConcurrentHashMap.newKeySet();
    private final Set<String> seenMemberChanges = ConcurrentHashMap.newKeySet();
    private final Set<Long> selfSendPolling = ConcurrentHashMap.newKeySet();

    // 入站侧的计数。事件收不到时先看这几个：能区分「记录压根没进来」（回调计数不动）、
    // 「进来了但没转成事件」（有计数没有 emit）与「转了但客户端没消费」（有 emit）。
    private final AtomicLong recv = new AtomicLong();
    private final AtomicLong add = new AtomicLong();
    private final AtomicLong update = new AtomicLong();
    private final AtomicLong grayTip = new AtomicLong();
    private final AtomicLong emit = new AtomicLong();
    private final AtomicLong notice = new AtomicLong();
    private final AtomicLong manualSelf = new AtomicLong();

    Inbound(Cfg cfg, QQClient qq, MsgStore store, Convert conv, Identity identity, Directory directory,
            History history, Reactions reactions, Requests requests, Events events, Sender sender) {
        this.cfg = cfg;
        this.qq = qq;
        this.store = store;
        this.conv = conv;
        this.identity = identity;
        this.directory = directory;
        this.history = history;
        this.reactions = reactions;
        this.requests = requests;
        this.events = events;
        this.sender = sender;
    }

    void reset() {
        emitted.clear();
        seenMemberChanges.clear();
    }

    JSONObject stats() throws Exception {
        return new JSONObject()
                .put("recv", recv.get())
                .put("add", add.get())
                .put("update", update.get())
                .put("gray_tip", grayTip.get())
                .put("emitted", emit.get())
                .put("notices", notice.get())
                .put("manual_self", manualSelf.get());
    }

    /** 在 QQ 界面里手打的「自己的消息」以什么身份递给客户端；缺省用一个稳定的、非 QQ 的 id。 */
    String manualSelfUserId(long self) {
        return cfg.manualSelfUserId.isEmpty() ? "qq-client:" + self : cfg.manualSelfUserId;
    }

    // ------------------------------------------------------------------ 消息

    @Override public void onRecvMsgs(List<?> records) {
        if (records == null) return;
        recv.addAndGet(records.size());
        for (Object rec : records) {
            try {
                Outcome outcome = deliver(rec, null);
                if (isSelf(rec) && retryable(outcome)) pollSelfSend(rec);
            } catch (Throwable t) {
                L.e("onRecvMsgs", t);
            }
        }
    }

    @Override public void onAddSendMsg(Object rec) {
        if (rec == null) return;
        add.incrementAndGet();
        try {
            if (retryable(deliver(rec, null))) pollSelfSend(rec);
        } catch (Throwable t) {
            L.e("onAddSendMsg", t);
        }
    }

    @Override public void onMsgUpdates(List<?> records) {
        if (records == null) return;
        update.addAndGet(records.size());
        for (Object record : records) {
            try {
                if (hasGrayTip(record)) grayTip.incrementAndGet();
                reactions.emitChanges(record);
                // Updates also carry reactions and edits to old records. Only a self-authored
                // candidate seen through add/recv can be a newly typed message. This prevents
                // an update to an old self-authored message from being replayed as new input.
                if (isSelf(record)) {
                    long msgId = Ref.asLong(field(record, "msgId"));
                    if (msgId != 0 && selfSendPolling.contains(msgId) && !retryable(deliver(record, null))) {
                        selfSendPolling.remove(msgId);
                    }
                }
            } catch (Throwable t) {
                L.e("onMsgUpdates", t);
            }
        }
    }

    private static boolean retryable(Outcome outcome) {
        return outcome == Outcome.EMPTY || outcome == Outcome.UNREADABLE;
    }

    /** 内核有时会先推空壳再推正文；空壳不占位，避免正文被 dedupe 掉。 */
    private static boolean deliverable(JSONObject ev) {
        if (!"message".equals(ev.optString("post_type"))) return true;
        if (!ev.optString("raw_message", "").trim().isEmpty()) return true;
        JSONArray segments = ev.optJSONArray("message");
        if (segments == null) return false;
        for (int i = 0; i < segments.length(); i++) {
            JSONObject seg = segments.optJSONObject(i);
            if (seg == null) continue;
            if ("text".equals(seg.optString("type"))) {
                JSONObject data = seg.optJSONObject("data");
                if (data != null && !data.optString("text", "").trim().isEmpty()) return true;
            } else if (!seg.optString("type", "").isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private Outcome deliver(Object rec, QQClient.MsgListResult hist) {
        long self = identity.selfUin();
        try {
            Object inner = Convert.unwrapRecord(rec);
            reactions.observe(inner);
            long msgId = Ref.asLong(qq.ref.get(inner, "msgId"));
            long senderUin = Ref.asLong(qq.ref.get(inner, "senderUin"));

            // 机器人 API 发出的回声：Sender 已登记 msgId。
            if (msgId != 0 && sender.isEcho(msgId)) return Outcome.ECHO;
            // 已完整投递过（内核重复推送同一条）。
            if (msgId != 0 && emitted.contains(msgId)) return Outcome.DUPLICATE;

            JSONObject ev = conv.recordToEvent(rec, self);
            // Preserve notices (recall, poke, member changes) returned by Convert.
            // Synthesis is only a fallback for an otherwise unparseable message record.
            if (ev == null) ev = history.synthesizeInbound(rec, hist);
            if (ev != null && self != 0) ev.put("self_id", self);
            history.fillEmptyText(ev, rec);
            if (hist != null) history.fillEmptyTextFromMap(ev, hist);
            if (ev == null) return Outcome.UNREADABLE;
            if (!deliverable(ev)) return Outcome.EMPTY;
            if (self != 0 && senderUin == self) markManualSelf(ev, self);

            String noticeType = ev.optString("notice_type", "");
            if ("group_recall".equals(noticeType) || "friend_recall".equals(noticeType)) {
                long mid = ev.optLong("qq_msg_id", 0);
                if (mid == 0) mid = ev.optLong("message_id", 0);
                if (mid != 0 && !events.firstRecall(mid)) return Outcome.DUPLICATE;
            }
            if (msgId != 0) {
                emitted.add(msgId);
                if (emitted.size() > 8000) emitted.clear();
            }
            emit.incrementAndGet();
            if (!noticeType.isEmpty()) notice.incrementAndGet();
            if (ev.optBoolean("manual_self", false)) manualSelf.incrementAndGet();
            events.emit(ev);
            L.d("event -> " + ev.optString("post_type") + "/"
                    + ev.optString("message_type", noticeType) + " from " + ev.optLong("user_id"));
            return Outcome.EMITTED;
        } catch (Throwable t) {
            L.e("tryEmitInboundMsg", t);
            return Outcome.UNREADABLE;
        }
    }

    private void markManualSelf(JSONObject ev, long self) throws Exception {
        if (!cfg.manualSelfMessages) return;
        ev.put("satori_user_id", manualSelfUserId(self))
                .put("manual_self", true)
                .put("actual_user_id", String.valueOf(self));
    }

    // ------------------------------------------------------------------ 自己发的消息

    private Object field(Object rec, String name) {
        return qq.ref.get(Convert.unwrapRecord(rec), name);
    }

    private boolean isSelf(Object rec) {
        long self = identity.selfUin();
        return self != 0 && Ref.asLong(field(rec, "senderUin")) == self;
    }

    /** 记录里带不带灰条元素（elementType 8）——戳一戳、禁言、成员变动都从这儿来。 */
    private boolean hasGrayTip(Object rec) {
        try {
            if (field(rec, "elements") instanceof List<?> elements) {
                for (Object e : elements) {
                    if (e != null && Ref.asInt(qq.ref.get(e, "elementType")) == 8) return true;
                }
            }
        } catch (Throwable ignore) {
            // 读不出元素就当没有灰条
        }
        return false;
    }

    private String peerOf(Object rec) {
        String peerUid = Ref.asStr(field(rec, "peerUid"));
        return peerUid.isEmpty() ? String.valueOf(Ref.asLong(field(rec, "peerUin"))) : peerUid;
    }

    /**
     * 自己在 QQ 界面里发的消息，内核先推空壳再补正文（群里经常走 onMsgUpdates 才完整）；
     * 只有空壳需要轮询，完整的 UI 消息走同步的快路径。
     */
    private void pollSelfSend(Object rec) {
        try {
            if (!isSelf(rec)) return;
            long msgId = Ref.asLong(field(rec, "msgId"));
            if (msgId == 0 || !selfSendPolling.add(msgId)) return;
            pollSelfSend(msgId, Ref.asInt(field(rec, "chatType")), peerOf(rec), 0);
        } catch (Throwable t) {
            L.e("scheduleSelfSendEmit", t);
        }
    }

    /** One outstanding retry per message; successful messages stop immediately. */
    private void pollSelfSend(long msgId, int chatType, String peer, int attempt) {
        if (attempt >= SELF_SEND_RETRY_MS.length) {
            selfSendPolling.remove(msgId);
            return;
        }
        Workers.scheduler().schedule(() -> {
            boolean retry = false;
            try {
                if (emitted.contains(msgId) || sender.isEcho(msgId)) return;
                Object fetched = qq.fetchRecord(chatType, peer, msgId);
                QQClient.MsgListResult hist = null;
                if (fetched == null && chatType == QQClient.CT_GROUP) {
                    hist = qq.getHistory(chatType, peer, 0, 20, true);
                    if (hist.records != null) {
                        for (Object candidate : hist.records) {
                            if (Ref.asLong(qq.ref.get(candidate, "msgId")) == msgId) {
                                fetched = candidate;
                                break;
                            }
                        }
                    }
                }
                retry = fetched == null || retryable(deliver(fetched, hist));
            } catch (Throwable t) {
                retry = true;
                L.e("selfSendPoll", t);
            } finally {
                if (retry && attempt + 1 < SELF_SEND_RETRY_MS.length) pollSelfSend(msgId, chatType, peer, attempt + 1);
                else selfSendPolling.remove(msgId);
            }
        }, SELF_SEND_RETRY_MS[attempt], TimeUnit.MILLISECONDS);
    }

    // ------------------------------------------------------------------ 撤回

    @Override public void onRecall(int type, String info, long time) {
        try {
            recalled(type, info, time);
        } catch (Throwable t) {
            L.e("onRecall", t);
        }
    }

    /**
     * Android 9.3.50: onMsgRecall(chatType, peerUid, msgSeqOrMsgId).
     * Desktop NapCat uses the same shape. JSON info is still accepted if present.
     */
    private void recalled(int type, String info, long third) throws Exception {
        if (info == null || info.isEmpty()) return;
        long msgId = 0, peer = 0, user = 0, operator = 0, msgSeq = 0;
        boolean group = type == QQClient.CT_GROUP;
        if (info.charAt(0) == '{') {
            JSONObject j = new JSONObject(info);
            msgId = j.optLong("msgId", j.optLong("msg_id", 0));
            msgSeq = j.optLong("msgSeq", j.optLong("msg_seq", 0));
            peer = j.optLong("peerUin", j.optLong("groupCode", j.optLong("group_id", 0)));
            user = j.optLong("senderUin", j.optLong("user_id", 0));
            operator = j.optLong("operatorUin", j.optLong("operator_id", 0));
            String uid = j.optString("peerUid", j.optString("operatorUid", ""));
            if (peer == 0 && !uid.isEmpty()) peer = Ids.parse(uid);
            if (j.has("chatType")) group = j.optInt("chatType") == QQClient.CT_GROUP;
        } else {
            msgSeq = third;
            msgId = third;
            peer = Ids.parse(info);
        }
        MsgStore.Rec rec = msgId != 0 ? store.getByMsgId(msgId) : null;
        if (rec == null) rec = store.findByPeerSeq(type, peer, info, msgSeq);
        int storeId = rec != null ? rec.id : store.idOfMsgId(msgId);
        if (rec != null) {
            if (peer == 0) peer = rec.peerUin;
            if (user == 0) user = rec.senderUin;
            group = rec.chatType == QQClient.CT_GROUP;
        }
        if (storeId == 0 && msgId == 0 && peer == 0) return;
        if (storeId == 0 && msgId != 0) {
            MsgStore.Rec stub = new MsgStore.Rec();
            stub.chatType = group ? QQClient.CT_GROUP : QQClient.CT_C2C;
            stub.peerUin = peer;
            stub.peerUid = info;
            stub.msgId = msgId;
            stub.msgSeq = msgSeq;
            stub.senderUin = user;
            storeId = store.put(stub);
        }
        events.recalled(group, peer, user, operator == 0 ? user : operator, storeId, msgId);
    }

    // ------------------------------------------------------------------ 申请、成员、群

    @Override public void onBuddyReq(Object info) {
        if (info == null) return;
        try {
            if (qq.ref.get(info, "buddyReqs") instanceof List<?> list) {
                for (Object req : list) {
                    JSONObject ev = requests.friendEvent(req);
                    if (ev != null) events.emit(ev);
                }
            }
        } catch (Throwable t) {
            L.e("onBuddyReq", t);
        }
    }

    @Override public void onGroupNotifies(List<?> notifies) {
        if (notifies == null) return;
        for (Object notify : notifies) {
            try {
                JSONObject ev = requests.groupEvent(notify);
                if (ev != null) events.emit(ev);
            } catch (Throwable t) {
                L.e("onGroupNotifies", t);
            }
        }
    }

    @Override public void onMemberListChange(Object change) {
        if (change == null) return;
        try {
            long groupId = Ref.asLong(qq.ref.get(change, "groupCode"));
            Object typeEnum = qq.ref.get(change, "changeType");
            String typeName = typeEnum == null ? "" : String.valueOf(qq.ref.call(typeEnum, "name"));
            if (!(qq.ref.get(change, "infos") instanceof Map<?, ?> infos) || groupId == 0) return;
            boolean joined = typeName.contains("ADD");
            if (!joined && !typeName.contains("REMOVE")) return;
            long now = System.currentTimeMillis() / 1000;
            long self = identity.selfUin();
            for (Object member : infos.values()) {
                if (member == null) continue;
                long uin = Ref.asLong(qq.ref.get(member, "uin"));
                String uid = Ref.asStr(qq.ref.get(member, "uid"));
                if (uin == 0) continue;
                if (!uid.isEmpty()) store.learnUid(uin, uid);
                if (!seenMemberChanges.add(groupId + ":" + uin + ":" + (joined ? "add" : "rm"))) continue;
                if (seenMemberChanges.size() > 8000) seenMemberChanges.clear();
                events.emit(joined
                        ? Notices.groupIncrease(self, now, groupId, uin, self)
                        : Notices.groupDecrease(self, now, groupId, uin, self, true));
            }
        } catch (Throwable t) {
            L.e("onMemberListChange", t);
        }
    }

    @Override public void onGroupListUpdate(Object updateType, List<?> groups) {
        if (groups == null) return;
        for (Object info : groups) {
            try {
                directory.rememberGroup(Ref.asLong(qq.ref.get(info, "groupCode")), Ref.asStr(qq.ref.get(info, "groupName")));
            } catch (Throwable ignore) {
                // 记不下群名不影响事件
            }
        }
        if (groups.isEmpty()) return;
        String update = Ref.enumName(updateType).toUpperCase(Locale.ROOT);
        String kind = changeKind(update);
        if (kind == null || ("updated".equals(kind) && groups.size() > 3)) return;
        for (Object info : groups) {
            try {
                long groupId = Ref.asLong(qq.ref.get(info, "groupCode"));
                if (groupId != 0) events.guildChanged(kind, groupId, Ref.asStr(qq.ref.get(info, "groupName")));
            } catch (Throwable t) {
                L.e("onGroupListUpdate " + update, t);
            }
        }
    }

    /** 群列表更新的种类；整表同步、初始化一类的不是「群变了」，返回 null。 */
    private static String changeKind(String update) {
        if (containsAny(update, "INIT", "SYNC", "REFRESH", "RELOAD", "LOAD", "ALL", "FULL")) return null;
        if (containsAny(update, "DELETE", "REMOVE", "DEL", "QUIT", "EXIT")) return "removed";
        if (containsAny(update, "ADD", "INSERT", "JOIN")) return "added";
        return "updated";
    }

    private static boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) return true;
        }
        return false;
    }
}
