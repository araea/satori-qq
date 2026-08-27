package com.onebot.qq.core;

import com.onebot.qq.Cfg;
import com.onebot.qq.L;
import com.onebot.qq.net.WsConn;
import com.onebot.qq.net.WsServer;
import com.onebot.qq.packet.LongMsg;
import com.onebot.qq.packet.PacketSvc;
import com.onebot.qq.packet.Pb;
import com.onebot.qq.qq.Convert;
import com.onebot.qq.qq.QQClient;
import com.onebot.qq.qq.Ref;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/** OneBot 11 protocol hub: action dispatch (WS in) + event push (WS out). */
public final class OneBotHub implements WsServer.Handler, QQClient.Listener {
    private final Cfg cfg;
    private final QQClient qq;
    private final MsgStore store;
    private final Convert conv;
    private WsServer server;

    public OneBotHub(Cfg cfg, QQClient qq, MsgStore store) {
        this.cfg = cfg; this.qq = qq; this.store = store;
        this.conv = new Convert(qq, store);
    }

    public void start() {
        server = new WsServer(cfg, this);
        server.start();
        qq.setListener(this);
        startStatusMonitor();
    }

    private long selfUin() { try { return Long.parseLong(qq.selfUin()); } catch (Throwable t) { return 0; } }

    // ============ WS inbound: OneBot actions ============
    @Override public void onOpen(WsConn conn) {
        try {
            conn.send(lifecycle("connect").toString());
            conn.send(heartbeat(qq.isOnline()).toString());
        } catch (Throwable t) {
            L.e("send initial lifecycle", t);
        }
    }

    @Override public void onText(WsConn conn, String text) {
        JSONObject req;
        try { req = new JSONObject(text); } catch (Throwable t) { return; }
        String action = req.optString("action", "");
        Object echo = req.has("echo") ? req.opt("echo") : null;
        JSONObject params = req.optJSONObject("params");
        if (params == null) params = new JSONObject();
        try {
            Object data = dispatch(action, params);
            conn.send(ok(data, echo).toString());
        } catch (ApiError e) {
            conn.send(fail(e.code, e.getMessage(), echo).toString());
        } catch (Throwable t) {
            L.e("action " + action, t);
            conn.send(fail(1400, String.valueOf(t), echo).toString());
        }
    }

    private static final class ApiError extends RuntimeException {
        final int code; ApiError(int code, String msg) { super(msg); this.code = code; }
    }

