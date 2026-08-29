package com.satori.qq.packet;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;

/**
 * Merge-forward (合并转发) via trpc SsoSendLongMsg.
 *
 * <p>Flow (mirrors LagrangeGo / NapCat): build one fake im_msg_body node per forwarded message,
 * wrap them in a gzipped LongMsgResult, upload with {@link #CMD}, receive a resId, then send a
 * kernel {@code MultiForwardMsgElement} (elementType=16) with that resId plus legacy XML.
 * Ark/json is only a fallback: Android QQNT 9.3.50 opens merge-forward from type 16, not ark 10.
 * Protobuf field numbers are from LagrangeDev/LagrangeGo / NapCat / im_msg_body.proto.</p>
 *
 * <p>Nodes carry im_msg_body Elems: text, at (MentionExtra + attr6Buf), face, reply (SrcMsg),
 * image (CustomFace / NotOnlineImage), file (TransElem 24 / GroupFile).</p>
 */
public final class LongMsg {
    public static final String CMD = "trpc.group.long_msg_interface.MsgService.SsoSendLongMsg";
    public static final String RECV_CMD = "trpc.group.long_msg_interface.MsgService.SsoRecvLongMsg";
    private static final Random RND = new Random();

    public static final class Seg {
        public String type = "text";
        public String text = "";
        public String qq = "";
        public String id = "";
        public String file = "";
        public String url = "";
        public String name = "";
        public long size;
        public int busid;
        public int width;
        public int height;
    }

    public static final class Node {
        public long senderUin;
        public String senderName = "";
        public String text = "";
        public long time;
        public final List<byte[]> elems = new ArrayList<>();
        public final List<Seg> segs = new ArrayList<>();
    }

    public static final class Pic {
        public byte[] md5;
        public int width;
        public int height;
        public int size;
        public String fileName = "";
        public String origUrl = "";
        public boolean group;
    }

    public static final class FileRef {
        public String fileId = "";
        public String name = "";
        public long size;
        public int busId = 102;
        public byte[] md5;
    }

    // ---- upload request (SendLongMsgReq) ----
    public static byte[] buildUploadReq(long groupUin, String selfUid, List<Node> nodes) {
        return buildUploadReq(groupUin, selfUid, nodes, null);
    }

    public static byte[] buildUploadReq(long groupUin, String selfUid, List<Node> nodes, String fileName) {
        Pb.Writer content = Pb.w();                 // LongMsgContent: repeated 1 = PushMsgBody
        for (Node n : nodes) content.message(1, buildFakeNode(groupUin, selfUid, n));
        byte[] body = content.toByteArray();
        Pb.Writer result = Pb.w();                  // LongMsgResult.Action (repeated 2)
        result.message(2, Pb.w().string(1, "MultiMsg").bytes(2, body));
        // Android MultiMsg viewer looks up the card uniseq/filename, not only "MultiMsg".
        if (fileName != null && !fileName.isEmpty() && !"MultiMsg".equals(fileName)) {
            result.message(2, Pb.w().string(1, fileName).bytes(2, body));
        }
        byte[] payload = gzip(result.toByteArray());

        Pb.Writer uid = Pb.w().string(2, groupUin != 0
                ? String.valueOf(groupUin) : (selfUid == null ? "" : selfUid));
        Pb.Writer info = Pb.w()
                .varint(1, groupUin != 0 ? 3 : 1)   // Type: group 3, friend 1
                .message(2, uid)                    // Uid
                .varint(3, groupUin)                // GroupUin
                .bytes(4, payload);                 // Payload
        Pb.Writer settings = Pb.w().varint(1, 4).varint(2, 1).varint(3, 7).varint(4, 0);
        return Pb.w().message(2, info).message(15, settings).toByteArray();
    }

