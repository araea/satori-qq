package com.onebot.qq.core;

import com.onebot.qq.Cfg;
import com.onebot.qq.L;
import com.onebot.qq.net.WsConn;
import com.onebot.qq.net.WsServer;
import com.onebot.qq.packet.LongMsg;
import com.onebot.qq.packet.GroupFiles;
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
        if ("get_image".equals(action)) return getResource(p, "image");
        if ("get_record".equals(action)) return getResource(p, "record");
        if ("get_file".equals(action)) return getResource(p, null);
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
            case "get_group_file_system_info":
                return getGroupFileSystemInfo(p.optLong("group_id", 0));
            case "get_group_root_files":
                return getGroupFiles(p.optLong("group_id", 0), "/");
            case "get_group_files_by_folder":
                return getGroupFiles(p.optLong("group_id", 0), p.optString("folder_id", ""));
            case "get_group_file_url":
                return getGroupFileUrl(p.optLong("group_id", 0),
                        p.optString("file_id", ""), p.optInt("busid", p.optInt("bus_id", 0)));
            case "create_group_file_folder":
                return createGroupFileFolder(p.optLong("group_id", 0),
                        p.optString("folder_id", p.optString("parent_id", "/")),
                        firstNonEmpty(p.optString("folder_name", ""), p.optString("name", "")));
            case "delete_group_folder":
            case "delete_group_file_folder":
                return deleteGroupFolder(p.optLong("group_id", 0),
                        firstNonEmpty(p.optString("folder_id", ""), p.optString("folder", "")));
            case "rename_group_folder":
                return renameGroupFolder(p.optLong("group_id", 0),
                        firstNonEmpty(p.optString("folder_id", ""), p.optString("folder", "")),
                        firstNonEmpty(p.optString("new_folder_name", ""),
                                p.optString("name", p.optString("folder_name", ""))));
            case "delete_group_file":
                return deleteGroupFile(p.optLong("group_id", 0),
                        p.optString("file_id", ""), p.optInt("busid", p.optInt("bus_id", 0)));
            case "move_group_file":
                return moveGroupFile(p.optLong("group_id", 0),
                        p.optString("file_id", ""),
                        firstNonEmpty(p.optString("parent_directory", ""),
                                p.optString("current_parent_directory", "/")),
                        firstNonEmpty(p.optString("target_directory", ""),
                                p.optString("target_parent_directory", "/")),
                        p.optInt("busid", p.optInt("bus_id", 0)));
            case "rename_group_file":
                return renameGroupFile(p.optLong("group_id", 0),
                        p.optString("file_id", ""),
                        firstNonEmpty(p.optString("parent_directory", ""),
                                p.optString("current_parent_directory", "/")),
                        firstNonEmpty(p.optString("new_name", ""), p.optString("name", "")),
                        p.optInt("busid", p.optInt("bus_id", 0)));
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

    /** Resolve an opaque file id learned from an incoming segment to a local path and/or source URL. */
    private JSONObject getResource(JSONObject p, String expectedType) throws Exception {
        String id = p.optString("file", p.optString("file_id", p.optString("id", ""))).trim();
        if (id.isEmpty()) throw new ApiError(1400, "missing file/file_id");
        MsgStore.Resource resource = store.getResource(id);
        if (resource == null) {
            // Also accept a direct local path or URL for compatibility with clients that retain segment data.
            resource = new MsgStore.Resource();
            resource.id = id;
            resource.type = expectedType == null ? "file" : expectedType;
            if (id.startsWith("http://") || id.startsWith("https://")) resource.url = id;
            else resource.path = id.startsWith("file://") ? id.substring(7) : id;
        }
        if (expectedType != null && resource.type != null && !expectedType.equals(resource.type))
            throw new ApiError(1400, "resource type is " + resource.type + ", expected " + expectedType);

        // Prefer an existing local file, then QQ's authenticated kernel downloader. Old qpic URLs
        // frequently expire or stall, so direct HTTP is deliberately the final fallback.
        java.io.File local = com.onebot.qq.qq.Media.resolve(resource.path, "");
        if (local == null && resource.msgId != 0 && qq.isOnline()) {
            String downloaded = qq.downloadRichMedia(
                    resource.chatType, resource.peerUid, resource.msgId, resource.elementId);
            if (!downloaded.isEmpty()) {
                local = new java.io.File(downloaded);
                resource.path = downloaded;
                resource.size = local.length();
            }
        }
        if (local == null && resource.url != null && !resource.url.isEmpty())
            local = com.onebot.qq.qq.Media.resolve("", resource.url);
        if (local == null && (resource.url == null || resource.url.isEmpty()))
            throw new ApiError(1404, "resource unavailable: " + id);

        JSONObject out = new JSONObject()
                .put("resource_id", resource.id)
                .put("resource_type", resource.type == null ? "file" : resource.type)
                .put("file_name", resource.name == null ? "" : resource.name)
                .put("file_size", local != null ? local.length() : resource.size);
        if (local != null) out.put("file", local.getAbsolutePath());
        else out.put("file", resource.url);
        if (resource.url != null && !resource.url.isEmpty()) out.put("url", resource.url);
        return out;
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

    private JSONObject getGroupFileSystemInfo(long groupId) throws Exception {
        if (groupId == 0) throw new ApiError(1400, "missing group_id");
        PacketSvc.Result countPacket = qq.packets().sendOidb(GroupFiles.VIEW_CMD,
                GroupFiles.COUNT_SUB, GroupFiles.countRequest(groupId), true, 15_000L);
        if (!countPacket.ok())
            throw new ApiError(1500, "group file count failed: " + countPacket.describe());
        GroupFiles.CountResult count = GroupFiles.parseCount(countPacket.body);
        if (count.code != 0)
            throw new ApiError(1500, "group file count failed (code=" + count.code + "): " + count.message);

        PacketSvc.Result spacePacket = qq.packets().sendOidb(GroupFiles.VIEW_CMD,
                GroupFiles.SPACE_SUB, GroupFiles.spaceRequest(groupId), true, 15_000L);
        if (!spacePacket.ok())
            throw new ApiError(1500, "group file space failed: " + spacePacket.describe());
        GroupFiles.SpaceResult space = GroupFiles.parseSpace(spacePacket.body);
        if (space.code != 0)
            throw new ApiError(1500, "group file space failed (code=" + space.code + "): " + space.message);
        return new JSONObject()
                .put("file_count", count.fileCount)
                .put("limit_count", count.limitCount)
                .put("used_space", space.usedSpace)
                .put("total_space", space.totalSpace);
    }

    private JSONObject getGroupFiles(long groupId, String folderId) throws Exception {
        if (groupId == 0) throw new ApiError(1400, "missing group_id");
        if (folderId == null || folderId.isEmpty())
            throw new ApiError(1400, "missing folder_id");
        JSONArray files = new JSONArray();
        JSONArray folders = new JSONArray();
        int startIndex = 0;
        final int pageSize = 50;
        for (int page = 0; page < 100; page++) {
            PacketSvc.Result packet = qq.packets().sendOidb(GroupFiles.VIEW_CMD,
                    GroupFiles.LIST_SUB,
                    GroupFiles.listRequest(groupId, folderId, startIndex, pageSize), true, 15_000L);
            if (!packet.ok())
                throw new ApiError(1500, "group file list failed: " + packet.describe());
            GroupFiles.ListResult result = GroupFiles.parseList(packet.body);
            if (result.code != 0)
                throw new ApiError(1500, "group file list failed (code=" + result.code + "): " + result.message);
            for (GroupFiles.Entry entry : result.entries) {
                if (entry.folder) {
                    folders.put(new JSONObject()
                            .put("folder_id", entry.id)
                            .put("folder_name", entry.name)
                            .put("create_time", entry.uploadTime)
                            .put("creator", entry.creatorUin)
                            .put("creator_name", entry.creatorName)
                            .put("total_file_count", entry.totalFileCount));
                } else {
                    files.put(new JSONObject()
                            .put("file_id", entry.id)
                            .put("file_name", entry.name)
                            .put("busid", entry.busId)
                            .put("file_size", entry.size)
                            .put("upload_time", entry.uploadTime)
                            .put("dead_time", entry.deadTime)
                            .put("modify_time", entry.modifyTime)
                            .put("download_times", entry.downloadTimes)
                            .put("uploader", entry.uploaderUin)
                            .put("uploader_name", entry.uploaderName));
                }
            }
            if (result.end) return new JSONObject().put("files", files).put("folders", folders);
            int next = result.nextIndex > startIndex ? result.nextIndex : startIndex + pageSize;
            if (next <= startIndex)
                throw new ApiError(1500, "group file list returned a stalled cursor");
            startIndex = next;
        }
        throw new ApiError(1500, "group file list exceeded pagination limit");
    }

    private JSONObject getGroupFileUrl(long groupId, String fileId, int busId) throws Exception {
        if (groupId == 0) throw new ApiError(1400, "missing group_id");
        if (fileId == null || fileId.isEmpty()) throw new ApiError(1400, "missing file_id");
        PacketSvc.Result packet = qq.packets().sendOidb(GroupFiles.DOWNLOAD_CMD,
                GroupFiles.DOWNLOAD_SUB, GroupFiles.urlRequest(groupId, fileId, busId), true, 15_000L);
        if (!packet.ok())
            throw new ApiError(1500, "group file URL failed: " + packet.describe());
        GroupFiles.UrlResult result = GroupFiles.parseUrl(packet.body);
        if (result.code != 0)
            throw new ApiError(1500, "group file URL failed (code=" + result.code + "): " + result.message);
        if (result.url.isEmpty()) throw new ApiError(1500, "group file URL response is empty");
        return new JSONObject().put("url", result.url);
    }

    private JSONObject createGroupFileFolder(long groupId, String parentId, String name) throws Exception {
        if (groupId == 0) throw new ApiError(1400, "missing group_id");
        if (name == null || name.isEmpty()) throw new ApiError(1400, "missing folder_name");
        GroupFiles.OpResult parsed = GroupFiles.parseCreateFolder(
                sendGroupFileBody(GroupFiles.FOLDER_CMD, GroupFiles.CREATE_FOLDER_SUB,
                        GroupFiles.createFolderRequest(groupId, parentId, name), "create group folder"));
        if (parsed.code != 0)
            throw new ApiError(1500, "create group folder failed (code=" + parsed.code + "): " + parsed.message);
        JSONObject created = folderJson(parsed.folder, name, parentId);
        if (created.optString("folder_id", "").isEmpty()) {
            JSONObject listed = getGroupFiles(groupId, parentId == null || parentId.isEmpty() ? "/" : parentId);
            JSONArray folders = listed.optJSONArray("folders");
            if (folders != null) {
                for (int i = 0; i < folders.length(); i++) {
                    JSONObject folder = folders.optJSONObject(i);
                    if (folder != null && name.equals(folder.optString("folder_name"))) {
                        created.put("folder_id", folder.optString("folder_id"));
                        created.put("create_time", folder.optLong("create_time"));
                        created.put("creator", folder.optLong("creator"));
                        created.put("creator_name", folder.optString("creator_name"));
                        break;
                    }
                }
            }
        }
        if (created.optString("folder_id", "").isEmpty())
            throw new ApiError(1500, "create group folder succeeded without folder_id");
        return created;
    }

    private JSONObject deleteGroupFolder(long groupId, String folderId) throws Exception {
        if (groupId == 0) throw new ApiError(1400, "missing group_id");
        if (folderId == null || folderId.isEmpty()) throw new ApiError(1400, "missing folder_id");
        GroupFiles.OpResult parsed = GroupFiles.parseDeleteFolder(
                sendGroupFileBody(GroupFiles.FOLDER_CMD, GroupFiles.DELETE_FOLDER_SUB,
                        GroupFiles.deleteFolderRequest(groupId, folderId), "delete group folder"));
        if (parsed.code != 0)
            throw new ApiError(1500, "delete group folder failed (code=" + parsed.code + "): " + parsed.message);
        return new JSONObject().put("folder_id", folderId);
    }

    private JSONObject renameGroupFolder(long groupId, String folderId, String newName) throws Exception {
        if (groupId == 0) throw new ApiError(1400, "missing group_id");
        if (folderId == null || folderId.isEmpty()) throw new ApiError(1400, "missing folder_id");
        if (newName == null || newName.isEmpty()) throw new ApiError(1400, "missing new_folder_name");
        GroupFiles.OpResult parsed = GroupFiles.parseRenameFolder(
                sendGroupFileBody(GroupFiles.FOLDER_CMD, GroupFiles.RENAME_FOLDER_SUB,
                        GroupFiles.renameFolderRequest(groupId, folderId, newName), "rename group folder"));
        if (parsed.code != 0)
            throw new ApiError(1500, "rename group folder failed (code=" + parsed.code + "): " + parsed.message);
        JSONObject o = folderJson(parsed.folder, newName, null);
        o.put("folder_id", folderId);
        return o;
    }

    private JSONObject deleteGroupFile(long groupId, String fileId, int busId) throws Exception {
        if (groupId == 0) throw new ApiError(1400, "missing group_id");
        if (fileId == null || fileId.isEmpty()) throw new ApiError(1400, "missing file_id");
        GroupFiles.OpResult parsed = GroupFiles.parseDeleteFile(
                sendGroupFileBody(GroupFiles.DOWNLOAD_CMD, GroupFiles.DELETE_FILE_SUB,
                        GroupFiles.deleteFileRequest(groupId, fileId, busId), "delete group file"));
        if (parsed.code != 0)
            throw new ApiError(1500, "delete group file failed (code=" + parsed.code + "): " + parsed.message);
        return new JSONObject().put("file_id", fileId);
    }

    private JSONObject moveGroupFile(long groupId, String fileId, String parentId, String destId,
                                     int busId) throws Exception {
        if (groupId == 0) throw new ApiError(1400, "missing group_id");
        if (fileId == null || fileId.isEmpty()) throw new ApiError(1400, "missing file_id");
        GroupFiles.OpResult parsed = GroupFiles.parseMoveFile(
                sendGroupFileBody(GroupFiles.DOWNLOAD_CMD, GroupFiles.MOVE_FILE_SUB,
                        GroupFiles.moveFileRequest(groupId, fileId, parentId, destId, busId),
                        "move group file"));
        if (parsed.code != 0)
            throw new ApiError(1500, "move group file failed (code=" + parsed.code + "): " + parsed.message);
        return new JSONObject().put("file_id", fileId).put("parent_id", destId);
    }

    private JSONObject renameGroupFile(long groupId, String fileId, String parentId, String newName,
                                       int busId) throws Exception {
        if (groupId == 0) throw new ApiError(1400, "missing group_id");
        if (fileId == null || fileId.isEmpty()) throw new ApiError(1400, "missing file_id");
        if (newName == null || newName.isEmpty()) throw new ApiError(1400, "missing new_name");
        GroupFiles.OpResult parsed = GroupFiles.parseRenameFile(
                sendGroupFileBody(GroupFiles.DOWNLOAD_CMD, GroupFiles.RENAME_FILE_SUB,
                        GroupFiles.renameFileRequest(groupId, fileId, parentId, newName, busId),
                        "rename group file"));
        if (parsed.code != 0)
            throw new ApiError(1500, "rename group file failed (code=" + parsed.code + "): " + parsed.message);
        return new JSONObject().put("file_id", fileId).put("file_name", newName);
    }

    private byte[] sendGroupFileBody(int cmd, int sub, byte[] body, String action) throws Exception {
        PacketSvc.Result packet = qq.packets().sendOidb(cmd, sub, body, true, 15_000L);
        if (!packet.ok()) throw new ApiError(1500, action + " failed: " + packet.describe());
        return packet.body == null ? new byte[0] : packet.body;
    }

    private JSONObject folderJson(GroupFiles.Entry folder, String fallbackName, String fallbackParent)
            throws Exception {
        JSONObject o = new JSONObject();
        if (folder != null) {
            o.put("folder_id", folder.id);
            o.put("folder_name", folder.name);
            o.put("parent_id", folder.parentId);
            o.put("create_time", folder.uploadTime);
            o.put("modify_time", folder.modifyTime);
            o.put("creator", folder.creatorUin);
            o.put("creator_name", folder.creatorName);
            o.put("total_file_count", folder.totalFileCount);
        } else {
            if (fallbackName != null) o.put("folder_name", fallbackName);
            if (fallbackParent != null) o.put("parent_id", fallbackParent);
        }
        return o;
    }

    private static String firstNonEmpty(String a, String b) {
        return (a != null && !a.isEmpty()) ? a : (b == null ? "" : b);
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
                .put("app_version", "0.5.0")
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