    private Object dispatch(String action, JSONObject p) throws Exception {
        if ("get_status".equals(action)) return status(qq.isOnline());
        if ("get_version_info".equals(action)) return versionInfo();
        if ("can_send_image".equals(action) || "can_send_record".equals(action))
            return new JSONObject().put("yes", true);
        if ("clean_cache".equals(action))
            return new JSONObject().put("deleted", com.onebot.qq.qq.Media.cleanTemp());
        if ("set_restart".equals(action)) {
            scheduleRestart(Math.max(500, p.optInt("delay", 0)));
            return new JSONObject();
        }
        if (!qq.isOnline()) throw new ApiError(1500, "QQ kernel offline or not ready");
        switch (action) {
            case "get_login_info": {
                JSONObject d = new JSONObject();
                d.put("user_id", selfUin());
                d.put("nickname", qq.selfNick());
                return d;
            }
            case "send_msg": {
                String mt = p.optString("message_type", "");
                long gid = p.optLong("group_id", 0);
                long uid = p.optLong("user_id", 0);
                boolean group = mt.equals("group") || (mt.isEmpty() && gid != 0);
                return group ? sendGroup(gid, p.opt("message")) : sendPrivate(uid, p.opt("message"));
            }
            case "send_group_msg":   return sendGroup(p.optLong("group_id", 0), p.opt("message"));
            case "send_private_msg": return sendPrivate(p.optLong("user_id", 0), p.opt("message"));
            case "send_group_forward_msg":
                return sendForward(p.optLong("group_id", 0), 0, p.opt("messages"));
            case "send_private_forward_msg":
                return sendForward(0, p.optLong("user_id", 0), p.opt("messages"));
            case "send_forward_msg": {
                long gid = p.optLong("group_id", 0);
                return gid != 0 ? sendForward(gid, 0, p.opt("messages"))
                        : sendForward(0, p.optLong("user_id", 0), p.opt("messages"));
            }
            case "delete_msg": {
                recall(p.optInt("message_id", 0));
                return new JSONObject();
            }
            case "get_msg": return getMsg(p.optInt("message_id", 0));
            case "get_group_msg_history":
                return getGroupMsgHistory(p.optLong("group_id", 0),
                        p.optLong("message_seq", 0), p.optInt("count", 20));
            case "get_group_list":        return getGroupList();
            case "get_friend_list":       return getFriendList();
            case "get_stranger_info":     return getStrangerInfo(p.optLong("user_id", 0));
            case "get_group_member_info": return getGroupMemberInfo(p.optLong("group_id", 0), p.optLong("user_id", 0));
            case "get_group_member_list": return getGroupMemberList(p.optLong("group_id", 0));
            case "set_msg_emoji_like": {
                setEmojiLike(p.optInt("message_id", 0), p.optLong("emoji_id", 0), p.optBoolean("set", true));
                return new JSONObject();
            }
            case "get_group_info": return groupInfoJson(p.optLong("group_id", 0));
            case "set_group_kick": {
                long g = p.optLong("group_id", 0), u = p.optLong("user_id", 0);
                qq.kickMember(g, uidFor(g, u), p.optBoolean("reject_add_request", false));
                return new JSONObject();
            }
            case "set_group_ban": {
                long g = p.optLong("group_id", 0), u = p.optLong("user_id", 0);
                qq.banMember(g, uidFor(g, u), p.optInt("duration", 1800));
                return new JSONObject();
            }
            case "set_group_whole_ban":
                qq.wholeBan(p.optLong("group_id", 0), p.optBoolean("enable", true));
                return new JSONObject();
            case "set_group_card": {
                long g = p.optLong("group_id", 0), u = p.optLong("user_id", 0);
                qq.setCard(g, uidFor(g, u), p.optString("card", ""));
                return new JSONObject();
            }
            case "set_group_admin": {
                long g = p.optLong("group_id", 0), u = p.optLong("user_id", 0);
                qq.setAdmin(g, uidFor(g, u), p.optBoolean("enable", true));
                return new JSONObject();
            }
            case "set_group_leave":
                qq.quitGroup(p.optLong("group_id", 0));
                return new JSONObject();
            case "set_group_name": {
                long groupId = p.optLong("group_id", 0);
                if (groupId == 0) throw new ApiError(1400, "missing group_id");
                if (!qq.setGroupName(groupId, p.optString("group_name", "")))
                    throw new ApiError(1500, "group service not ready");
                return new JSONObject();
            }
            case "set_group_special_title": {
                long g = p.optLong("group_id", 0), u = p.optLong("user_id", 0);
                setGroupSpecialTitle(g, uidFor(g, u), p.optString("special_title", ""));
                return new JSONObject();
            }
            case "send_like":
                sendLike(p.optLong("user_id", 0), p.optInt("times", 1));
                return new JSONObject();
            case "get_forward_msg":
                return getForwardMsg(p.optString("id", p.optString("message_id", "")));
            case "upload_group_file":
                return sendGroup(p.optLong("group_id", 0), fileSeg(p));
            case "upload_private_file":
                return sendPrivate(p.optLong("user_id", 0), fileSeg(p));
            default:
                throw new ApiError(1404, "unknown action: " + action);
        }
    }