    public static byte[] buildFakeNode(long groupUin, String selfUid, Node n) {
        String avatar = "https://q.qlogo.cn/headimg_dl?dst_uin=" + n.senderUin + "&spec=640&img_type=jpg";
        boolean group = groupUin != 0;

        Pb.Writer fwd = Pb.w()                       // ForwardHead
                .varint(1, 0).varint(2, 0)
                .varint(3, group ? 0 : 2)
                .string(5, avatar).string(6, avatar);
        Pb.Writer ch = Pb.w()                        // ContentHead
                .varint(1, group ? 82 : 9)           // Type
                .varint(4, randU32())                // MsgId
                .varint(5, randU32())                // Sequence
                .varint(6, n.time != 0 ? n.time : System.currentTimeMillis() / 1000)
                .varint(7, 1).varint(8, 0).varint(9, 0);
        if (!group) ch.varint(2, 4).varint(3, 4);    // SubType, DivSeq (friend)
        ch.message(15, fwd);

        Pb.Writer rh = Pb.w().varint(1, n.senderUin).string(2, ""); // ResponseHead: FromUin, FromUid
        if (group) {
            Pb.Writer grp = Pb.w().varint(1, groupUin)
                    .string(4, n.senderName == null ? "" : n.senderName)
                    .varint(5, 2);                   // ResponseGrp{GroupUin,MemberName,Unknown5}
            rh.message(8, grp);
        } else {
            rh.string(6, selfUid == null ? "" : selfUid); // ToUid
            rh.message(7, Pb.w().string(6, n.senderName == null ? "" : n.senderName)); // ResponseForward.FriendName
        }

        Pb.Writer rich = Pb.w();
        List<byte[]> elems = n.elems;
        if (elems == null || elems.isEmpty()) {
            rich.message(2, elemText(n.text == null ? "" : n.text));
        } else {
            for (byte[] e : elems) if (e != null && e.length > 0) rich.message(2, e);
        }
        byte[] body = Pb.w().message(1, rich).toByteArray();

        return Pb.w().message(1, rh).message(2, ch).message(3, body).toByteArray();
    }

    // ---- Elem builders (im_msg_body.Elem) ----

    public static byte[] elemText(String text) {
        return Pb.w().message(1, Pb.w().string(1, text == null ? "" : text)).toByteArray();
    }

    /** at-all type=1, at-one type=2. uid may be empty; attr6Buf keeps old clients working. */
    public static byte[] elemAt(String display, boolean all, long uin, String uid) {
        String str = display == null || display.isEmpty()
                ? (all ? "@全体成员" : ("@" + uin))
                : display;
        byte[] reserve = Pb.w()
                .varint(3, all ? 1 : 2)
                .varint(4, 0)
                .varint(5, 0)
                .string(9, uid == null ? "" : uid)
                .toByteArray();
        return Pb.w().message(1, Pb.w()
                .string(1, str)
                .bytes(3, attr6Buf(all ? 0 : uin))
                .bytes(12, reserve)).toByteArray();
    }

    public static byte[] elemFace(int faceId) {
        return Pb.w().message(2, Pb.w().varint(1, faceId)).toByteArray();
    }

    public static byte[] elemReply(long seq, long senderUin, long time, long msgId, String senderUid,
                                   String summary) {
        byte[] inner = elemText(summary == null || summary.isEmpty() ? "[回复]" : summary);
        Pb.Writer src = Pb.w()
                .varint(1, seq)
                .varint(2, senderUin)
                .varint(3, time);
        src.message(5, inner);
        Pb.Writer reserve = Pb.w();
        if (msgId != 0) reserve.varint(3, msgId);
        if (senderUid != null && !senderUid.isEmpty()) reserve.string(6, senderUid);
        byte[] res = reserve.toByteArray();
        if (res.length > 0) src.message(8, res);
        return Pb.w().message(45, src).toByteArray();
    }

