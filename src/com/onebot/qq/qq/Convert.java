package com.onebot.qq.qq;

import com.onebot.qq.L;
import com.onebot.qq.core.MsgStore;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;

/** Converts between OneBot 11 message segments and QQ NT MsgElement objects,
 *  and turns a received MsgRecord into a OneBot 11 event object. */
public final class Convert {
    private final Ref ref;
    private final QQClient qq;
    private final MsgStore store;

    public Convert(QQClient qq, MsgStore store) { this.qq = qq; this.ref = qq.ref; this.store = store; }

    // ---------------- OneBot segments -> QQ elements (for sending) ----------------

    /** message may be a JSONArray of segments or a plain String. */
    public ArrayList<Object> toElements(Object message, int chatType) {
        ArrayList<Object> out = new ArrayList<>();
        JSONArray arr = normalize(message);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject seg = arr.optJSONObject(i);
            if (seg == null) continue;
            String type = seg.optString("type", "text");
            JSONObject d = seg.optJSONObject("data");
            if (d == null) d = new JSONObject();
            try {
                switch (type) {
                    case "text": addText(out, d.optString("text", "")); break;
                    case "at":   addAt(out, d.optString("qq", "")); break;
                    case "face": addFace(out, d.optString("id", "0")); break;
                    case "reply":addReply(out, d.optString("id", "")); break;
                    // media types need rich-media upload (milestone 2); degrade gracefully:
                    case "image": addText(out, "[图片]"); break;
                    case "record":addText(out, "[语音]"); break;
                    case "video": addText(out, "[视频]"); break;
                    default:
                        String t = d.optString("text", "");
                        if (!t.isEmpty()) addText(out, t);
                        break;
                }
            } catch (Throwable e) {
                L.e("toElements seg " + type, e);
            }
        }
        if (out.isEmpty()) addText(out, "");
        return out;
    }

    private JSONArray normalize(Object message) {
        if (message instanceof JSONArray) return (JSONArray) message;
        JSONArray a = new JSONArray();
        JSONObject seg = new JSONObject();
        try { seg.put("type", "text"); seg.put("data", new JSONObject().put("text", String.valueOf(message))); } catch (Exception ignore) {}
        a.put(seg);
        return a;
    }

    private Object newElement(int type) {
        Object e = ref.neu(QQClient.MSG_ELEMENT);
        ref.set(e, "elementType", type);
        return e;
    }

    private void addText(ArrayList<Object> out, String text) {
        Object e = newElement(1);
        Object t = ref.neu(QQClient.TEXT_ELEMENT);
        ref.set(t, "content", text == null ? "" : text);
        ref.set(e, "textElement", t);
        out.add(e);
    }

    private void addAt(ArrayList<Object> out, String qq) {
        Object e = newElement(1);
        Object t = ref.neu(QQClient.TEXT_ELEMENT);
        if ("all".equalsIgnoreCase(qq)) {
            ref.set(t, "content", "@全体成员");
            ref.set(t, "atType", 1);
        } else {
            long uin = parseLong(qq);
            String uid = store.uidOf(uin);
            ref.set(t, "content", "@" + qq);
            ref.set(t, "atType", 2);
            ref.set(t, "atUid", uin);
            if (uid != null && !uid.isEmpty()) ref.set(t, "atNtUid", uid);
        }
        ref.set(e, "textElement", t);
        out.add(e);
    }

    private void addFace(ArrayList<Object> out, String id) {
        Object e = newElement(6);
        Object f = ref.neu("com.tencent.qqnt.kernel.nativeinterface.FaceElement");
        int fid = (int) parseLong(id);
        ref.set(f, "faceIndex", fid);
        ref.set(f, "faceType", 1);
        ref.set(e, "faceElement", f);
        out.add(e);
    }

    private void addReply(ArrayList<Object> out, String id) {
        MsgStore.Rec r = store.get((int) parseLong(id));
        if (r == null) return;
        Object e = newElement(7);
        Object rp = ref.neu("com.tencent.qqnt.kernel.nativeinterface.ReplyElement");
        ref.set(rp, "replayMsgSeq", r.msgSeq);
        ref.set(rp, "replayMsgId", r.msgId);
        if (r.senderUid != null && !r.senderUid.isEmpty()) ref.set(rp, "senderUidStr", r.senderUid);
        ref.set(e, "replyElement", rp);
        out.add(e);
    }

    // ---------------- QQ MsgRecord -> OneBot event ----------------

    /** Returns a message event JSONObject, or null if it should be skipped. */
    public JSONObject recordToEvent(Object rec, long selfUin) {
        int chatType = ref.asInt(ref.get(rec, "chatType"));
        if (chatType != QQClient.CT_C2C && chatType != QQClient.CT_GROUP) return null;

        long msgId = Ref.asLong(ref.get(rec, "msgId"));
        long msgSeq = Ref.asLong(ref.get(rec, "msgSeq"));
        long peerUin = Ref.asLong(ref.get(rec, "peerUin"));
        long senderUin = Ref.asLong(ref.get(rec, "senderUin"));
        String senderUid = Ref.asStr(ref.get(rec, "senderUid"));
        String peerUid = Ref.asStr(ref.get(rec, "peerUid"));
        String nick = Ref.asStr(ref.get(rec, "sendNickName"));
        String card = Ref.asStr(ref.get(rec, "sendMemberName"));
        long msgTime = Ref.asLong(ref.get(rec, "msgTime"));

        store.learnUid(senderUin, senderUid);
        if (senderUin != 0 && String.valueOf(senderUin).equals(String.valueOf(selfUin))) return null; // skip self

        MsgStore.Rec sr = new MsgStore.Rec();
        sr.chatType = chatType; sr.peerUin = peerUin; sr.peerUid = peerUid;
        sr.msgId = msgId; sr.msgSeq = msgSeq; sr.senderUin = senderUin; sr.senderUid = senderUid;
        sr.msgRecord = rec;
        int obId = store.put(sr);

        Object elements = ref.get(rec, "elements");
        JSONArray segs = new JSONArray();
        StringBuilder raw = new StringBuilder();
        parseElements(elements, segs, raw);

        try {
            JSONObject ev = new JSONObject();
            ev.put("time", msgTime > 0 ? msgTime : System.currentTimeMillis() / 1000);
            ev.put("self_id", selfUin);
            ev.put("post_type", "message");
            ev.put("message_id", obId);
            ev.put("user_id", senderUin);
            ev.put("message", segs);
            ev.put("raw_message", raw.toString());
            ev.put("font", 0);
            ev.put("message_seq", msgSeq);
            JSONObject sender = new JSONObject();
            sender.put("user_id", senderUin);
            sender.put("nickname", nick);
            if (chatType == QQClient.CT_GROUP) {
                ev.put("message_type", "group");
                ev.put("sub_type", "normal");
                ev.put("group_id", peerUin);
                sender.put("card", card == null ? "" : card);
                sender.put("role", "member");
            } else {
                ev.put("message_type", "private");
                ev.put("sub_type", "friend");
            }
            ev.put("sender", sender);
            return ev;
        } catch (Throwable e) {
            L.e("recordToEvent", e);
            return null;
        }
    }

    private void parseElements(Object elements, JSONArray segs, StringBuilder raw) {
        if (!(elements instanceof java.util.List)) return;
        java.util.List<?> list = (java.util.List<?>) elements;
        for (Object e : list) {
            if (e == null) continue;
            try {
                int et = ref.asInt(ref.get(e, "elementType"));
                switch (et) {
                    case 1: { // text / at
                        Object t = ref.get(e, "textElement");
                        String content = Ref.asStr(ref.get(t, "content"));
                        int atType = ref.asInt(ref.get(t, "atType"));
                        if (atType != 0) {
                            long atUin = Ref.asLong(ref.get(t, "atUid"));
                            if (atType == 1 || atUin == 0) {
                                seg(segs, "at", "qq", "all");
                                raw.append("[CQ:at,qq=all]");
                            } else {
                                seg(segs, "at", "qq", String.valueOf(atUin));
                                raw.append("[CQ:at,qq=").append(atUin).append("]");
                            }
                        } else {
                            seg(segs, "text", "text", content);
                            raw.append(content);
                        }
                        break;
                    }
                    case 6: { // face
                        Object f = ref.get(e, "faceElement");
                        int fid = ref.asInt(ref.get(f, "faceIndex"));
                        seg(segs, "face", "id", String.valueOf(fid));
                        raw.append("[CQ:face,id=").append(fid).append("]");
                        break;
                    }
                    case 2: { // pic
                        Object p = ref.get(e, "picElement");
                        String md5 = Ref.asStr(ref.get(p, "md5HexStr"));
                        String url = safeStr(p, "originImageUrl");
                        String path = safeStr(p, "sourcePath");
                        String file = md5.isEmpty() ? safeStr(p, "fileName") : (md5 + ".image");
                        JSONObject d = new JSONObject();
                        try {
                            d.put("file", file);
                            if (!url.isEmpty()) d.put("url", url.startsWith("http") ? url : ("https://gchat.qpic.cn" + url));
                            if (!path.isEmpty()) d.put("path", path);
                        } catch (Exception ignore) {}
                        segObj(segs, "image", d);
                        raw.append("[CQ:image,file=").append(file).append("]");
                        break;
                    }
                    case 7: { // reply
                        Object rp = ref.get(e, "replyElement");
                        long seq = Ref.asLong(ref.get(rp, "replayMsgSeq"));
                        seg(segs, "reply", "id", String.valueOf(seq));
                        raw.append("[CQ:reply,id=").append(seq).append("]");
                        break;
                    }
                    default:
                        break;
                }
            } catch (Throwable ex) {
                L.e("parseElements", ex);
            }
        }
    }

    private String safeStr(Object o, String f) { try { return Ref.asStr(ref.get(o, f)); } catch (Throwable t) { return ""; } }

    private void seg(JSONArray arr, String type, String k, String v) {
        try {
            JSONObject d = new JSONObject().put(k, v);
            segObj(arr, type, d);
        } catch (Exception ignore) {}
    }
    private void segObj(JSONArray arr, String type, JSONObject data) {
        try { arr.put(new JSONObject().put("type", type).put("data", data)); } catch (Exception ignore) {}
    }

    private static long parseLong(String s) { try { return Long.parseLong(s.trim()); } catch (Throwable t) { return 0; } }
}