    /**
     * Merge-forward: upload fake nodes via SsoSendLongMsg, then send the multimsg card that
     * references the returned resId as an ordinary ark/json message. Exactly one of groupId/userId
     * is non-zero.
     */
    private JSONObject sendForward(long groupId, long userId, Object messages) throws Exception {
        if (groupId == 0 && userId == 0) throw new ApiError(1400, "missing group_id/user_id");
        List<LongMsg.Node> nodes = parseForwardNodes(messages);
        if (nodes.isEmpty()) throw new ApiError(1400, "empty forward messages");
        String selfUid = "";
        if (groupId == 0) {
            selfUid = qq.resolveUid(selfUin());
            if (selfUid == null || selfUid.isEmpty()) throw new ApiError(1500, "cannot resolve self uid");
        }
        byte[] req = LongMsg.buildUploadReq(groupId, selfUid, nodes);
        PacketSvc.Result r = qq.packets().sendSso(LongMsg.CMD, req);
        if (!r.ok()) throw new ApiError(1500, "forward upload failed: " + r.describe());
        String resId = LongMsg.parseResId(r.body);
        if (resId == null || resId.isEmpty()) throw new ApiError(1500, "forward upload: no resId in reply");
        String card = LongMsg.buildCardJson(resId, nodes);
        JSONArray msg = new JSONArray().put(new JSONObject().put("type", "json")
                .put("data", new JSONObject().put("data", card)));
        JSONObject sent = groupId != 0 ? sendGroup(groupId, msg) : sendPrivate(userId, msg);
        return sent.put("res_id", resId).put("forward_id", resId);
    }

    /** get_forward_msg: download a merged-forward by res_id via SsoRecvLongMsg, return its nodes. */
    private JSONObject getForwardMsg(String resId) throws Exception {
        if (resId == null || resId.isEmpty()) throw new ApiError(1400, "missing id (forward res_id)");
        String selfUid = qq.resolveUid(selfUin());
        if (selfUid == null || selfUid.isEmpty()) throw new ApiError(1500, "cannot resolve self uid");
        PacketSvc.Result r = qq.packets().sendSso(LongMsg.RECV_CMD, LongMsg.buildDownloadReq(selfUid, resId));
        if (!r.ok()) throw new ApiError(1500, "get_forward_msg failed: " + r.describe());
        List<LongMsg.Node> nodes = LongMsg.parseDownload(r.body);
        if (nodes.isEmpty()) throw new ApiError(1404, "forward not found or empty: " + resId);
        JSONArray messages = new JSONArray();
        for (LongMsg.Node n : nodes) {
            JSONArray content = new JSONArray().put(new JSONObject().put("type", "text")
                    .put("data", new JSONObject().put("text", n.text == null ? "" : n.text)));
            messages.put(new JSONObject().put("type", "node").put("data", new JSONObject()
                    .put("user_id", n.senderUin)
                    .put("nickname", n.senderName == null ? "" : n.senderName)
                    .put("time", n.time)
                    .put("content", content)));
        }
        return new JSONObject().put("messages", messages);
    }