    public static byte[] elemImage(Pic pic) {
        if (pic == null || pic.md5 == null || pic.md5.length != 16) return elemText("[图片]");
        String hex = hex(pic.md5).toUpperCase();
        String name = pic.fileName == null || pic.fileName.isEmpty() ? (hex + ".jpg") : pic.fileName;
        String orig = pic.origUrl;
        if (orig == null || orig.isEmpty()) {
            orig = pic.group
                    ? ("/gchatpic_new/0/0-0-" + hex + "/0?term=2")
                    : ("/offpic_new/0/0-0-" + hex + "/0?term=2");
        } else {
            orig = stripHost(orig);
        }
        int w = pic.width > 0 ? pic.width : 100;
        int h = pic.height > 0 ? pic.height : 100;
        int size = pic.size > 0 ? pic.size : 1;
        if (pic.group) {
            return Pb.w().message(8, Pb.w()
                    .string(2, name)
                    .varint(7, 0)
                    .varint(10, 1001)
                    .varint(12, 1)
                    .bytes(13, pic.md5)
                    .string(16, orig)
                    .varint(17, 4)
                    .varint(20, 1000)
                    .varint(22, w)
                    .varint(23, h)
                    .varint(25, size)
                    .varint(26, 1)).toByteArray();
        }
        return Pb.w().message(4, Pb.w()
                .string(1, "/" + name)
                .varint(2, size)
                .bytes(7, pic.md5)
                .varint(8, h)
                .varint(9, w)
                .string(10, orig)
                .string(15, orig)
                .varint(13, 1)).toByteArray();
    }

    public static byte[] elemFile(FileRef file) {
        if (file == null || file.fileId == null || file.fileId.isEmpty()) return elemText("[文件]");
        String name = file.name == null || file.name.isEmpty() ? file.fileId : file.name;
        int bus = file.busId > 0 ? file.busId : 102;
        Pb.Writer info = Pb.w()
                .varint(1, bus)
                .string(2, file.fileId)
                .varint(3, file.size)
                .string(4, name);
        if (file.md5 != null && file.md5.length > 0) info.bytes(8, file.md5);
        byte[] extra = Pb.w()
                .varint(1, 6)
                .string(2, name)
                .message(7, Pb.w().message(2, info))
                .toByteArray();
        byte[] tlv = new byte[3 + extra.length];
        tlv[0] = 0x01;
        tlv[1] = (byte) ((extra.length >>> 8) & 0xFF);
        tlv[2] = (byte) (extra.length & 0xFF);
        System.arraycopy(extra, 0, tlv, 3, extra.length);
        return Pb.w().message(5, Pb.w().varint(1, 24).bytes(2, tlv)).toByteArray();
    }

    // ---- reply (SendLongMsgResp{2: Result{3: ResId}}) ----
    public static String parseResId(byte[] replyBody) {
        if (replyBody == null || replyBody.length == 0) return null;
        Pb.Reader result = new Pb.Reader(replyBody).msg(2);
        return result == null ? null : result.str(3);
    }

    // ---- download (get_forward_msg): SsoRecvLongMsg ----
    public static byte[] buildDownloadReq(String selfUid, String resId) {
        Pb.Writer uid = Pb.w().string(2, selfUid == null ? "" : selfUid);
        Pb.Writer info = Pb.w().message(1, uid).string(2, resId).varint(3, 1); // Acquire=true
        Pb.Writer settings = Pb.w().varint(1, 2).varint(2, 0).varint(3, 0).varint(4, 0);
        return Pb.w().message(1, info).message(15, settings).toByteArray();
    }

    /** Parse a RecvLongMsgResp reply into the forwarded nodes. */
    public static List<Node> parseDownload(byte[] replyBody) {
        List<Node> out = new ArrayList<>();
        if (replyBody == null || replyBody.length == 0) return out;
        Pb.Reader result = new Pb.Reader(replyBody).msg(1);   // RecvLongMsgResp.Result=1
        if (result == null) return out;
        byte[] payload = result.bytes(4);                     // RecvLongMsgResult.Payload=4
        if (payload == null) return out;
        byte[] raw = gunzip(payload);
        if (raw.length == 0) return out;
        List<Object> actions = new Pb.Reader(raw).all(2);     // LongMsgResult.Action=2 (repeated)
        if (actions == null) return out;
        List<Node> named = new ArrayList<>();
        for (Object ao : actions) {
            Pb.Reader action = new Pb.Reader((byte[]) ao);
            String cmd = action.str(1);
            Pb.Reader data = action.msg(2);                   // LongMsgContent
            if (data == null) continue;
            List<Node> parsed = parseActionNodes(data);
            if (parsed.isEmpty()) continue;
            if ("MultiMsg".equals(cmd)) {
                return parsed;
            }
            if (named.isEmpty()) named = parsed;
        }
        return named.isEmpty() ? out : named;
    }

