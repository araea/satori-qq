package com.onebot.qq.core;

import com.onebot.qq.Cfg;
import com.onebot.qq.L;
import com.onebot.qq.net.WsConn;
import com.onebot.qq.net.WsServer;
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
        if (cfg.heartbeat) startHeartbeat();
    }

    private long selfUin() { try { return Long.parseLong(qq.selfUin()); } catch (Throwable t) { return 0; } }

    // ============ WS inbound: OneBot actions ============
    @Override public void onText(WsConn conn, String text) {
        JSONObject req;
        try { req = new JSONObject(text); } catch (Throwable t) { return; }
        String action = req.optString("action", "");
        Object echo = req.has("echo") ? req.opt("echo") : null;
        JSONObject params = req.optJSONObject("params");
        if (params == null) params = new JSONObject();
        try {
            JSONObject data = dispatch(action, params);
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

    private JSONObject dispatch(String action, JSONObject p) throws Exception {
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
            case "delete_msg": {
                recall(p.optInt("message_id", 0));
                return new JSONObject();
            }
            case "get_msg": return getMsg(p.optInt("message_id", 0));
            // ---- not yet implemented (milestone 2) ----
            case "get_group_list":
            case "get_group_member_info":
            case "get_forward_msg":
            case "send_like":
            case "set_group_special_title":
            case "set_msg_emoji_like":
            case "upload_group_file":
            case "upload_private_file":
                throw new ApiError(1404, "action not implemented yet: " + action);
            default:
                throw new ApiError(1404, "unknown action: " + action);
        }
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

    // ============ heartbeat ============
    private void startHeartbeat() {
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(cfg.heartbeatMs);
                    if (server.connectionCount() == 0) continue;
                    JSONObject hb = new JSONObject();
                    hb.put("time", System.currentTimeMillis() / 1000);
                    hb.put("self_id", selfUin());
                    hb.put("post_type", "meta_event");
                    hb.put("meta_event_type", "heartbeat");
                    hb.put("interval", cfg.heartbeatMs);
                    JSONObject st = new JSONObject().put("online", true).put("good", true);
                    hb.put("status", st);
                    server.broadcast(hb.toString());
                } catch (InterruptedException ie) { return; }
                catch (Throwable ignore) {}
            }
        }, "onebot-heartbeat");
        t.setDaemon(true);
        t.start();
    }

    // ============ response envelopes ============
    private JSONObject ok(JSONObject data, Object echo) {
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