    private List<LongMsg.Node> parseForwardNodes(Object messages) {
        List<LongMsg.Node> out = new java.util.ArrayList<>();
        if (!(messages instanceof JSONArray)) return out;
        JSONArray arr = (JSONArray) messages;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject seg = arr.optJSONObject(i);
            if (seg == null) continue;
            JSONObject d = seg.optJSONObject("data");
            if (d == null) continue;
            LongMsg.Node n = new LongMsg.Node();
            n.senderUin = d.optLong("uin", d.optLong("user_id", selfUin()));
            n.senderName = d.optString("name", d.optString("nickname", String.valueOf(n.senderUin)));
            n.text = extractText(d.opt("content"));
            out.add(n);
        }
        return out;
    }

    /** Flatten OneBot node content (string or segment array) to display text for a forward node. */
    private String extractText(Object content) {
        if (content == null) return "";
        if (content instanceof String) return (String) content;
        if (!(content instanceof JSONArray)) return String.valueOf(content);
        StringBuilder sb = new StringBuilder();
        JSONArray a = (JSONArray) content;
        for (int i = 0; i < a.length(); i++) {
            JSONObject s = a.optJSONObject(i);
            if (s == null) { sb.append(a.optString(i)); continue; }
            String t = s.optString("type", "");
            JSONObject sd = s.optJSONObject("data");
            switch (t) {
                case "text": sb.append(sd == null ? "" : sd.optString("text", "")); break;
                case "at": sb.append("@").append(sd == null ? "" : sd.optString("qq", "")); break;
                case "face": sb.append("[表情]"); break;
                case "image": sb.append("[图片]"); break;
                case "json": case "lightapp": sb.append("[卡片]"); break;
                default: break;
            }
        }
        return sb.toString();
    }

    /** Build a one-segment `file` message from upload_*_file params (file/url + name). */
    private JSONArray fileSeg(JSONObject p) throws Exception {
        JSONObject data = new JSONObject()
                .put("file", p.optString("file", p.optString("url", "")))
                .put("name", p.optString("name", ""));
        return new JSONArray().put(new JSONObject().put("type", "file").put("data", data));
    }

    private JSONObject sendGroup(long groupId, Object message) throws Exception {
        if (groupId == 0) throw new ApiError(1400, "missing group_id");
        java.util.ArrayList<Object> els = conv.toElements(message, QQClient.CT_GROUP);
        QQClient.SendResult r = qq.sendMsg(QQClient.CT_GROUP, String.valueOf(groupId), els);
        return afterSend(r, QQClient.CT_GROUP, groupId, "");
    }

    private JSONObject sendPrivate(long userId, Object message) throws Exception {
        if (userId == 0) throw new ApiError(1400, "missing user_id");
        String uid = store.uidOf(userId);
        if (uid == null || uid.isEmpty()) {
            uid = qq.resolveUid(userId);            // resolve uin -> uid via profile service
            if (uid != null && !uid.isEmpty()) store.learnUid(userId, uid);
        }
        if (uid == null || uid.isEmpty())
            throw new ApiError(1404, "cannot resolve uid for user " + userId);
        java.util.ArrayList<Object> els = conv.toElements(message, QQClient.CT_C2C);
        QQClient.SendResult r = qq.sendMsg(QQClient.CT_C2C, uid, els);
        return afterSend(r, QQClient.CT_C2C, userId, uid);
    }

    private JSONObject afterSend(QQClient.SendResult r, int chatType, long peerUin, String peerUid) throws Exception {
        if (r.code != 0) throw new ApiError(1500, "send failed (code=" + r.code + "): " + r.msg);
        MsgStore.Rec rec = new MsgStore.Rec();
        rec.chatType = chatType; rec.peerUin = peerUin; rec.peerUid = peerUid;
        rec.msgId = r.msgId; rec.senderUin = selfUin();
        int id = store.put(rec);
        return new JSONObject().put("message_id", id);
    }

    private void recall(int messageId) throws Exception {
        MsgStore.Rec r = store.get(messageId);
        if (r == null) throw new ApiError(1404, "message not found: " + messageId);
        Object msgService = qq.getMsgService();
        if (msgService == null) throw new ApiError(1500, "kernel not ready");
        Object contact = qq.ref.neu(QQClient.CONTACT, r.chatType, r.peerUid.isEmpty() ? String.valueOf(r.peerUin) : r.peerUid, "");
        java.util.ArrayList<Long> ids = new java.util.ArrayList<>();
        ids.add(r.msgId);
        Object cb = java.lang.reflect.Proxy.newProxyInstance(qq.ref.cl,
            new Class[]{qq.ref.cls(QQClient.IOPERATE_CB)}, (proxy, m, args) -> null);
        qq.ref.call(msgService, "recallMsg", contact, ids, cb);
    }

    private JSONObject getMsg(int messageId) throws Exception {
        MsgStore.Rec r = store.get(messageId);
        if (r == null || r.msgRecord == null) throw new ApiError(1404, "message not found: " + messageId);
        JSONObject ev = conv.recordToEvent(r.msgRecord, selfUin());
        if (ev == null) throw new ApiError(1500, "cannot render message");
        JSONObject d = new JSONObject();
        d.put("time", ev.optLong("time"));
        d.put("message_type", ev.optString("message_type"));
        d.put("message_id", messageId);
        d.put("real_id", messageId);
        d.put("sender", ev.optJSONObject("sender"));
        d.put("message", ev.optJSONArray("message"));
        return d;
    }

    private JSONObject getGroupMsgHistory(long groupId, long messageSeq, int count) throws Exception {
        if (groupId == 0) throw new ApiError(1400, "missing group_id");
        JSONArray messages = new JSONArray();
        for (Object rec : qq.getMsgs(QQClient.CT_GROUP, String.valueOf(groupId), messageSeq, count)) {
            JSONObject ev = conv.recordToEvent(rec, 0); // self=0 keeps messages sent by this account
            if (ev == null) continue;
            ev.put("self_id", selfUin());
            JSONObject item = new JSONObject()
                    .put("time", ev.optLong("time"))
                    .put("message_type", ev.optString("message_type"))
                    .put("message_id", ev.optInt("message_id"))
                    .put("real_id", ev.optInt("message_id"))
                    .put("sender", ev.optJSONObject("sender"))
                    .put("message", ev.optJSONArray("message"))
                    .put("raw_message", ev.optString("raw_message"));
            messages.put(item);
        }
        return new JSONObject().put("messages", messages);
    }

    private JSONArray getGroupList() {
        JSONArray arr = new JSONArray();
        for (Object gi : qq.getGroupList()) {
            try {
                JSONObject o = new JSONObject();
                o.put("group_id", Ref.asLong(qq.ref.get(gi, "groupCode")));
                o.put("group_name", Ref.asStr(qq.ref.get(gi, "groupName")));
                o.put("member_count", Ref.asInt(qq.ref.get(gi, "memberCount")));
                o.put("max_member_count", Ref.asInt(qq.ref.get(gi, "maxMember")));
                arr.put(o);
            } catch (Throwable ignore) {}
        }
        return arr;
    }

    private JSONArray getFriendList() throws Exception {
        JSONArray arr = new JSONArray();
        for (java.util.Map.Entry<String, Object> entry : qq.getFriendCoreInfos().entrySet()) {
            Object info = entry.getValue();
            long uin = Ref.asLong(qq.ref.get(info, "uin"));
            if (uin == 0) continue;
            String uid = Ref.asStr(qq.ref.get(info, "uid"));
            store.learnUid(uin, uid.isEmpty() ? entry.getKey() : uid);
            arr.put(new JSONObject()
                    .put("user_id", uin)
                    .put("nickname", Ref.asStr(qq.ref.get(info, "nick")))
                    .put("remark", Ref.asStr(qq.ref.get(info, "remark"))));
        }
        return arr;
    }

    private JSONObject getStrangerInfo(long userId) throws Exception {
        if (userId == 0) throw new ApiError(1400, "missing user_id");
        Object info = qq.getCoreInfo(userId);
        if (info == null) throw new ApiError(1404, "profile not found for user " + userId);
        String uid = Ref.asStr(qq.ref.get(info, "uid"));
        if (!uid.isEmpty()) store.learnUid(userId, uid);
        return new JSONObject()
                .put("user_id", userId)
                .put("nickname", Ref.asStr(qq.ref.get(info, "nick")))
                .put("sex", "unknown")
                .put("age", 0)
                .put("qid", "")
                .put("level", 0)
                .put("login_days", 0);
    }

    private JSONObject getGroupMemberInfo(long groupId, long userId) throws Exception {
        if (groupId == 0 || userId == 0) throw new ApiError(1400, "missing group_id/user_id");
        java.util.Map<String, Object> members = qq.getAllMembers(groupId);
        if (members == null) throw new ApiError(1500, "cannot fetch group members");
        for (Object mi : members.values()) {
            if (Ref.asLong(qq.ref.get(mi, "uin")) == userId) return memberJson(groupId, mi);
        }
        throw new ApiError(1404, "member " + userId + " not found in group " + groupId);
    }

    private JSONArray getGroupMemberList(long groupId) throws Exception {
        if (groupId == 0) throw new ApiError(1400, "missing group_id");
        java.util.Map<String, Object> members = qq.getAllMembers(groupId);
        if (members == null) throw new ApiError(1500, "cannot fetch group members");
        JSONArray arr = new JSONArray();
        for (Object mi : members.values()) arr.put(memberJson(groupId, mi));
        return arr;
    }

    private JSONObject memberJson(long groupId, Object mi) throws Exception {
        JSONObject o = new JSONObject();
        long uin = Ref.asLong(qq.ref.get(mi, "uin"));
        String card = Ref.asStr(qq.ref.get(mi, "cardName"));
        o.put("group_id", groupId);
        o.put("user_id", uin);
        o.put("nickname", Ref.asStr(qq.ref.get(mi, "nick")));
        o.put("card", card);
        o.put("sex", "unknown");
        o.put("age", 0);
        o.put("area", "");
        o.put("join_time", Ref.asInt(qq.ref.get(mi, "joinTime")));
        o.put("last_sent_time", Ref.asInt(qq.ref.get(mi, "lastSpeakTime")));
        o.put("level", String.valueOf(Ref.asInt(qq.ref.get(mi, "memberLevel"))));
        o.put("role", roleStr(qq.ref.get(mi, "role")));
        o.put("unfriendly", false);
        o.put("title", Ref.asStr(qq.ref.get(mi, "memberSpecialTitle")));
        o.put("title_expire_time", Ref.asLong(qq.ref.get(mi, "specialTitleExpireTime")));
        o.put("card_changeable", true);
        store.learnUid(uin, Ref.asStr(qq.ref.get(mi, "uid")));
        return o;
    }

    private String roleStr(Object roleEnum) {
        try {
            String n = String.valueOf(qq.ref.call(roleEnum, "name")).toUpperCase();
            if (n.contains("OWNER")) return "owner";
            if (n.contains("ADMIN")) return "admin";
        } catch (Throwable ignore) {}
        return "member";
    }

    private JSONObject groupInfoJson(long groupId) throws Exception {
        Object gi = qq.groupInfo(groupId);
        if (gi == null) throw new ApiError(1404, "group not found: " + groupId);
        JSONObject o = new JSONObject();
        o.put("group_id", Ref.asLong(qq.ref.get(gi, "groupCode")));
        o.put("group_name", Ref.asStr(qq.ref.get(gi, "groupName")));
        o.put("member_count", Ref.asInt(qq.ref.get(gi, "memberCount")));
        o.put("max_member_count", Ref.asInt(qq.ref.get(gi, "maxMember")));
        return o;
    }

    /** Resolve a uin to its uid for a group action: cache -> profile service -> group member list. */
    private String uidFor(long groupId, long uin) throws Exception {
        if (uin == 0) throw new ApiError(1400, "missing user_id");
        String uid = store.uidOf(uin);
        if (uid != null && !uid.isEmpty()) return uid;
        uid = qq.resolveUid(uin);
        if (uid != null && !uid.isEmpty()) { store.learnUid(uin, uid); return uid; }
        java.util.Map<String, Object> members = qq.getAllMembers(groupId);
        if (members != null) {
            for (Object mi : members.values()) {
                if (Ref.asLong(qq.ref.get(mi, "uin")) == uin) {
                    String u = Ref.asStr(qq.ref.get(mi, "uid"));
                    store.learnUid(uin, u);
                    return u;
                }
            }
        }
        throw new ApiError(1404, "cannot resolve uid for user " + uin);
    }

    private void setEmojiLike(int messageId, long emojiId, boolean set) throws Exception {
        MsgStore.Rec r = store.get(messageId);
        if (r == null) throw new ApiError(1404, "message not found: " + messageId);
        Object msgService = qq.getMsgService();
        if (msgService == null) throw new ApiError(1500, "kernel not ready");
        Object contact = qq.ref.neu(QQClient.CONTACT, r.chatType,
                r.peerUid == null || r.peerUid.isEmpty() ? String.valueOf(r.peerUin) : r.peerUid, "");
        long emojiType = emojiId < 9000 ? 1L : 2L;   // 1=QQ face, 2=unicode emoji
        Object cb = java.lang.reflect.Proxy.newProxyInstance(qq.ref.cl,
                new Class[]{qq.ref.cls("com.tencent.qqnt.kernel.nativeinterface.ISetMsgEmojiLikesCallback")},
                (proxy, m, a) -> null);
        // setMsgEmojiLikes(Contact, long msgSeq, String emojiId, long emojiType, boolean set, cb)
        qq.ref.call(msgService, "setMsgEmojiLikes", contact, r.msgSeq, String.valueOf(emojiId), emojiType, set, cb);
    }

    /** OidbSvcTrpcTcp.0x8FC_2: set (or clear) one member's special title. */
    private void setGroupSpecialTitle(long groupId, String targetUid, String title) {
        if (groupId == 0) throw new ApiError(1400, "missing group_id");
        // D8FCReqBody: 1=groupCode, repeated 3=MemberInfo; MemberInfo: 1=uid, 5=title bytes.
        // Match current Lagrange.Core behavior: OneBot's optional duration is not sent.
        byte[] member = Pb.w().string(1, targetUid).string(5, title == null ? "" : title)
                .toByteArray();
        byte[] body = Pb.w().varint(1, groupId).message(3, member).toByteArray();
        PacketSvc.Result result = qq.packets().sendOidb(0x8FC, 2, body);
        if (!result.ok()) {
            throw new ApiError(1500, "set special title failed: " + result.describe());
        }
    }

    /** OidbSvcTrpcTcp.0x7E5_104: like a user's profile card `times` times (server caps daily total). */
    /**
     * OidbSvcTrpcTcp.0x7E5_104: like a user's profile card `times` times.
     * Body per LagrangeGo: 11=targetUid(str), 12=source(71), 13=count; envelope isReserved=0.
     *
     * <p>Device note (QQ 9.3.50, 2026-08): the transport reaches the server and the command routes
     * to the like service, but the server rejects it with oidb=319 "[oidb] rule type not match
     * appid" for every source value. This is Tencent's appid/rule gating (the same 319 seen across
     * clients since ~2026-08), not a packet-format bug — a wrong command number would return 236
     * "cmd not found" instead. It may also compound with this account's existing risk-control state.
     * Left as the protocol-correct implementation; it should succeed once the appid is un-gated or on
     * a non-restricted account.</p>
     */
    private void sendLike(long userId, int times) throws Exception {
        if (userId == 0) throw new ApiError(1400, "missing user_id");
        if (times < 1) times = 1;
        String uid = qq.resolveUid(userId);
        if (uid == null || uid.isEmpty()) throw new ApiError(1404, "cannot resolve uid for user " + userId);
        store.learnUid(userId, uid);
        byte[] body = Pb.w().string(11, uid).varint(12, 71).varint(13, times).toByteArray();
        PacketSvc.Result result = qq.packets().sendOidb(0x7E5, 104, body, false);
        if (!result.ok()) {
            throw new ApiError(1500, "send_like failed: " + result.describe());
        }
    }

    // ============ QQ inbound: events ============
    private final java.util.Set<Long> seen = java.util.concurrent.ConcurrentHashMap.newKeySet();

    @Override public void onRecvMsgs(List<?> records) {
        long self = selfUin();
        for (Object rec : records) {
            try {
                long msgId = Ref.asLong(qq.ref.get(rec, "msgId"));
                if (msgId != 0 && !seen.add(msgId)) continue; // dedupe
                if (seen.size() > 8000) seen.clear();
                JSONObject ev = conv.recordToEvent(rec, self);
                if (ev != null) {
                    server.broadcast(ev.toString());
                    L.d("event -> " + ev.optString("message_type") + " from " + ev.optLong("user_id"));
                }
            } catch (Throwable t) {
                L.e("onRecvMsgs", t);
            }
        }
    }

    @Override public void onRecall(int type, String info, long time) {
        // notice event (milestone 2: map to group_recall / friend_recall)
    }

    // ============ lifecycle / online status / heartbeat ============
    private void startStatusMonitor() {
        Thread t = new Thread(() -> {
            boolean previous = qq.isOnline();
            long interval = Math.max(1000L, cfg.heartbeatMs);
            long nextHeartbeat = System.currentTimeMillis() + interval;
            while (true) {
                try {
                    Thread.sleep(1000);
                    boolean online = qq.isOnline();
                    long now = System.currentTimeMillis();
                    if (online != previous) {
                        L.i("QQ kernel state -> " + (online ? "online" : "offline"));
                        if (server.connectionCount() > 0) {
                            server.broadcast(lifecycle(online ? "enable" : "disable").toString());
                            server.broadcast(heartbeat(online).toString());
                        }
                        previous = online;
                    }
                    if (cfg.heartbeat && now >= nextHeartbeat) {
                        if (server.connectionCount() > 0) server.broadcast(heartbeat(online).toString());
                        nextHeartbeat = now + interval;
                    }
                } catch (InterruptedException ie) { return; }
                catch (Throwable ignore) {}
            }
        }, "pool-5-thread-1");
        t.setDaemon(true);
        t.start();
    }

    private JSONObject status(boolean online) throws Exception {
        return new JSONObject().put("online", online).put("good", online);
    }

    private JSONObject lifecycle(String subType) throws Exception {
        return new JSONObject()
                .put("time", System.currentTimeMillis() / 1000)
                .put("self_id", selfUin())
                .put("post_type", "meta_event")
                .put("meta_event_type", "lifecycle")
                .put("sub_type", subType);
    }

    private JSONObject heartbeat(boolean online) throws Exception {
        return new JSONObject()
                .put("time", System.currentTimeMillis() / 1000)
                .put("self_id", selfUin())
                .put("post_type", "meta_event")
                .put("meta_event_type", "heartbeat")
                .put("interval", Math.max(1000, cfg.heartbeatMs))
                .put("status", status(online));
    }

    private JSONObject versionInfo() throws Exception {
        return new JSONObject()
                .put("app_name", "onebot-qq")
                .put("app_version", "0.4.0")
                .put("protocol_version", "v11")
                .put("qq_version", "9.3.50")
                .put("runtime", "Android QQNT/Xposed");
    }

    private void scheduleRestart(int delayMs) {
        Thread t = new Thread(() -> {
            try { Thread.sleep(delayMs); } catch (InterruptedException ignore) { return; }
            L.i("set_restart requested; exiting QQ for external watchdog recovery");
            Runtime.getRuntime().exit(0);
        }, "pool-5-thread-2");
        t.setDaemon(true);
        t.start();
    }

    // ============ response envelopes ============
    private JSONObject ok(Object data, Object echo) {
        try {
            JSONObject o = new JSONObject();
            o.put("status", "ok"); o.put("retcode", 0);
            o.put("data", data == null ? JSONObject.NULL : data);
            if (echo != null) o.put("echo", echo);
            return o;
        } catch (Exception e) { return new JSONObject(); }
    }
    private JSONObject fail(int code, String msg, Object echo) {
        try {
            JSONObject o = new JSONObject();
            o.put("status", "failed"); o.put("retcode", code);
            o.put("msg", msg); o.put("wording", msg);
            o.put("data", JSONObject.NULL);
            if (echo != null) o.put("echo", echo);
            return o;
        } catch (Exception e) { return new JSONObject(); }
    }
}