    private static List<Node> parseActionNodes(Pb.Reader data) {
        List<Node> out = new ArrayList<>();
        List<Object> bodies = data.all(1);                // MsgBody=1 (repeated PushMsgBody)
        if (bodies == null) return out;
        for (Object bo : bodies) out.add(parseNode(new Pb.Reader((byte[]) bo)));
        return out;
    }

    public static Node parseNode(Pb.Reader body) {
        Node n = new Node();
        Pb.Reader rh = body.msg(1);   // ResponseHead
        if (rh != null) {
            n.senderUin = rh.num(1);  // FromUin
            Pb.Reader grp = rh.msg(8); // Grp
            if (grp != null) {
                n.senderName = grp.str(4);           // MemberName
            } else {
                Pb.Reader fwd = rh.msg(7);           // ResponseForward
                if (fwd != null) n.senderName = fwd.str(6);
            }
        }
        Pb.Reader ch = body.msg(2);   // ContentHead
        if (ch != null) n.time = ch.num(6); // TimeStamp
        if (n.senderName == null) n.senderName = "";

        Pb.Reader mb = body.msg(3);
        Pb.Reader rt = mb == null ? null : mb.msg(1);
        List<Object> elems = rt == null ? null : rt.all(2);
        n.segs.addAll(parseElems(elems));
        StringBuilder sb = new StringBuilder();
        for (Seg s : n.segs) {
            if ("text".equals(s.type) && s.text != null) sb.append(s.text);
            else if ("at".equals(s.type)) sb.append(s.text != null && !s.text.isEmpty() ? s.text : ("@" + s.qq));
            else if ("image".equals(s.type)) sb.append("[图片]");
            else if ("file".equals(s.type)) sb.append("[文件]");
            else if ("reply".equals(s.type)) sb.append("[回复]");
            else if ("face".equals(s.type)) sb.append("[表情]");
        }
        n.text = sb.toString();
        return n;
    }

    public static List<Seg> parseElems(List<Object> elems) {
        List<Seg> out = new ArrayList<>();
        if (elems == null) return out;
        for (Object eo : elems) {
            Seg s = parseElem(new Pb.Reader((byte[]) eo));
            if (s != null) out.add(s);
        }
        if (out.isEmpty()) {
            Seg t = new Seg();
            t.type = "text";
            t.text = "";
            out.add(t);
        }
        return out;
    }

