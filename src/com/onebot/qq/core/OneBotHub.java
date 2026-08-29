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
import com.onebot.qq.qq.AntiDetect;
import com.onebot.qq.qq.Media;
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
    private final OutboundGuard outboundGuard;
    private WsServer server;
    private volatile long onlineSinceMs;

    public OneBotHub(Cfg cfg, QQClient qq, MsgStore store) {
        this.cfg = cfg; this.qq = qq; this.store = store;
        this.conv = new Convert(qq, store);
        this.outboundGuard = new OutboundGuard(cfg.outboundMinIntervalMs,
                cfg.outboundQueueTimeoutMs, cfg.outboundMaxQueued,
                cfg.outboundMaxPerMinute, cfg.outboundFailureThreshold,
                cfg.outboundCircuitOpenMs);
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
            replayPendingRequests(conn);
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
        OutboundGuard.Lease outboundLease = null;
        boolean outboundSucceeded = false;
        try {
            if (OutboundGuard.isMutation(action)) {
                ensureOutboundReady();
                try {
                    outboundLease = outboundGuard.acquire(action);
                } catch (OutboundGuard.BusyException busy) {
                    throw new ApiError(1500, busy.getMessage());
                }
                // The session can go offline while this request waits behind a media upload.
                ensureOutboundReady();
            }
            Object data = dispatch(action, params);
            outboundSucceeded = true;
            conn.send(ok(data, echo).toString());
        } catch (ApiError e) {
            conn.send(fail(e.code, e.getMessage(), echo).toString());
        } catch (IllegalStateException e) {
            conn.send(fail(1500, e.getMessage(), echo).toString());
        } catch (Throwable t) {
            L.e("action " + action, t);
            conn.send(fail(1400, String.valueOf(t), echo).toString());
        } finally {
            if (outboundLease != null) outboundLease.complete(outboundSucceeded);
        }
    }

    private static final class ApiError extends RuntimeException {
        final int code; ApiError(int code, String msg) { super(msg); this.code = code; }
    }

    private void ensureOutboundReady() {
        if (!qq.isOnline()) throw new ApiError(1500, "QQ kernel offline or not ready");
        long since = onlineSinceMs;
        long remaining = cfg.onlineStabilizeMs - (System.currentTimeMillis() - since);
        if (since <= 0 || remaining > 0) {
            long seconds = Math.max(1, (remaining + 999) / 1000);
            throw new ApiError(1500, "QQ session stabilizing; retry after " + seconds + "s");
        }
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
            case "get_friend_msg_history":
                return getFriendMsgHistory(p.optLong("user_id", 0),
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
                requireOp(qq.kickMember(g, uidFor(g, u), p.optBoolean("reject_add_request", false)));
                return new JSONObject();
            }
            case "invite_group": {
                long g = p.optLong("group_id", 0), u = p.optLong("user_id", 0);
                if (g == 0 || u == 0) throw new ApiError(1400, "missing group_id/user_id");
                requireOp(qq.inviteToGroup(g, uidFor(g, u)));
                return new JSONObject();
            }
            case "set_group_ban": {
                long g = p.optLong("group_id", 0), u = p.optLong("user_id", 0);
                requireOp(qq.banMember(g, uidFor(g, u), p.optInt("duration", 1800)));
                return new JSONObject();
            }
            case "set_group_whole_ban":
                requireOp(qq.wholeBan(p.optLong("group_id", 0), p.optBoolean("enable", true)));
                return new JSONObject();
            case "set_group_card": {
                long g = p.optLong("group_id", 0), u = p.optLong("user_id", 0);
                requireOp(qq.setCard(g, uidFor(g, u), p.optString("card", "")));
                return new JSONObject();
            }
            case "set_group_admin": {
                long g = p.optLong("group_id", 0), u = p.optLong("user_id", 0);
                requireOp(qq.setAdmin(g, uidFor(g, u), p.optBoolean("enable", true)));
                return new JSONObject();
            }
            case "set_group_leave":
                requireOp(qq.quitGroup(p.optLong("group_id", 0)));
                return new JSONObject();
            case "set_group_name": {
                long groupId = p.optLong("group_id", 0);
                if (groupId == 0) throw new ApiError(1400, "missing group_id");
                requireOp(qq.setGroupName(groupId, p.optString("group_name", "")));
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
            case "send_poke":
                sendPoke(p.optLong("group_id", 0), p.optLong("user_id", 0));
                return new JSONObject();
            case "get_forward_msg":
                return getForwardMsg(p.optString("id", p.optString("message_id", "")));
            case "get_group_system_msg":
                return getGroupSystemMsg();
            case "get_friend_system_msg":
                return getFriendSystemMsg();
            case "upload_group_file":
                return uploadGroupFile(p);
            case "upload_private_file":
                return uploadPrivateFile(p);
            case "set_friend_add_request":
                setFriendAddRequest(p.optString("flag", ""),
                        approveParam(p), p.optString("remark", p.optString("reason", "")));
                return new JSONObject();
            case "set_group_add_request":
                setGroupAddRequest(p.optString("flag", ""),
                        approveParam(p), p.optString("reason", ""));
                return new JSONObject();
            default:
                throw new ApiError(1404, "unknown action: " + action);
        }
    }

    /** Resolve an opaque file id learned from an incoming segment to a local path and/or source URL. */
    private JSONObject getResource(JSONObject p, String expectedType) throws Exception {
        String id = p.optString("file", p.optString("file_id", p.optString("id", ""))).trim();
        if (id.isEmpty()) throw new ApiError(1400, "missing file/file_id");
        boolean registered = true;
        MsgStore.Resource resource = store.getResource(id);
        if (resource == null) {
            registered = false;
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
                    resource.chatType, resource.peerUid, resource.msgId, resource.elementId,
                    resource.fileModelId);
            if (!downloaded.isEmpty()) {
                local = new java.io.File(downloaded);
                resource.path = downloaded;
                resource.size = local.length();
            }
        }
        if (local == null && "video".equals(resource.type) && resource.msgId != 0 && qq.isOnline()) {
            String play = qq.getVideoPlayUrl(
                    resource.chatType, resource.peerUid, resource.msgId, resource.elementId);
            if (!play.isEmpty()) {
                resource.url = play;
                local = com.onebot.qq.qq.Media.resolve("", play);
                if (local != null) resource.size = local.length();
            }
        }
        if (local == null && resource.url != null && !resource.url.isEmpty())
            local = com.onebot.qq.qq.Media.resolve("", resource.url);
        if (local == null && (resource.url == null || resource.url.isEmpty())) {
            String why = !registered ? "unregistered"
                    : resource.msgId == 0 ? "no download context" : "download failed";
            throw new ApiError(1404, "resource unavailable (" + why + ")");
        }

        JSONObject out = new JSONObject()
                .put("resource_id", resource.id)
                .put("resource_type", resource.type == null ? "file" : resource.type)
                .put("file_name", resource.name == null ? "" : resource.name)
                .put("file_size", local != null ? local.length() : resource.size);
        if (local != null && "record".equals(expectedType)) {
            String format = p.optString("out_format", p.optString("outFormat", "")).trim();
            if (!format.isEmpty()) {
                java.io.File converted = Media.convertRecord(qq.ref, local, format);
                if (converted == null)
                    throw new ApiError(1400, "unsupported or failed out_format: " + format);
                local = converted;
                String produced = converted.getName();
                int dot = produced.lastIndexOf('.');
                out.put("out_format", dot >= 0 ? produced.substring(dot + 1) : format);
                out.put("file_size", local.length());
            }
        }
        if (local != null) out.put("file", local.getAbsolutePath());
        else out.put("file", resource.url);
        if (resource.url != null && !resource.url.isEmpty()) out.put("url", resource.url);
        return out;
    }

    /**
     * Merge-forward: Android QQNT only opens cards created by kernel {@code multiForwardMsg}
     * from real local messages. Fake SsoSendLongMsg + type-16/13/ark is fallback only.
     * Exactly one of groupId/userId is non-zero.
     */
    private JSONObject sendForward(long groupId, long userId, Object messages) throws Exception {
        if (groupId == 0 && userId == 0) throw new ApiError(1400, "missing group_id/user_id");
        List<LongMsg.Node> nodes = parseForwardNodes(messages, groupId != 0);
        if (nodes.isEmpty()) throw new ApiError(1400, "empty forward messages");
        JSONObject nativeSent = sendForwardNative(groupId, userId, messages);
        if (nativeSent != null) return nativeSent;
        String selfUid = "";
        if (groupId == 0) {
            selfUid = qq.resolveUid(selfUin());
            if (selfUid == null || selfUid.isEmpty()) throw new ApiError(1500, "cannot resolve self uid");
        } else {
            String maybe = qq.resolveUid(selfUin());
            if (maybe != null) selfUid = maybe;
        }
        String fileName = java.util.UUID.randomUUID().toString();
        byte[] req = LongMsg.buildUploadReq(groupId, selfUid, nodes, fileName);
        PacketSvc.Result r = qq.packets().sendSso(LongMsg.CMD, req);
        if (!r.ok()) throw new ApiError(1500, "forward upload failed: " + r.describe());
        String resId = LongMsg.parseResId(r.body);
        if (resId == null || resId.isEmpty()) throw new ApiError(1500, "forward upload: no resId in reply");
        LongMsg.Card card = LongMsg.buildCard(resId, nodes, groupId != 0, fileName);
        JSONObject sent = sendForwardElements(groupId, userId,
                conv.toMultiForward(card.json, resId, card.fileName), "type16");
        if (sent == null) {
            sent = sendForwardElements(groupId, userId,
                    conv.toStructLongMsg(card.xml, resId), "type13");
        }
        if (sent == null) sent = sendForwardArk(groupId, userId, card.json);
        return sent.put("res_id", resId).put("forward_id", resId).put("filename", fileName);
    }

    /**
     * Android QQNT opens merge-forward via kernel getMultiMsg(msgId). That only works for cards
     * created by {@code multiForwardMsg} from real local messages, not fake SsoSendLongMsg ark/16.
     */
    private JSONObject sendForwardNative(long groupId, long userId, Object messages)
            throws Exception {
        int chatType = groupId != 0 ? QQClient.CT_GROUP : QQClient.CT_C2C;
        String peer;
        if (groupId != 0) {
            peer = String.valueOf(groupId);
        } else {
            peer = store.uidOf(userId);
            if (peer == null || peer.isEmpty()) peer = qq.resolveUid(userId);
            if (peer != null && !peer.isEmpty()) store.learnUid(userId, peer);
            if (peer == null || peer.isEmpty()) return null;
        }
        java.util.ArrayList<Long> ids = new java.util.ArrayList<>();
        java.util.ArrayList<String> names = new java.util.ArrayList<>();
        JSONArray arr = messages instanceof JSONArray ? (JSONArray) messages : new JSONArray();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject seg = arr.optJSONObject(i);
            JSONObject d = seg == null ? null : seg.optJSONObject("data");
            if (d == null) continue;
            Object content = d.opt("content");
            if (content == null) content = "";
            java.util.ArrayList<Object> els = conv.toElements(content, chatType);
            if (els == null || els.isEmpty()) continue;
            QQClient.SendResult sr = qq.sendMsg(chatType, peer, els);
            if (sr.code != 0 || sr.msgId == 0) {
                L.e("native forward inner send failed code=" + sr.code + " " + sr.msg, null);
                return null;
            }
            ids.add(sr.msgId);
            String name = d.optString("nickname", d.optString("name", ""));
            names.add(name);
            afterSend(sr, chatType, groupId != 0 ? groupId : userId, groupId != 0 ? "" : peer);
        }
        if (ids.isEmpty()) return null;
        QQClient.SendResult fw = qq.multiForward(chatType, peer, ids, names);
        if (fw.code != 0) {
            L.e("multiForwardMsg failed code=" + fw.code + " " + fw.msg, null);
            return null;
        }
        JSONObject found = null;
        for (int attempt = 0; attempt < 10 && found == null; attempt++) {
            try { Thread.sleep(attempt == 0 ? 800 : 500); } catch (InterruptedException ignore) {}
            found = findNativeForward(chatType, peer, groupId, userId, ids);
        }
        if (found != null) return found;
        L.e("multiForward card not in history yet inners=" + ids.size(), null);
        return new JSONObject().put("native_forward", true).put("message_id", 0);
    }

    /** Newest merge-forward in history that is not one of the inner scaffolding msgIds. */
    private JSONObject findNativeForward(int chatType, String peer, long groupId, long userId,
                                         java.util.ArrayList<Long> innerIds) {
        QQClient.MsgListResult hist = qq.getMsgs(chatType, peer, 0, 20);
        if (hist.records == null) return null;
        for (int i = hist.records.size() - 1; i >= 0; i--) {
            Object rec = hist.records.get(i);
            long msgId = 0;
            try { msgId = Ref.asLong(qq.ref.get(rec, "msgId")); } catch (Throwable ignore) {}
            if (msgId != 0 && innerIds.contains(msgId)) continue;
            // self=0: own cards must not be dropped (live listener still skips self).
            JSONObject ev = conv.recordToEvent(rec, 0);
            if (ev == null) continue;
            JSONArray msg = ev.optJSONArray("message");
            if (msg == null) continue;
            JSONObject data = null;
            for (int j = 0; j < msg.length(); j++) {
                JSONObject s = msg.optJSONObject(j);
                if (s != null && "forward".equals(s.optString("type"))) {
                    data = s.optJSONObject("data");
                    break;
                }
            }
            if (data == null) continue;
            MsgStore.Rec stored = new MsgStore.Rec();
            stored.chatType = chatType;
            stored.peerUin = groupId != 0 ? groupId : userId;
            stored.peerUid = groupId != 0 ? "" : peer;
            stored.msgId = msgId;
            stored.senderUin = selfUin();
            stored.msgRecord = rec;
            int obId = store.put(stored);
            JSONObject out;
            try {
                out = new JSONObject().put("message_id", obId).put("native_forward", true);
                if (data.has("id") && !data.optString("id").isEmpty()) {
                    out.put("res_id", data.optString("id")).put("forward_id", data.optString("id"));
                }
                if (data.has("filename")) out.put("filename", data.optString("filename"));
                if (data.has("element_type")) out.put("element_type", data.optInt("element_type"));
            } catch (Exception e) {
                return null;
            }
            if (msgId != 0) qq.prefetchForward(chatType, peer, msgId);
            return out;
        }
        return null;
    }

    private JSONObject sendForwardElements(long groupId, long userId, java.util.ArrayList<Object> els,
                                           String label) throws Exception {
        if (els == null || els.isEmpty()) {
            L.e("multiForward " + label + " element empty", null);
            return null;
        }
        if (groupId != 0) {
            QQClient.SendResult sr = qq.sendMsg(QQClient.CT_GROUP, String.valueOf(groupId), els);
            if (sr.code == 0) {
                JSONObject sent = afterSend(sr, QQClient.CT_GROUP, groupId, "");
                qq.prefetchForward(QQClient.CT_GROUP, String.valueOf(groupId), sr.msgId);
                return sent;
            }
            L.e("multiForward " + label + " send failed code=" + sr.code + " " + sr.msg, null);
            return null;
        }
        String uid = store.uidOf(userId);
        if (uid == null || uid.isEmpty()) uid = qq.resolveUid(userId);
        if (uid != null && !uid.isEmpty()) store.learnUid(userId, uid);
        if (uid == null || uid.isEmpty())
            throw new ApiError(1404, "cannot resolve uid for user " + userId);
        QQClient.SendResult sr = qq.sendMsg(QQClient.CT_C2C, uid, els);
        if (sr.code == 0) {
            JSONObject sent = afterSend(sr, QQClient.CT_C2C, userId, uid);
            qq.prefetchForward(QQClient.CT_C2C, uid, sr.msgId);
            return sent;
        }
        L.e("multiForward " + label + " send failed code=" + sr.code + " " + sr.msg, null);
        return null;
    }

    private JSONObject sendForwardArk(long groupId, long userId, String cardJson) throws Exception {
        JSONArray msg = new JSONArray().put(new JSONObject().put("type", "json")
                .put("data", new JSONObject().put("data", cardJson)));
        return groupId != 0 ? sendGroup(groupId, msg) : sendPrivate(userId, msg);
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
            JSONArray content = segsToContent(n);
            messages.put(new JSONObject().put("type", "node").put("data", new JSONObject()
                    .put("user_id", n.senderUin)
                    .put("nickname", n.senderName == null ? "" : n.senderName)
                    .put("time", n.time)
                    .put("content", content)));
        }
        return new JSONObject().put("messages", messages);
    }

    private JSONArray segsToContent(LongMsg.Node n) throws Exception {
        JSONArray content = new JSONArray();
        if (n.segs.isEmpty()) {
            content.put(new JSONObject().put("type", "text")
                    .put("data", new JSONObject().put("text", n.text == null ? "" : n.text)));
            return content;
        }
        for (LongMsg.Seg s : n.segs) {
            JSONObject data = new JSONObject();
            switch (s.type) {
                case "at":
                    data.put("qq", s.qq == null || s.qq.isEmpty() ? "all" : s.qq);
                    if (s.text != null && !s.text.isEmpty()) data.put("name", s.text);
                    content.put(new JSONObject().put("type", "at").put("data", data));
                    break;
                case "face":
                    content.put(new JSONObject().put("type", "face")
                            .put("data", new JSONObject().put("id", s.id)));
                    break;
                case "reply":
                    content.put(new JSONObject().put("type", "reply")
                            .put("data", new JSONObject().put("id", s.id)));
                    break;
                case "image": {
                    String id = s.file == null ? "" : s.file;
                    if (!id.isEmpty()) {
                        store.putResource("image", id, "", s.url, s.name, s.size);
                    }
                    data.put("file", id);
                    if (s.url != null && !s.url.isEmpty()) data.put("url", s.url);
                    if (s.size > 0) data.put("file_size", s.size);
                    content.put(new JSONObject().put("type", "image").put("data", data));
                    break;
                }
                case "file": {
                    String id = s.file == null ? "" : s.file;
                    if (!id.isEmpty()) {
                        store.putResource("file", id, "", s.url, s.name, s.size);
                    }
                    data.put("file", id);
                    data.put("file_id", id);
                    if (s.name != null && !s.name.isEmpty()) data.put("name", s.name);
                    if (s.size > 0) data.put("file_size", s.size);
                    if (s.busid > 0) data.put("busid", s.busid);
                    content.put(new JSONObject().put("type", "file").put("data", data));
                    break;
                }
                default:
                    content.put(new JSONObject().put("type", "text")
                            .put("data", new JSONObject().put("text", s.text == null ? "" : s.text)));
                    break;
            }
        }
        return content;
    }

    private List<LongMsg.Node> parseForwardNodes(Object messages, boolean group) {
        List<LongMsg.Node> out = new java.util.ArrayList<>();
        if (!(messages instanceof JSONArray)) return out;
        JSONArray arr = (JSONArray) messages;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject seg = arr.optJSONObject(i);
            if (seg == null) continue;
            JSONObject d = seg.optJSONObject("data");
            if (d == null) continue;
            String type = seg.optString("type", "node");
            if (!type.isEmpty() && !"node".equals(type)) continue;
            LongMsg.Node n = new LongMsg.Node();
            n.senderUin = parseNodeUin(d);
            n.senderName = d.optString("name", d.optString("nickname", String.valueOf(n.senderUin)));
            n.time = d.optLong("time", 0);
            Object content = d.opt("content");
            n.text = extractText(content);
            encodeForwardContent(n, content, group);
            out.add(n);
        }
        return out;
    }

    /** Encode OneBot node content into im_msg_body Elems (text/at/face/reply/image/file). */
    private void encodeForwardContent(LongMsg.Node n, Object content, boolean group) {
        if (content instanceof String) {
            n.elems.add(LongMsg.elemText((String) content));
            return;
        }
        if (!(content instanceof JSONArray)) {
            if (n.text != null && !n.text.isEmpty()) n.elems.add(LongMsg.elemText(n.text));
            return;
        }
        JSONArray a = (JSONArray) content;
        for (int i = 0; i < a.length(); i++) {
            JSONObject s = a.optJSONObject(i);
            if (s == null) {
                String t = a.optString(i);
                if (!t.isEmpty()) n.elems.add(LongMsg.elemText(t));
                continue;
            }
            String type = s.optString("type", "text");
            JSONObject d = s.optJSONObject("data");
            if (d == null) d = new JSONObject();
            switch (type) {
                case "text":
                    n.elems.add(LongMsg.elemText(d.optString("text", "")));
                    break;
                case "at": {
                    String atQq = d.optString("qq", "");
                    boolean all = "all".equalsIgnoreCase(atQq);
                    long uin = all ? 0 : parseLongQuiet(atQq);
                    String uid = all ? "" : store.uidOf(uin);
                    if (!all && (uid == null || uid.isEmpty()) && uin != 0) {
                        try { uid = this.qq.resolveUid(uin); } catch (Exception ignore) { uid = ""; }
                        if (uid != null && !uid.isEmpty()) store.learnUid(uin, uid);
                    }
                    String display = d.optString("name", all ? "@全体成员" : ("@" + atQq));
                    n.elems.add(LongMsg.elemAt(display, all, uin, uid == null ? "" : uid));
                    break;
                }
                case "face":
                    n.elems.add(LongMsg.elemFace((int) parseLongQuiet(d.optString("id", "0"))));
                    break;
                case "reply": {
                    String id = d.optString("id", "");
                    MsgStore.Rec rec = store.get((int) parseLongQuiet(id));
                    long seq = rec != null ? rec.msgSeq : parseLongQuiet(id);
                    long uin = rec != null ? rec.senderUin : 0;
                    long msgId = rec != null ? rec.msgId : 0;
                    String uid = rec != null ? rec.senderUid : "";
                    n.elems.add(LongMsg.elemReply(seq, uin, 0, msgId, uid, "[回复]"));
                    break;
                }
                case "image": {
                    LongMsg.Pic pic = imageForForward(d, group);
                    n.elems.add(LongMsg.elemImage(pic));
                    break;
                }
                case "file": {
                    LongMsg.FileRef file = fileForForward(d);
                    n.elems.add(LongMsg.elemFile(file));
                    break;
                }
                default:
                    n.elems.add(LongMsg.elemText(extractText(new JSONArray().put(s))));
                    break;
            }
        }
        if (n.elems.isEmpty()) n.elems.add(LongMsg.elemText(n.text == null ? "" : n.text));
    }

    private LongMsg.Pic imageForForward(JSONObject d, boolean group) {
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
        java.io.File local = Media.resolve(spec, url);
        if (local == null && res != null) local = Media.resolve(res.path, res.url);
        if (local != null) {
            if (pic.md5 == null) pic.md5 = LongMsg.md5Of(local);
            pic.size = (int) Math.min(Integer.MAX_VALUE, local.length());
            if (pic.fileName.isEmpty()) pic.fileName = local.getName();
            try {
                android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                android.graphics.BitmapFactory.decodeFile(local.getAbsolutePath(), o);
                if (o.outWidth > 0) pic.width = o.outWidth;
                if (o.outHeight > 0) pic.height = o.outHeight;
            } catch (Throwable ignore) {}
        }
        pic.origUrl = url;
        if (pic.md5 == null) pic.md5 = new byte[0];
        return pic;
    }

    private LongMsg.FileRef fileForForward(JSONObject d) {
        LongMsg.FileRef f = new LongMsg.FileRef();
        f.fileId = firstNonEmpty(d.optString("file_id", ""), d.optString("file", ""));
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

    private static long parseLongQuiet(String s) {
        try { return s == null || s.isEmpty() ? 0 : Long.parseLong(s.trim()); }
        catch (Exception e) { return 0; }
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
                case "at": sb.append(sd == null ? "" : sd.optString("name",
                        "@" + sd.optString("qq", ""))); break;
                case "face": sb.append("[表情]"); break;
                case "image": sb.append("[图片]"); break;
                case "file": sb.append("[文件]"); break;
                case "reply": sb.append("[回复]"); break;
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

    /**
     * Chat-channel file send, then poll the group filesystem for the real file_id.
     * Chat uploads land in the root; a non-root folder moves after the id is known.
     */
    private JSONObject uploadGroupFile(JSONObject p) throws Exception {
        long groupId = p.optLong("group_id", 0);
        if (groupId == 0) throw new ApiError(1400, "missing group_id");
        String name = p.optString("name", "").trim();
        if (name.isEmpty()) {
            String file = p.optString("file", p.optString("url", ""));
            int slash = Math.max(file.lastIndexOf('/'), file.lastIndexOf('\\'));
            name = slash >= 0 ? file.substring(slash + 1) : file;
        }
        if (name.isEmpty()) throw new ApiError(1400, "missing name");
        String folder = firstNonEmpty(p.optString("folder_id", ""), p.optString("folder", "/"));
        if (folder == null || folder.isEmpty()) folder = "/";
        JSONObject sent = sendGroup(groupId, fileSeg(p));
        JSONObject found = waitGroupFile(groupId, "/", name);
        if (found == null)
            throw new ApiError(1500, "uploaded file did not appear in group file system");
        if (!"/".equals(folder)) {
            moveGroupFile(groupId, found.optString("file_id"), "/", folder,
                    found.optInt("busid", found.optInt("bus_id", 0)));
            found.put("parent_id", folder);
        }
        found.put("message_id", sent.optInt("message_id"));
        return found;
    }

    private JSONObject uploadPrivateFile(JSONObject p) throws Exception {
        long userId = p.optLong("user_id", 0);
        if (userId == 0) throw new ApiError(1400, "missing user_id");
        String name = p.optString("name", "").trim();
        if (name.isEmpty()) {
            String file = p.optString("file", p.optString("url", ""));
            int slash = Math.max(file.lastIndexOf('/'), file.lastIndexOf('\\'));
            name = slash >= 0 ? file.substring(slash + 1) : file;
        }
        JSONObject sent = sendPrivate(userId, fileSeg(p));
        int obId = sent.optInt("message_id");
        MsgStore.Rec rec = store.get(obId);
        JSONObject found = rec == null ? null
                : waitPrivateFile(rec.chatType, rec.peerUid, rec.msgId, name);
        if (found == null) return sent;
        found.put("message_id", obId);
        return found;
    }

    private JSONObject waitPrivateFile(int chatType, String peerUid, long msgId, String name)
            throws Exception {
        if (msgId == 0 || peerUid == null || peerUid.isEmpty()) return null;
        final int attempts = 8;
        final long delayMs = 1500L;
        for (int i = 0; i < attempts; i++) {
            if (i > 0) Thread.sleep(delayMs);
            QQClient.MsgListResult hist = qq.getMsgsByMsgId(chatType, peerUid, msgId);
            if (!hist.ok() || hist.records.isEmpty()) continue;
            for (Object rec : hist.records) {
                JSONObject ev = conv.recordToEvent(rec, 0);
                if (ev == null) continue;
                JSONArray segs = ev.optJSONArray("message");
                if (segs == null) continue;
                for (int j = 0; j < segs.length(); j++) {
                    JSONObject seg = segs.optJSONObject(j);
                    if (seg == null || !"file".equals(seg.optString("type"))) continue;
                    JSONObject d = seg.optJSONObject("data");
                    if (d == null) continue;
                    String id = firstNonEmpty(d.optString("file_id", ""), d.optString("file", ""));
                    if (id.isEmpty()) continue;
                    JSONObject out = new JSONObject()
                            .put("file_id", id)
                            .put("file", id)
                            .put("file_name", d.optString("name", name))
                            .put("file_size", d.optLong("file_size", d.optLong("size", 0)));
                    return out;
                }
            }
        }
        return null;
    }

    private JSONObject waitGroupFile(long groupId, String folderId, String name) throws Exception {
        final int attempts = 8;
        final long delayMs = 1500L;
        for (int i = 0; i < attempts; i++) {
            if (i > 0) Thread.sleep(delayMs);
            JSONObject listed = getGroupFiles(groupId, folderId);
            JSONArray files = listed.optJSONArray("files");
            if (files == null) continue;
            for (int j = 0; j < files.length(); j++) {
                JSONObject file = files.optJSONObject(j);
                if (file != null && name.equals(file.optString("file_name"))) return file;
            }
        }
        return null;
    }

    private JSONObject sendGroup(long groupId, Object message) throws Exception {
        if (groupId == 0) throw new ApiError(1400, "missing group_id");
        if (looksLikeForward(message)) return sendForward(groupId, 0, message);
        java.util.ArrayList<Object> els = conv.toElements(message, QQClient.CT_GROUP);
        QQClient.SendResult r = qq.sendMsg(QQClient.CT_GROUP, String.valueOf(groupId), els);
        return afterSend(r, QQClient.CT_GROUP, groupId, "");
    }

    private JSONObject sendPrivate(long userId, Object message) throws Exception {
        if (userId == 0) throw new ApiError(1400, "missing user_id");
        if (looksLikeForward(message)) return sendForward(0, userId, message);
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

    /**
     * ayjx (and go-cqhttp-style clients) send merge-forward as {@code send_msg}
     * whose message array is {@code node} segments, not {@code send_*_forward_msg}.
     */
    static boolean looksLikeForward(Object message) {
        if (!(message instanceof JSONArray)) return false;
        JSONArray arr = (JSONArray) message;
        boolean anyNode = false;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject seg = arr.optJSONObject(i);
            if (seg == null) continue;
            String type = seg.optString("type", "");
            if ("node".equals(type)) anyNode = true;
            else if (!type.isEmpty()) return false;
        }
        return anyNode;
    }

    /** ayjx {@code node_custom} serializes user_id as a JSON string. */
    private long parseNodeUin(JSONObject d) {
        long uin = d.optLong("uin", d.optLong("user_id", 0));
        if (uin != 0) return uin;
        String raw = d.optString("uin", d.optString("user_id", ""));
        if (!raw.isEmpty()) {
            try { return Long.parseLong(raw.trim()); } catch (NumberFormatException ignored) {}
        }
        return selfUin();
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
        String peer = r.peerUid == null || r.peerUid.isEmpty() ? String.valueOf(r.peerUin) : r.peerUid;
        Object contact = qq.ref.neu(QQClient.CONTACT, r.chatType, peer, "");
        java.util.ArrayList<Long> ids = new java.util.ArrayList<>();
        ids.add(r.msgId);
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        final int[] code = new int[]{-1};
        final String[] wording = new String[]{""};
        Object cb = java.lang.reflect.Proxy.newProxyInstance(qq.ref.cl,
            new Class[]{qq.ref.cls(QQClient.IOPERATE_CB)}, (proxy, m, args) -> {
                if ("onResult".equals(m.getName()) && args != null && args.length >= 1) {
                    code[0] = Ref.asInt(args[0]);
                    if (args.length >= 2) wording[0] = Ref.asStr(args[1]);
                    latch.countDown();
                }
                return null;
            });
        qq.ref.call(msgService, "recallMsg", contact, ids, cb);
        if (!latch.await(15, java.util.concurrent.TimeUnit.SECONDS))
            throw new ApiError(1500, "recall timeout");
        if (code[0] != 0) throw new ApiError(1500, "recall failed: code=" + code[0] + " " + wording[0]);
        emitRecall(r.chatType == QQClient.CT_GROUP, r.peerUin, r.senderUin, selfUin(), r.id);
    }

    private void emitRecall(boolean group, long peer, long user, long operator, int messageId) {
        if (messageId != 0 && !seenRecalls.add(messageId)) return;
        if (seenRecalls.size() > 8000) seenRecalls.clear();
        try {
            JSONObject n = Notices.recall(selfUin(), System.currentTimeMillis() / 1000,
                    group, peer, user, operator, messageId);
            server.broadcast(n.toString());
        } catch (Throwable t) {
            L.e("emitRecall", t);
        }
    }

    private JSONObject getMsg(int messageId) throws Exception {
        MsgStore.Rec r = store.get(messageId);
        if (r == null || r.msgRecord == null) throw new ApiError(1404, "message not found: " + messageId);
        JSONObject ev = conv.recordToEvent(r.msgRecord, 0);
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
        return historyToArray(qq.getMsgs(QQClient.CT_GROUP, String.valueOf(groupId), messageSeq, count),
                messageSeq);
    }

    private JSONObject getFriendMsgHistory(long userId, long messageSeq, int count) throws Exception {
        if (userId == 0) throw new ApiError(1400, "missing user_id");
        return historyToArray(qq.getMsgs(QQClient.CT_C2C, uidFor(0, userId), messageSeq, count),
                messageSeq);
    }

    private JSONObject historyToArray(QQClient.MsgListResult hist, long cursorSeq) throws Exception {
        if (hist.timedOut) throw new ApiError(1500, hist.describe());
        if (!hist.ok()) throw new ApiError(1500, "history failed: " + hist.describe());
        JSONArray messages = new JSONArray();
        for (Object rec : hist.records) {
            JSONObject ev = conv.recordToEvent(rec, 0); // self=0 keeps messages sent by this account
            if (ev == null) continue;
            if ("notice".equals(ev.optString("post_type"))) continue;
            long seq = ev.optLong("message_seq");
            if (cursorSeq > 0 && seq >= cursorSeq) continue;
            ev.put("self_id", selfUin());
            JSONObject item = new JSONObject()
                    .put("time", ev.optLong("time"))
                    .put("message_type", ev.optString("message_type"))
                    .put("message_id", ev.optInt("message_id"))
                    .put("real_id", ev.optInt("message_id"))
                    .put("message_seq", seq)
                    .put("sender", ev.optJSONObject("sender"))
                    .put("message", ev.optJSONArray("message"))
                    .put("raw_message", ev.optString("raw_message"));
            messages.put(item);
        }
        return new JSONObject().put("messages", messages);
    }

    private static void requireOp(QQClient.OpResult r) {
        if (r == null || !r.ok()) {
            throw new ApiError(1500, r == null ? "group op failed" : r.describe());
        }
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
        if (groupId != 0) {
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

    /** go-cqhttp send_poke: 0xED3_1. Group uses groupUin+target; friend uses friendUin=target. */
    private void sendPoke(long groupId, long userId) throws Exception {
        if (userId == 0) throw new ApiError(1400, "missing user_id");
        Pb.Writer body = Pb.w().varint(1, userId).varint(6, 0);
        if (groupId != 0) body.varint(2, groupId);
        else body.varint(5, userId);
        PacketSvc.Result result = qq.packets().sendOidb(0xED3, 1, body.toByteArray());
        if (!result.ok()) throw new ApiError(1500, "send_poke failed: " + result.describe());
    }

    // ============ QQ inbound: events ============
    private final java.util.Set<Long> seen = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.Map<String, FriendReq> pendingFriends = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<String, GroupReq> pendingGroups = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Set<String> seenRequests = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.Set<Integer> seenRecalls = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.Set<String> seenMemberChanges = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static final class FriendReq {
        final String uid;
        final long reqTime;
        final JSONObject event;
        final boolean checked;
        FriendReq(String uid, long reqTime, JSONObject event, boolean checked) {
            this.uid = uid; this.reqTime = reqTime; this.event = event; this.checked = checked;
        }
    }
    private static final class GroupReq {
        final long seq;
        final long groupCode;
        final Object type;
        final JSONObject event;
        final boolean invite;
        final boolean checked;
        GroupReq(long seq, long groupCode, Object type, JSONObject event, boolean invite, boolean checked) {
            this.seq = seq; this.groupCode = groupCode; this.type = type;
            this.event = event; this.invite = invite; this.checked = checked;
        }
    }

    @Override public void onRecvMsgs(List<?> records) {
        long self = selfUin();
        for (Object rec : records) {
            try {
                long msgId = Ref.asLong(qq.ref.get(rec, "msgId"));
                if (msgId != 0 && !seen.add(msgId)) continue; // dedupe
                if (seen.size() > 8000) seen.clear();
                JSONObject ev = conv.recordToEvent(rec, self);
                if (ev != null) {
                    String nt = ev.optString("notice_type", "");
                    if ("group_recall".equals(nt) || "friend_recall".equals(nt)) {
                        int mid = ev.optInt("message_id", 0);
                        if (mid != 0 && !seenRecalls.add(mid)) continue;
                    }
                    server.broadcast(ev.toString());
                    L.d("event -> " + ev.optString("post_type") + "/"
                            + ev.optString("message_type", ev.optString("notice_type"))
                            + " from " + ev.optLong("user_id"));
                }
            } catch (Throwable t) {
                L.e("onRecvMsgs", t);
            }
        }
    }

    @Override public void onRecall(int type, String info, long time) {
        try {
            recallFromCallback(type, info, time);
        } catch (Throwable t) {
            L.e("onRecall", t);
        }
    }

    @Override public void onBuddyReq(Object info) {
        if (info == null) return;
        try {
            Object list = qq.ref.get(info, "buddyReqs");
            if (!(list instanceof List)) return;
            for (Object req : (List<?>) list) {
                JSONObject ev = friendRequestEvent(req);
                if (ev != null) server.broadcast(ev.toString());
            }
        } catch (Throwable t) {
            L.e("onBuddyReq", t);
        }
    }

    @Override public void onGroupNotifies(List<?> notifies) {
        if (notifies == null) return;
        for (Object n : notifies) {
            try {
                JSONObject ev = groupRequestEvent(n);
                if (ev != null) server.broadcast(ev.toString());
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
            Object infosObj = qq.ref.get(change, "infos");
            if (!(infosObj instanceof java.util.Map) || groupId == 0) return;
            boolean add = typeName.contains("ADD");
            boolean remove = typeName.contains("REMOVE");
            if (!add && !remove) return;
            long now = System.currentTimeMillis() / 1000;
            for (Object mi : ((java.util.Map<?, ?>) infosObj).values()) {
                if (mi == null) continue;
                long uin = Ref.asLong(qq.ref.get(mi, "uin"));
                String uid = Ref.asStr(qq.ref.get(mi, "uid"));
                if (uin != 0 && uid != null && !uid.isEmpty()) store.learnUid(uin, uid);
                if (uin == 0) continue;
                String key = groupId + ":" + uin + ":" + (add ? "add" : "rm");
                if (!seenMemberChanges.add(key)) continue;
                if (seenMemberChanges.size() > 8000) seenMemberChanges.clear();
                JSONObject ev = add
                        ? Notices.groupIncrease(selfUin(), now, groupId, uin, selfUin())
                        : Notices.groupDecrease(selfUin(), now, groupId, uin, selfUin(), true);
                server.broadcast(ev.toString());
            }
        } catch (Throwable t) {
            L.e("onMemberListChange", t);
        }
    }

    /**
     * Android 9.3.50: onMsgRecall(chatType, peerUid, msgSeqOrMsgId).
     * Desktop NapCat uses the same shape. JSON info is still accepted if present.
     */
    private void recallFromCallback(int type, String info, long third) throws Exception {
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
            if (peer == 0 && !uid.isEmpty()) {
                try { peer = Long.parseLong(uid); } catch (Exception ignore) {}
            }
            if (j.has("chatType")) group = j.optInt("chatType") == QQClient.CT_GROUP;
        } else {
            msgSeq = third;
            msgId = third;
            try { peer = Long.parseLong(info.trim()); } catch (Exception ignore) {}
        }
        MsgStore.Rec rec = msgId != 0 ? store.getByMsgId(msgId) : null;
        if (rec == null) rec = store.findByPeerSeq(type, peer, info, msgSeq);
        int obId = rec != null ? rec.id : store.idOfMsgId(msgId);
        if (rec != null) {
            if (peer == 0) peer = rec.peerUin;
            if (user == 0) user = rec.senderUin;
            group = rec.chatType == QQClient.CT_GROUP;
        }
        if (obId == 0 && msgId == 0 && peer == 0) return;
        if (obId == 0 && msgId != 0) {
            MsgStore.Rec sr = new MsgStore.Rec();
            sr.chatType = group ? QQClient.CT_GROUP : QQClient.CT_C2C;
            sr.peerUin = peer;
            sr.peerUid = info;
            sr.msgId = msgId;
            sr.msgSeq = msgSeq;
            sr.senderUin = user;
            obId = store.put(sr);
        }
        emitRecall(group, peer, user, operator == 0 ? user : operator, obId);
    }

    private JSONObject friendRequestEvent(Object req) throws Exception {
        if (req == null) return null;
        boolean initiator = Ref.asBool(qq.ref.get(req, "isInitiator"));
        boolean decided = Ref.asBool(qq.ref.get(req, "isDecide"));
        int reqType = Ref.asInt(qq.ref.get(req, "reqType"));
        if (initiator) return null;
        if (decided && reqType != 13) return null; // 13 = KMEINITIATORWAITPEERCONFIRM
        String uid = Ref.asStr(qq.ref.get(req, "friendUid"));
        long reqTime = Ref.asLong(qq.ref.get(req, "reqTime"));
        if (uid.isEmpty() || reqTime == 0) return null;
        String flag = String.valueOf(reqTime);
        long userId = uinFromUid(uid);
        String comment = Ref.asStr(qq.ref.get(req, "extWords"));
        long time = reqTime > 1_000_000_000_000L ? reqTime / 1000 : reqTime;
        JSONObject ev = Notices.friendRequest(selfUin(), time, userId, comment, flag);
        pendingFriends.put(flag, new FriendReq(uid, reqTime, ev, decided));
        if (!seenRequests.add("f:" + flag)) return null;
        if (seenRequests.size() > 8000) seenRequests.clear();
        return ev;
    }

    private JSONObject groupRequestEvent(Object notify) throws Exception {
        if (notify == null) return null;
        Object status = qq.ref.get(notify, "status");
        boolean unhandled = status == null || enumName(status).contains("KUNHANDLE");
        Object type = qq.ref.get(notify, "type");
        String typeName = enumName(type);
        String sub;
        boolean user2 = false;
        boolean invite = false;
        if (typeName.contains("REQUESTJOINNEEDADMINISTRATORPASS")) sub = "add";
        else if (typeName.contains("INVITEDNEEDADMINISTRATORPASS")) sub = "add";
        else if (typeName.contains("INVITEDBYMEMBER")) { sub = "invite"; user2 = true; invite = true; }
        else return null;
        long seq = Ref.asLong(qq.ref.get(notify, "seq"));
        Object group = qq.ref.get(notify, "group");
        long groupCode = group == null ? 0 : Ref.asLong(qq.ref.get(group, "groupCode"));
        Object user = qq.ref.get(notify, user2 ? "user2" : "user1");
        String uid = user == null ? "" : Ref.asStr(qq.ref.get(user, "uid"));
        String flag = String.valueOf(seq);
        if (seq == 0 || groupCode == 0) return null;
        String comment = Ref.asStr(qq.ref.get(notify, "postscript"));
        long actionTime = Ref.asLong(qq.ref.get(notify, "actionTime"));
        long time = actionTime > 0 ? (actionTime > 1_000_000_000_000L ? actionTime / 1000 : actionTime)
                : System.currentTimeMillis() / 1000;
        JSONObject ev = Notices.groupRequest(selfUin(), time, groupCode, uinFromUid(uid), sub, comment, flag);
        pendingGroups.put(flag, new GroupReq(seq, groupCode, type, ev, invite, !unhandled));
        if (!unhandled) return null;
        if (!seenRequests.add("g:" + flag)) return null;
        if (seenRequests.size() > 8000) seenRequests.clear();
        return ev;
    }

    private void replayPendingRequests(WsConn conn) {
        for (FriendReq req : pendingFriends.values()) {
            if (req.event != null && !req.checked) {
                try { conn.send(req.event.toString()); } catch (Throwable ignore) {}
            }
        }
        for (GroupReq req : pendingGroups.values()) {
            if (req.event != null && !req.checked) {
                try { conn.send(req.event.toString()); } catch (Throwable ignore) {}
            }
        }
    }

    private JSONObject getFriendSystemMsg() throws Exception {
        qq.refreshBuddyReqs();
        JSONArray requests = new JSONArray();
        for (java.util.Map.Entry<String, FriendReq> e : pendingFriends.entrySet()) {
            FriendReq req = e.getValue();
            JSONObject ev = req.event;
            requests.put(new JSONObject()
                    .put("request_id", req.reqTime)
                    .put("requester_uin", ev == null ? 0 : ev.optLong("user_id"))
                    .put("message", ev == null ? "" : ev.optString("comment"))
                    .put("flag", e.getKey())
                    .put("checked", req.checked)
                    .put("user_id", ev == null ? 0 : ev.optLong("user_id"))
                    .put("comment", ev == null ? "" : ev.optString("comment")));
        }
        return new JSONObject().put("requests", requests);
    }

    private JSONObject getGroupSystemMsg() throws Exception {
        qq.refreshGroupNotifies();
        JSONArray join = new JSONArray();
        JSONArray invite = new JSONArray();
        for (java.util.Map.Entry<String, GroupReq> e : pendingGroups.entrySet()) {
            GroupReq req = e.getValue();
            JSONObject ev = req.event;
            JSONObject item = new JSONObject()
                    .put("request_id", req.seq)
                    .put("invitor_uin", ev == null ? 0 : ev.optLong("user_id"))
                    .put("invitor_nick", "")
                    .put("group_id", req.groupCode)
                    .put("message", ev == null ? "" : ev.optString("comment"))
                    .put("group_name", "")
                    .put("checked", req.checked)
                    .put("actor", 0)
                    .put("requester_nick", "")
                    .put("flag", e.getKey());
            if (req.invite) invite.put(item);
            else join.put(item);
        }
        return new JSONObject()
                .put("join_requests", join)
                .put("invited_requests", invite)
                .put("InvitedRequest", invite);
    }

    private static String enumName(Object e) {
        if (e == null) return "";
        if (e instanceof Enum) return ((Enum<?>) e).name();
        return String.valueOf(e);
    }

    private long uinFromUid(String uid) {
        if (uid == null || uid.isEmpty()) return 0;
        long uin = store.uinOf(uid);
        if (uin != 0) return uin;
        uin = qq.resolveUin(uid);
        if (uin != 0) store.learnUid(uin, uid);
        return uin;
    }

    private static boolean approveParam(JSONObject p) {
        if (!p.has("approve")) return true;
        Object v = p.opt("approve");
        if (v instanceof Boolean) return (Boolean) v;
        String s = String.valueOf(v);
        return !"false".equalsIgnoreCase(s) && !"0".equals(s);
    }

    private void setFriendAddRequest(String flag, boolean approve, String remark) {
        if (flag == null || flag.isEmpty()) throw new ApiError(1400, "missing flag");
        FriendReq req = pendingFriends.get(flag);
        if (req == null) {
            qq.refreshBuddyReqs();
            req = pendingFriends.get(flag);
        }
        long reqTime = req != null ? req.reqTime : parseLongQuiet(flag);
        String uid = req != null ? req.uid : "";
        if (uid.isEmpty()) throw new ApiError(1404, "unknown friend request flag");
        requireOp(qq.approvalFriendRequest(uid, approve, approve ? "" : remark, reqTime));
        pendingFriends.remove(flag);
    }

    private void setGroupAddRequest(String flag, boolean approve, String reason) {
        if (flag == null || flag.isEmpty()) throw new ApiError(1400, "missing flag");
        GroupReq req = pendingGroups.get(flag);
        if (req == null) {
            qq.refreshGroupNotifies();
            req = pendingGroups.get(flag);
        }
        if (req == null) throw new ApiError(1404, "unknown group request flag");
        requireOp(qq.operateGroupNotify(req.seq, req.groupCode, req.type, approve, reason));
        pendingGroups.remove(flag);
    }

    // ============ lifecycle / online status / heartbeat ============
    private void startStatusMonitor() {
        Thread t = new Thread(() -> {
            boolean previous = qq.isOnline();
            onlineSinceMs = previous ? System.currentTimeMillis() : 0;
            long interval = Math.max(1000L, cfg.heartbeatMs);
            long nextHeartbeat = System.currentTimeMillis() + interval;
            while (true) {
                try {
                    Thread.sleep(1000);
                    boolean online = qq.isOnline();
                    long now = System.currentTimeMillis();
                    if (online != previous) {
                        onlineSinceMs = online ? now : 0;
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
        return new JSONObject()
                .put("online", online)
                .put("good", online)
                .put("online_since_epoch_ms", onlineSinceMs)
                .put("outbound_guard", outboundGuard.stats())
                .put("fekit_attach", AntiDetect.fekitAttachStats(cfg.observeFekitAttach));
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
        String qqVersion = qq.qqVersion();
        return new JSONObject()
                .put("app_name", "onebot-qq")
                .put("app_version", "0.5.3")
                .put("protocol_version", "v11")
                .put("qq_version", qqVersion.isEmpty() ? "unknown" : qqVersion)
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
