package com.satori.qq.qq;

import com.satori.qq.L;
import com.satori.qq.core.MsgStore;
import com.satori.qq.core.Notices;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;

/** Converts between internal message segments and QQ NT MsgElement objects,
 *  and turns a received MsgRecord into an event object for the Satori hub. */
public final class Convert {
    private final Ref ref;
    private final QQClient qq;
    private final MsgStore store;

    public Convert(QQClient qq, MsgStore store) { this.qq = qq; this.ref = qq.ref; this.store = store; }

    // ---------------- internal segments -> QQ elements (for sending) ----------------

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
                    case "image": addImage(out, d); break;
                    case "json":  addArk(out, d.optString("data", d.optString("content", ""))); break;
                    case "lightapp": addArk(out, d.optString("content", d.optString("data", ""))); break;
                    case "mface": addMface(out, d); break;
                    case "poke":  addPoke(out, d); break;
            case "record":addRecord(out, d); break;
                    case "file":  addFile(out, d); break;
                    case "video": addVideo(out, d); break;
                    default:
                        String t = d.optString("text", "");
                        if (!t.isEmpty()) addText(out, t);
                        break;
                }
            } catch (Throwable e) {
                L.e("toElements seg " + type, e);
                if ("record".equals(type) || "video".equals(type) || "file".equals(type)
                        || "image".equals(type)) {
                    throw (e instanceof RuntimeException) ? (RuntimeException) e
                            : new IllegalStateException(type + " send failed", e);
                }
            }
        }
        if (out.isEmpty()) {
            boolean media = false;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject seg = arr.optJSONObject(i);
                if (seg == null) continue;
                String type = seg.optString("type", "");
                if ("image".equals(type) || "record".equals(type) || "video".equals(type)
                        || "file".equals(type)) media = true;
            }
            if (media) throw new IllegalStateException("media send produced no elements");
            addText(out, "");
        }
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

    private static final String ARK_ELEMENT = "com.tencent.qqnt.kernel.nativeinterface.ArkElement";
    private static final String LINK_INFO = "com.tencent.qqnt.kernel.nativeinterface.LinkInfo";
    private static final String MARKET_FACE_ELEMENT = "com.tencent.qqnt.kernel.nativeinterface.MarketFaceElement";
    private static final String FACE_ELEMENT = "com.tencent.qqnt.kernel.nativeinterface.FaceElement";
    private static final String MULTI_FORWARD_ELEMENT =
            "com.tencent.qqnt.kernel.nativeinterface.MultiForwardMsgElement";

    /**
     * Merge-forward card -> KELEMTYPEMULTIFORWARD(16). Android QQNT opens via getMultiMsg using
     * resId + fileName; xmlContent on this version is the multimsg JSON (not the old serviceID=35 XML).
     */
    public ArrayList<Object> toMultiForward(String xmlContent, String resId, String fileName) {
        ArrayList<Object> out = new ArrayList<>();
        addMultiForward(out, xmlContent, resId, fileName);
        return out;
    }

    public ArrayList<Object> toStructLongMsg(String xmlContent, String resId) {
        ArrayList<Object> out = new ArrayList<>();
        addStructLongMsg(out, xmlContent, resId);
        return out;
    }

    private void addMultiForward(ArrayList<Object> out, String xmlContent, String resId, String fileName) {
        String xml = xmlContent == null ? "" : xmlContent;
        String id = resId == null ? "" : resId;
        String name = fileName == null ? "" : fileName;
        try {
            Object e = newElement(16);
            Object mf = ref.neu(MULTI_FORWARD_ELEMENT, xml, id, name);
            ref.set(e, "multiForwardMsgElement", mf);
            out.add(e);
            return;
        } catch (Throwable t) {
            L.e("addMultiForward", t);
        }
        try {
            Object e = newElement(16);
            Object mf = ref.neu(MULTI_FORWARD_ELEMENT);
            ref.set(mf, "xmlContent", xml);
            ref.set(mf, "resId", id);
            ref.set(mf, "fileName", name);
            ref.set(e, "multiForwardMsgElement", mf);
            out.add(e);
        } catch (Throwable t2) {
            L.e("addMultiForward fallback", t2);
        }
    }

    private static final String STRUCT_LONG_ELEMENT =
            "com.tencent.qqnt.kernel.nativeinterface.StructLongMsgElement";

    private void addStructLongMsg(ArrayList<Object> out, String xmlContent, String resId) {
        String xml = xmlContent == null ? "" : xmlContent;
        String id = resId == null ? "" : resId;
        try {
            Object e = newElement(13);
            Object sl = ref.neu(STRUCT_LONG_ELEMENT, xml, id);
            ref.set(e, "structLongMsgElement", sl);
            out.add(e);
        } catch (Throwable t) {
            L.e("addStructLongMsg", t);
            try {
                Object e = newElement(13);
                Object sl = ref.neu(STRUCT_LONG_ELEMENT);
                ref.set(sl, "xmlContent", xml);
                ref.set(sl, "resId", id);
                ref.set(e, "structLongMsgElement", sl);
                out.add(e);
            } catch (Throwable t2) {
                L.e("addStructLongMsg fallback", t2);
            }
        }
    }

    /** JSON / lightapp ark card -> KELEMTYPEARKSTRUCT(10). */
    private void addArk(ArrayList<Object> out, String json) {
        if (json == null || json.isEmpty()) return;
        try {
            Object e = newElement(10);
            Object ark = ref.neuTyped(ARK_ELEMENT,
                    new Class[]{String.class, ref.cls(LINK_INFO), Integer.class},
                    new Object[]{json, null, 0});
            ref.set(e, "arkElement", ark);
            out.add(e);
        } catch (Throwable t) {
            L.e("addArk", t);
            // fallback: default ctor + set field
            try {
                Object e = newElement(10);
                Object ark = ref.neu(ARK_ELEMENT);
                ref.set(ark, "bytesData", json);
                ref.set(e, "arkElement", ark);
                out.add(e);
            } catch (Throwable t2) { L.e("addArk fallback", t2); }
        }
    }

    /** Market face (商城表情) -> KELEMTYPEMARKETFACE(11). */
    private void addMface(ArrayList<Object> out, org.json.JSONObject d) {
        try {
            Object e = newElement(11);
            Object mf = ref.neu(MARKET_FACE_ELEMENT);
            ref.set(mf, "emojiId", d.optString("emoji_id", ""));
            ref.set(mf, "emojiPackageId", (int) parseLong(d.optString("emoji_package_id", "0")));
            ref.set(mf, "key", d.optString("key", ""));
            ref.set(mf, "faceName", d.optString("summary", "[表情]"));
            ref.set(e, "marketFaceElement", mf);
            out.add(e);
        } catch (Throwable t) { L.e("addMface", t); }
    }

    /** Poke (戳一戳) as a FaceElement -> KELEMTYPEFACE(6). */
    private void addPoke(ArrayList<Object> out, org.json.JSONObject d) {
        try {
            Object e = newElement(6);
            Object f = ref.neu(FACE_ELEMENT);
            int type = (int) parseLong(d.optString("type", d.optString("id", "1")));
            ref.set(f, "faceType", 5);
            ref.set(f, "faceIndex", 0);
            ref.set(f, "pokeType", Integer.valueOf(type));
            ref.set(f, "pokeStrength", Integer.valueOf(0));
            ref.set(e, "faceElement", f);
            out.add(e);
        } catch (Throwable t) { L.e("addPoke", t); }
    }

    private void addImage(ArrayList<Object> out, org.json.JSONObject d) {
        String file = d.optString("file", "");
        String url = d.optString("url", "");
        java.io.File f = Media.resolve(file, url);
        if (f == null) throw new IllegalStateException("image send failed: unresolved " + clipSpec(file, url));
        Object msgService = qq.getMsgService();
        if (msgService == null) throw new IllegalStateException("image send failed: no msgService");
        Object elem = Media.buildPicElement(ref, msgService, f);
        if (elem != null) out.add(elem);
        else throw new IllegalStateException("image send failed: buildPicElement " + f.getName());
    }

    private static String clipSpec(String file, String url) {
        String s = (file != null && !file.isEmpty()) ? file : (url == null ? "" : url);
        if (s.length() > 96) s = s.substring(0, 96) + "...";
        return s.replace('\n', ' ');
    }

    private void addRecord(ArrayList<Object> out, org.json.JSONObject d) {
        java.io.File f = Media.prepareVoice(ref, Media.resolve(d.optString("file", ""), d.optString("url", "")));
        Object msgService = qq.getMsgService();
        Object elem = (f != null && msgService != null) ? Media.buildPttElement(ref, msgService, f) : null;
        if (elem != null) out.add(elem);
        else throw new IllegalStateException("record transcode/send failed");
    }

    private void addFile(ArrayList<Object> out, org.json.JSONObject d) {
        java.io.File f = Media.resolve(d.optString("file", ""), d.optString("url", ""));
        Object msgService = qq.getMsgService();
        Object elem = (f != null && msgService != null)
                ? Media.buildFileElement(ref, msgService, f, d.optString("name", "")) : null;
        if (elem != null) out.add(elem);
        else throw new IllegalStateException("file send failed");
    }

    private void addVideo(ArrayList<Object> out, org.json.JSONObject d) {
        java.io.File f = Media.resolve(d.optString("file", ""), d.optString("url", ""));
        Object msgService = qq.getMsgService();
        Object elem = (f != null && msgService != null) ? Media.buildVideoElement(ref, msgService, f) : null;
        if (elem != null) out.add(elem);
        else throw new IllegalStateException("video send failed");
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

    // ---------------- QQ MsgRecord -> Satori/internal event ----------------

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

        JSONObject notice = noticeFromRecord(rec, selfUin, chatType, peerUin, peerUid,
                senderUin, senderUid, msgId, msgSeq, msgTime);
        if (notice != null) return notice;

        // Include messages typed in this QQ client. Bot-originated echoes are
        // suppressed in SatoriHub (outboundEcho / seen), not here.

        MsgStore.Rec sr = new MsgStore.Rec();
        sr.chatType = chatType; sr.peerUin = peerUin; sr.peerUid = peerUid;
        sr.msgId = msgId; sr.msgSeq = msgSeq; sr.senderUin = senderUin; sr.senderUid = senderUid;
        sr.msgRecord = rec;
        int obId = store.put(sr);

        Object elements = ref.get(rec, "elements");
        JSONArray segs = new JSONArray();
        StringBuilder raw = new StringBuilder();
        String resourcePeer = chatType == QQClient.CT_GROUP ? String.valueOf(peerUin) : peerUid;
        parseElements(elements, segs, raw, chatType, resourcePeer, msgId);

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

    private void parseElements(Object elements, JSONArray segs, StringBuilder raw,
                               int chatType, String peerUid, long msgId) {
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
                        String url = normalizeUrl(safeStr(p, "originImageUrl"));
                        String path = safeStr(p, "sourcePath");
                        String file = md5.isEmpty() ? safeStr(p, "fileName") : (md5 + ".image");
                        String name = safeStr(p, "fileName");
                        long size = Ref.asLong(ref.get(p, "fileSize"));
                        file = store.putResource("image", file, path, url, name, size);
                        attachResource(file, e, p, chatType, peerUid, msgId);
                        JSONObject d = new JSONObject();
                        try {
                            d.put("file", file);
                            if (!url.isEmpty()) d.put("url", url);
                            if (!path.isEmpty()) d.put("path", path);
                            if (size > 0) d.put("file_size", size);
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
                    case 3: { // file
                        Object f = ref.get(e, "fileElement");
                        String name = safeStr(f, "fileName");
                        String path = safeStr(f, "filePath");
                        String url = firstNonEmpty(safeStr(f, "fileUrl"), safeStr(f, "url"));
                        long size = Ref.asLong(ref.get(f, "fileSize"));
                        String file = firstNonEmpty(safeStr(f, "fileUuid"), safeStr(f, "fileSubId"));
                        file = store.putResource("file", file, path, url, name, size);
                        attachResource(file, e, f, chatType, peerUid, msgId);
                        JSONObject d = new JSONObject();
                        d.put("file", file);
                        d.put("file_id", file);
                        d.put("name", name);
                        if (!path.isEmpty()) d.put("path", path);
                        if (!url.isEmpty()) d.put("url", url);
                        d.put("size", size);
                        if (size > 0) d.put("file_size", size);
                        segObj(segs, "file", d);
                        raw.append("[CQ:file,file=").append(d.optString("file")).append("]");
                        break;
                    }
                    case 4: { // record / ptt
                        Object ptt = ref.get(e, "pttElement");
                        JSONObject d = new JSONObject();
                        String file = safeStr(ptt, "fileUuid");
                        if (file.isEmpty()) file = safeStr(ptt, "fileName");
                        String path = safeStr(ptt, "filePath");
                        String url = firstNonEmpty(safeStr(ptt, "originPttUrl"), safeStr(ptt, "fileUrl"));
                        long size = Ref.asLong(ref.get(ptt, "fileSize"));
                        file = store.putResource("record", file, path, url, safeStr(ptt, "fileName"), size);
                        attachResource(file, e, ptt, chatType, peerUid, msgId);
                        d.put("file", file);
                        if (!path.isEmpty()) d.put("path", path);
                        if (!url.isEmpty()) d.put("url", url);
                        d.put("duration", Ref.asInt(ref.get(ptt, "duration")));
                        segObj(segs, "record", d);
                        raw.append("[CQ:record,file=").append(file).append("]");
                        break;
                    }
                    case 5: { // video
                        Object v = ref.get(e, "videoElement");
                        JSONObject d = new JSONObject();
                        String file = firstNonEmpty(safeStr(v, "fileUuid"),
                                firstNonEmpty(safeStr(v, "videoMd5"), safeStr(v, "fileName")));
                        String path = safeStr(v, "filePath");
                        String url = firstNonEmpty(safeStr(v, "fileUrl"), safeStr(v, "videoUrl"));
                        long size = Ref.asLong(ref.get(v, "fileSize"));
                        String name = safeStr(v, "fileName");
                        file = store.putResource("video", file, path, url, name, size);
                        attachResource(file, e, v, chatType, peerUid, msgId);
                        d.put("file", file);
                        d.put("file_id", file);
                        if (!path.isEmpty()) d.put("path", path);
                        if (!url.isEmpty()) d.put("url", url);
                        if (size > 0) d.put("file_size", size);
                        segObj(segs, "video", d);
                        raw.append("[CQ:video,file=").append(file).append("]");
                        break;
                    }
                    case 10: { // ark/json card; multimsg ark is still a merge-forward
                        Object ark = ref.get(e, "arkElement");
                        String data = safeStr(ark, "bytesData");
                        String resid = multimsgResId(data);
                        if (resid != null || multimsgRoot(data) != null) {
                            JSONObject d = new JSONObject();
                            // Native multiForwardMsg cards can omit resid after the kernel rewrites
                            // element 16 to ark element 10. getMultiMsg still resolves them by msgId.
                            d.put("id", resid != null ? resid : nativeForwardId(msgId));
                            d.put("content", data);
                            d.put("element_type", 10);
                            String fn = multimsgFileName(data);
                            if (fn != null) d.put("filename", fn);
                            segObj(segs, "forward", d);
                            raw.append("[转发消息]");
                        } else {
                            seg(segs, "json", "data", data);
                            raw.append("[CQ:json,data=").append(data).append("]");
                        }
                        break;
                    }
                    case 13: { // struct long msg
                        Object sl = ref.get(e, "structLongMsgElement");
                        String id = safeStr(sl, "resId");
                        JSONObject d = new JSONObject();
                        d.put("id", id);
                        String xml = safeStr(sl, "xmlContent");
                        if (!xml.isEmpty()) d.put("content", xml);
                        d.put("element_type", 13);
                        segObj(segs, "forward", d);
                        raw.append("[转发消息]");
                        break;
                    }
                    case 16: { // native merge-forward
                        Object mf = ref.get(e, "multiForwardMsgElement");
                        String id = safeStr(mf, "resId");
                        JSONObject d = new JSONObject();
                        String xml = safeStr(mf, "xmlContent");
                        if (id.isEmpty()) {
                            String fromJson = multimsgResId(xml);
                            if (fromJson != null) id = fromJson;
                        }
                        if (id.isEmpty()) id = nativeForwardId(msgId);
                        d.put("id", id);
                        if (!xml.isEmpty()) d.put("content", xml);
                        String fileName = safeStr(mf, "fileName");
                        if (fileName.isEmpty()) fileName = multimsgFileName(xml);
                        if (fileName != null && !fileName.isEmpty()) d.put("filename", fileName);
                        d.put("element_type", 16);
                        segObj(segs, "forward", d);
                        raw.append("[转发消息]");
                        break;
                    }
                    case 11: { // market face
                        Object mf = ref.get(e, "marketFaceElement");
                        JSONObject d = new JSONObject();
                        d.put("emoji_id", safeStr(mf, "emojiId"));
                        d.put("emoji_package_id", Ref.asInt(ref.get(mf, "emojiPackageId")));
                        d.put("key", safeStr(mf, "key"));
                        d.put("summary", safeStr(mf, "faceName"));
                        segObj(segs, "mface", d);
                        raw.append("[商城表情]");
                        break;
                    }
                    default:
                        L.e("unparsed elementType=" + et, null);
                        break;
                }
            } catch (Throwable ex) {
                L.e("parseElements", ex);
            }
        }
    }

    /**
     * Gray-tip MsgRecord → notice, or null if this is a normal chat message.
     * elementType 8 = grayTip; sub 1 revoke / 4 group / 17 json (poke busiId 1061).
     */
    public JSONObject noticeFromRecord(Object rec, long selfUin, int chatType, long peerUin,
                                       String peerUid, long senderUin, String senderUid,
                                       long msgId, long msgSeq, long msgTime) {
        Object elements = ref.get(rec, "elements");
        if (!(elements instanceof java.util.List)) return null;
        for (Object e : (java.util.List<?>) elements) {
            if (e == null) continue;
            if (ref.asInt(ref.get(e, "elementType")) != 8) continue;
            Object gray = ref.get(e, "grayTipElement");
            if (gray == null) continue;
            try {
                JSONObject n = grayToNotice(gray, selfUin, chatType, peerUin, senderUin,
                        senderUid, msgId, msgSeq, msgTime);
                if (n != null) return n;
            } catch (Throwable t) {
                L.e("grayTip notice", t);
            }
        }
        return null;
    }

    private JSONObject grayToNotice(Object gray, long selfUin, int chatType, long peerUin,
                                    long senderUin, String senderUid, long msgId, long msgSeq,
                                    long msgTime) throws Exception {
        int sub = ref.asInt(ref.get(gray, "subElementType"));
        long time = msgTime > 0 ? msgTime : System.currentTimeMillis() / 1000;
        boolean group = chatType == QQClient.CT_GROUP;
        String jsonStr = "";
        String busi = "";
        String xml = "";

        if (sub == 1 || ref.get(gray, "revokeElement") != null) {
            Object rev = ref.get(gray, "revokeElement");
            if (rev == null && sub != 1) return null;
            String opUid = rev == null ? "" : Ref.asStr(ref.get(rev, "operatorUid"));
            long operator = store.uinOf(opUid);
            if (operator == 0) operator = senderUin;
            if (opUid != null && !opUid.isEmpty() && operator != 0) store.learnUid(operator, opUid);
            int obId = store.idOfMsgId(msgId);
            if (obId == 0) {
                MsgStore.Rec sr = new MsgStore.Rec();
                sr.chatType = chatType;
                sr.peerUin = peerUin;
                sr.msgId = msgId;
                sr.msgSeq = msgSeq;
                sr.senderUin = senderUin;
                sr.senderUid = senderUid;
                obId = store.put(sr);
            }
            return Notices.recall(selfUin, time, group, peerUin, senderUin, operator, obId);
        }

        Object js = ref.get(gray, "jsonGrayTipElement");
        if (js != null) {
            busi = Ref.asStr(ref.get(js, "busiId"));
            if (busi.isEmpty()) busi = String.valueOf(Ref.asLong(ref.get(js, "busiId")));
            jsonStr = Ref.asStr(ref.get(js, "jsonStr"));
            JSONObject poke = Notices.pokeFromJson(jsonStr, selfUin, time, group, peerUin);
            if (poke != null) {
                long sender = store.uinOf(poke.optString("sender_uid"));
                long target = store.uinOf(poke.optString("target_uid"));
                if (sender != 0) poke.put("user_id", sender);
                if (target != 0) poke.put("target_id", target);
                if (!group) poke.put("sender_id", sender);
                poke.remove("sender_uid");
                poke.remove("target_uid");
                return poke;
            }
        }

        Object xmlEl = ref.get(gray, "xmlElement");
        if (xmlEl != null) {
            xml = Ref.asStr(ref.get(xmlEl, "content"));
            JSONObject pokeXml = Notices.pokeFromXml(xml, selfUin, time, group, peerUin);
            if (pokeXml != null) {
                long sender = store.uinOf(pokeXml.optString("sender_uid"));
                long target = store.uinOf(pokeXml.optString("target_uid"));
                if (sender != 0) pokeXml.put("user_id", sender);
                if (target != 0) pokeXml.put("target_id", target);
                if (!group) pokeXml.put("sender_id", sender);
                pokeXml.remove("sender_uid");
                pokeXml.remove("target_uid");
                return pokeXml;
            }
            if (group) {
                JSONObject banXml = Notices.banFromXml(xml, selfUin, time, peerUin);
                if (banXml != null) {
                    long member = store.uinOf(banXml.optString("member_uid"));
                    long admin = store.uinOf(banXml.optString("admin_uid"));
                    if (member == selfUin && admin != 0 && admin != selfUin) {
                        long tmp = member;
                        member = admin;
                        admin = tmp;
                    }
                    if (member != 0) banXml.put("user_id", member);
                    if (admin != 0) banXml.put("operator_id", admin);
                    banXml.remove("member_uid");
                    banXml.remove("admin_uid");
                    return banXml;
                }
                JSONObject change = Notices.memberChangeFromXml(xml, selfUin, time, peerUin);
                if (change != null) {
                    applyXmlPeople(change, selfUin);
                    return change;
                }
            }
        }

        Object ge = ref.get(gray, "groupElement");
        if (ge != null) {
            int type = ref.asInt(ref.get(ge, "type"));
            String memberUid = Ref.asStr(ref.get(ge, "memberUid"));
            String adminUid = Ref.asStr(ref.get(ge, "adminUid"));
            Object shut = ref.get(ge, "shutUp");
            if ((type == 8 || shut != null) && group) {
                if (shut != null) {
                    Object mem = ref.get(shut, "member");
                    Object adm = ref.get(shut, "admin");
                    if (memberUid.isEmpty() && mem != null) memberUid = Ref.asStr(ref.get(mem, "uid"));
                    if (adminUid.isEmpty() && adm != null) adminUid = Ref.asStr(ref.get(adm, "uid"));
                }
                long member = store.uinOf(memberUid);
                long admin = store.uinOf(adminUid);
                if (member != 0) {
                    long duration = 0;
                    if (shut != null) {
                        duration = parseLongQuiet(Ref.asStr(ref.get(shut, "duration")));
                        if (duration == 0) duration = Ref.asLong(ref.get(shut, "duration"));
                    }
                    return Notices.groupBan(selfUin, time, peerUin, member, admin, duration);
                }
            }
            long member = store.uinOf(memberUid);
            long admin = store.uinOf(adminUid);
            if (!memberUid.isEmpty()) {
                if (member == 0) member = qq.resolveUin(memberUid);
                if (admin == 0 && !adminUid.isEmpty()) admin = qq.resolveUin(adminUid);
                if (member == 0) member = senderUin;
                if (type == 1) return Notices.groupIncrease(selfUin, time, peerUin, member, admin);
                if (type == 3) {
                    return Notices.groupDecrease(selfUin, time, peerUin, member, admin,
                            admin != 0 && admin != member);
                }
            }
        }
        String redacted = xml == null ? "" : xml.replaceAll("uin=\"[^\"]*\"", "uin=\"*\"")
                .replaceAll("nm=\"[^\"]*\"", "nm=\"*\"");
        L.e("unparsed grayTip sub=" + sub + " busi=" + busi
                + " xml=" + clip(redacted, 160) + " json_len=" + (jsonStr == null ? 0 : jsonStr.length()), null);
        return null;
    }

    private void applyXmlPeople(JSONObject n, long selfUin) throws Exception {
        String memberUid = n.optString("member_uid");
        String adminUid = n.optString("admin_uid");
        long member = store.uinOf(memberUid);
        if (member == 0 && !memberUid.isEmpty()) member = qq.resolveUin(memberUid);
        long admin = store.uinOf(adminUid);
        if (admin == 0 && !adminUid.isEmpty()) admin = qq.resolveUin(adminUid);
        if (member == selfUin && admin != 0 && admin != selfUin) {
            long tmp = member;
            member = admin;
            admin = tmp;
        }
        if (member != 0) n.put("user_id", member);
        if (admin != 0) n.put("operator_id", admin);
        else if ("kick".equals(n.optString("sub_type")) && n.optLong("operator_id") == 0)
            n.put("operator_id", selfUin);
        n.remove("member_uid");
        n.remove("admin_uid");
    }

    private static String clip(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n);
    }

    private static long parseLongQuiet(String s) {
        try { return s == null || s.isEmpty() ? 0 : Long.parseLong(s.trim()); }
        catch (Exception e) { return 0; }
    }

    private String safeStr(Object o, String f) { try { return Ref.asStr(ref.get(o, f)); } catch (Throwable t) { return ""; } }

    private long safeLong(Object o, String f) {
        try { return Ref.asLong(ref.get(o, f)); } catch (Throwable t) { return 0; }
    }

    private void attachResource(String id, Object element, Object media, int chatType,
                                String peerUid, long msgId) {
        long elementId = safeLong(element, "elementId");
        long fileModelId = safeLong(media, "fileModelId");
        if (fileModelId == 0) fileModelId = safeLong(element, "fileModelId");
        store.attachResourceContext(id, chatType, peerUid, msgId, elementId, fileModelId);
    }

    private String firstNonEmpty(String a, String b) {
        return a != null && !a.isEmpty() ? a : (b == null ? "" : b);
    }

    private String normalizeUrl(String url) {
        if (url == null || url.isEmpty()) return "";
        if (url.startsWith("http://") || url.startsWith("https://")) return url;
        return url.startsWith("/") ? "https://gchat.qpic.cn" + url : url;
    }

    /** resid inside a com.tencent.multimsg ark JSON, or null if this is some other card. */
    private static String multimsgResId(String data) {
        JSONObject detail = multimsgDetail(data);
        if (detail == null) return null;
        String id = detail.optString("resid", "");
        return id.isEmpty() ? null : id;
    }

    private static String multimsgFileName(String data) {
        JSONObject j = multimsgRoot(data);
        if (j == null) return null;
        JSONObject extraObj = j.optJSONObject("extra");
        if (extraObj != null) {
            String fn = extraObj.optString("filename", "");
            if (!fn.isEmpty()) return fn;
        } else {
            String extra = j.optString("extra", "");
            if (!extra.isEmpty()) {
                try {
                    String fn = new JSONObject(extra).optString("filename", "");
                    if (!fn.isEmpty()) return fn;
                } catch (Exception ignore) {}
            }
        }
        JSONObject detail = multimsgDetail(data);
        if (detail == null) return null;
        String fn = detail.optString("uniseq", "");
        return fn.isEmpty() ? null : fn;
    }

    private static JSONObject multimsgDetail(String data) {
        JSONObject j = multimsgRoot(data);
        if (j == null) return null;
        JSONObject meta = j.optJSONObject("meta");
        return meta == null ? null : meta.optJSONObject("detail");
    }

    private static JSONObject multimsgRoot(String data) {
        if (data == null || !data.contains("com.tencent.multimsg")) return null;
        try {
            JSONObject j = new JSONObject(data);
            if (!"com.tencent.multimsg".equals(j.optString("app"))) return null;
            return j;
        } catch (Exception ignore) {
            return null;
        }
    }

    private static String nativeForwardId(long msgId) {
        return "native:" + msgId;
    }

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