    static Seg parseElem(Pb.Reader el) {
        Pb.Reader src = el.msg(45);
        if (src != null) {
            Seg s = new Seg();
            s.type = "reply";
            List<Object> seqs = src.all(1);
            long seq = 0;
            if (seqs != null && !seqs.isEmpty() && seqs.get(0) instanceof Long)
                seq = (Long) seqs.get(0);
            s.id = String.valueOf(seq);
            s.qq = String.valueOf(src.num(2));
            Pb.Reader res = src.msg(8);
            if (res != null && res.num(3) != 0) s.id = String.valueOf(res.num(3));
            return s;
        }
        Pb.Reader trans = el.msg(5);
        if (trans != null && trans.num(1) == 24) {
            Seg file = parseTransFile(trans.bytes(2));
            if (file != null) return file;
        }
        Pb.Reader gf = el.msg(13);
        if (gf != null) {
            Seg s = new Seg();
            s.type = "file";
            byte[] name = gf.bytes(1);
            byte[] id = gf.bytes(3);
            try {
                s.name = name == null ? "" : new String(name, "UTF-8");
                s.file = id == null ? "" : new String(id, "UTF-8");
            } catch (Exception e) {
                s.name = "";
                s.file = "";
            }
            s.size = gf.num(2);
            s.busid = 102;
            return s;
        }
        Pb.Reader cf = el.msg(8);
        if (cf != null) return picSeg(cf.bytes(13), cf.str(16), cf.str(2),
                (int) cf.num(22), (int) cf.num(23), (int) cf.num(25), true);
        Pb.Reader noi = el.msg(4);
        if (noi != null) {
            String orig = noi.str(15);
            if (orig == null || orig.isEmpty()) orig = noi.str(10);
            return picSeg(noi.bytes(7), orig, noi.str(1),
                    (int) noi.num(9), (int) noi.num(8), (int) noi.num(2), false);
        }
        Pb.Reader face = el.msg(2);
        if (face != null && face.num(1) != 0) {
            Seg s = new Seg();
            s.type = "face";
            s.id = String.valueOf(face.num(1));
            return s;
        }
        Pb.Reader text = el.msg(1);
        if (text != null) {
            String str = text.str(1);
            byte[] attr6 = text.bytes(3);
            byte[] reserve = text.bytes(12);
            if ((attr6 != null && attr6.length >= 11) || reserve != null) {
                Seg s = new Seg();
                s.type = "at";
                s.text = str == null ? "" : str;
                long uin = 0;
                if (attr6 != null && attr6.length >= 11) {
                    uin = ((attr6[7] & 0xFFL) << 24) | ((attr6[8] & 0xFFL) << 16)
                            | ((attr6[9] & 0xFFL) << 8) | (attr6[10] & 0xFFL);
                }
                if (reserve != null) {
                    Pb.Reader m = new Pb.Reader(reserve);
                    if (m.num(3) == 1) {
                        s.qq = "all";
                        return s;
                    }
                    if (m.num(4) != 0) uin = m.num(4);
                }
                s.qq = uin == 0 ? "all" : String.valueOf(uin);
                return s;
            }
            Seg s = new Seg();
            s.type = "text";
            s.text = str == null ? "" : str;
            if (looksLikeAt(s.text)) {
                s.type = "at";
                if ("@全体成员".equals(s.text) || "@all".equalsIgnoreCase(s.text)) s.qq = "all";
                else s.qq = s.text.startsWith("@") ? s.text.substring(1).trim() : s.text;
            }
            return s;
        }
        return null;
    }

    private static Seg parseTransFile(byte[] tlv) {
        if (tlv == null || tlv.length < 4 || tlv[0] != 0x01) return null;
        int len = ((tlv[1] & 0xFF) << 8) | (tlv[2] & 0xFF);
        if (3 + len > tlv.length) len = tlv.length - 3;
        byte[] extra = new byte[len];
        System.arraycopy(tlv, 3, extra, 0, len);
        Pb.Reader gfe = new Pb.Reader(extra);
        Pb.Reader inner = gfe.msg(7);
        Pb.Reader info = inner == null ? null : inner.msg(2);
        Seg s = new Seg();
        s.type = "file";
        s.name = gfe.str(2);
        if (s.name == null) s.name = "";
        if (info != null) {
            s.busid = (int) info.num(1);
            s.file = info.str(2);
            s.size = info.num(3);
            String n = info.str(4);
            if (n != null && !n.isEmpty()) s.name = n;
        }
        if (s.file == null) s.file = "";
        return s;
    }

    private static Seg picSeg(byte[] md5, String origUrl, String name, int w, int h, int size, boolean group) {
        Seg s = new Seg();
        s.type = "image";
        String hex = hex(md5);
        s.file = hex.isEmpty() ? (name == null ? "" : name) : (hex + ".image");
        s.name = name == null ? "" : name;
        s.width = w;
        s.height = h;
        s.size = size;
        if (origUrl != null && !origUrl.isEmpty()) {
            if (origUrl.startsWith("http")) s.url = origUrl;
            else if (origUrl.contains("fileid=")) s.url = "https://multimedia.nt.qq.com.cn" + origUrl;
            else s.url = (group ? "https://gchat.qpic.cn" : "https://c2cpicdw.qpic.cn") + origUrl;
        }
        return s;
    }

    /** Outgoing merge-forward card: kernel type-16 XML + ark JSON fallback. */
    public static final class Card {
        public String fileName = "";
        public String xml = "";
        public String json = "";
    }

    public static Card buildCard(String resId, List<Node> nodes, boolean group) throws org.json.JSONException {
        return buildCard(resId, nodes, group, UUID.randomUUID().toString());
    }

    public static Card buildCard(String resId, List<Node> nodes, boolean group, String fileName)
            throws org.json.JSONException {
        Card c = new Card();
        c.fileName = fileName == null || fileName.isEmpty() ? UUID.randomUUID().toString() : fileName;
        String source = sourceTitle(nodes, group);
        JSONArray news = new JSONArray();
        int shown = 0;
        for (Node n : nodes) {
            if (shown >= 4) break;
            String name = n.senderName == null || n.senderName.isEmpty() ? String.valueOf(n.senderUin) : n.senderName;
            String preview = n.text == null ? "" : n.text;
            if (preview.length() > 36) preview = preview.substring(0, 36);
            news.put(new JSONObject().put("text", name + ": " + preview));
            shown++;
        }
        c.xml = buildXmlContent(resId, c.fileName, nodes, group);
        JSONObject extra = new JSONObject().put("filename", c.fileName).put("tsum", nodes.size());
        c.json = new JSONObject()
                .put("app", "com.tencent.multimsg")
                .put("config", new JSONObject()
                        .put("autosize", 1).put("forward", 1).put("round", 1)
                        .put("type", "normal").put("width", 300))
                .put("desc", "[聊天记录]")
                .put("extra", extra)
                .put("meta", new JSONObject().put("detail", new JSONObject()
                        .put("news", news)
                        .put("resid", resId)
                        .put("source", source)
                        .put("summary", "查看" + nodes.size() + "条转发消息")
                        .put("uniseq", c.fileName)))
                .put("prompt", "[聊天记录]")
                .put("ver", "0.0.0.5")
                .put("view", "contact")
                .toString();
        return c;
    }

    /** @deprecated use {@link #buildCard}; kept for existing tests. */
    public static String buildCardJson(String resId, List<Node> nodes) throws org.json.JSONException {
        return buildCard(resId, nodes, false).json;
    }

    public static String buildXmlContent(String resId, List<Node> nodes, boolean group) {
        return buildXmlContent(resId, UUID.randomUUID().toString(), nodes, group);
    }

    public static String buildXmlContent(String resId, String fileName, List<Node> nodes, boolean group) {
        String source = sourceTitle(nodes, group);
        StringBuilder xmlTitles = new StringBuilder();
        int shown = 0;
        for (Node n : nodes) {
            if (shown >= 4) break;
            String name = n.senderName == null || n.senderName.isEmpty() ? String.valueOf(n.senderUin) : n.senderName;
            String preview = n.text == null ? "" : n.text;
            if (preview.length() > 36) preview = preview.substring(0, 36);
            xmlTitles.append("<title size=\"26\" color=\"#777777\">")
                    .append(xmlEsc(name + ": " + preview)).append("</title>");
            shown++;
        }
        return "<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>"
                + "<msg serviceID=\"35\" templateID=\"1\" action=\"viewMultiMsg\" brief=\"[聊天记录]\""
                + " m_resid=\"" + xmlEsc(resId) + "\" m_fileName=\"" + xmlEsc(fileName) + "\""
                + " tSum=\"" + nodes.size() + "\" flag=\"3\" adverSign=\"0\" multiMsgFlag=\"0\">"
                + "<item layout=\"1\">"
                + "<title color=\"#000000\" size=\"34\">" + xmlEsc(source) + "</title>"
                + xmlTitles
                + "<hr></hr>"
                + "<summary size=\"26\" color=\"#777777\">查看" + nodes.size() + "条转发消息</summary>"
                + "</item><source name=\"聊天记录\"></source></msg>";
    }

    private static String sourceTitle(List<Node> nodes, boolean group) {
        if (nodes == null || nodes.isEmpty()) return "聊天记录";
        if (group) return "群聊的聊天记录";
        Set<String> names = new LinkedHashSet<>();
        for (Node n : nodes) {
            String name = n.senderName == null || n.senderName.isEmpty() ? String.valueOf(n.senderUin) : n.senderName;
            names.add(name);
            if (names.size() >= 4) break;
        }
        return String.join("和", names) + "的聊天记录";
    }

    private static String xmlEsc(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': b.append("&amp;"); break;
                case '<': b.append("&lt;"); break;
                case '>': b.append("&gt;"); break;
                case '"': b.append("&quot;"); break;
                case '\'': b.append("&apos;"); break;
                default: b.append(c);
            }
        }
        return b.toString();
    }

    public static byte[] md5Of(java.io.File file) {
        if (file == null || !file.isFile()) return null;
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            }
            return md.digest();
        } catch (Exception e) {
            return null;
        }
    }

    public static byte[] md5Hex(String hex) {
        if (hex == null) return null;
        String h = hex.trim();
        if (h.length() >= 32) {
            int start = 0;
            for (int i = 0; i + 32 <= h.length(); i++) {
                if (isHex32(h, i)) { start = i; break; }
            }
            h = h.substring(start, start + 32);
        }
        if (h.length() != 32) return null;
        byte[] out = new byte[16];
        try {
            for (int i = 0; i < 16; i++) out[i] = (byte) Integer.parseInt(h.substring(i * 2, i * 2 + 2), 16);
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isHex32(String s, int off) {
        for (int i = 0; i < 32; i++) {
            char c = s.charAt(off + i);
            boolean ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!ok) return false;
        }
        return true;
    }

    public static String hex(byte[] b) {
        if (b == null || b.length == 0) return "";
        char[] hex = "0123456789abcdef".toCharArray();
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte v : b) {
            sb.append(hex[(v >> 4) & 0xF]);
            sb.append(hex[v & 0xF]);
        }
        return sb.toString();
    }

    static boolean looksLikeAt(String text) {
        if (text == null) return false;
        if ("@全体成员".equals(text) || "@all".equalsIgnoreCase(text)) return true;
        if (text.length() < 2 || text.charAt(0) != '@') return false;
        for (int i = 1; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return text.length() > 1;
    }

    static byte[] attr6Buf(long uin) {
        byte[] b = new byte[11];
        b[1] = 1;
        b[5] = 0x0A;
        int u = (int) (uin & 0xFFFFFFFFL);
        b[7] = (byte) ((u >>> 24) & 0xFF);
        b[8] = (byte) ((u >>> 16) & 0xFF);
        b[9] = (byte) ((u >>> 8) & 0xFF);
        b[10] = (byte) (u & 0xFF);
        return b;
    }

    static String stripHost(String url) {
        if (url == null) return "";
        int scheme = url.indexOf("://");
        if (scheme < 0) return url.startsWith("/") ? url : ("/" + url);
        int slash = url.indexOf('/', scheme + 3);
        return slash < 0 ? url : url.substring(slash);
    }

    private static long randU32() { return RND.nextInt() & 0xFFFFFFFFL; }

    private static byte[] gzip(byte[] data) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (GZIPOutputStream gz = new GZIPOutputStream(bos)) { gz.write(data); }
            return bos.toByteArray();
        } catch (Exception e) {
            return new byte[0];
        }
    }

    private static byte[] gunzip(byte[] data) {
        try (java.util.zip.GZIPInputStream gz =
                     new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(data))) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int r;
            while ((r = gz.read(buf)) != -1) bos.write(buf, 0, r);
            return bos.toByteArray();
        } catch (Exception e) {
            return new byte[0];
        }
    }
}
