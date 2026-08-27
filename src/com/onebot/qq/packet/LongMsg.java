package com.onebot.qq.packet;

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
 * wrap them in a gzipped LongMsgResult, upload with {@link #CMD}, receive a resId, then render a
 * {@code com.tencent.multimsg} LightApp card that references the resId and send it as an ordinary
 * ark/json message. Protobuf field numbers are from LagrangeDev/LagrangeGo message protos.</p>
 *
 * <p>v1 supports text content per node (the common case). Richer node content (images, at, nested
 * cards) would each need their own im_msg_body Elem encoding.</p>
 */
public final class LongMsg {
    public static final String CMD = "trpc.group.long_msg_interface.MsgService.SsoSendLongMsg";
    public static final String RECV_CMD = "trpc.group.long_msg_interface.MsgService.SsoRecvLongMsg";
    private static final Random RND = new Random();

    public static final class Node {
        public long senderUin;
        public String senderName = "";
        public String text = "";
        public long time;
    }

    // ---- upload request (SendLongMsgReq) ----
    public static byte[] buildUploadReq(long groupUin, String selfUid, List<Node> nodes) {
        Pb.Writer content = Pb.w();                 // LongMsgContent: repeated 1 = PushMsgBody
        for (Node n : nodes) content.message(1, buildFakeNode(groupUin, selfUid, n));
        byte[] action = Pb.w().string(1, "MultiMsg").message(2, content).toByteArray();
        byte[] result = Pb.w().message(2, action).toByteArray();   // LongMsgResult.Action (repeated 2)
        byte[] payload = gzip(result);

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

    private static byte[] buildFakeNode(long groupUin, String selfUid, Node n) {
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
                .varint(6, System.currentTimeMillis() / 1000) // TimeStamp
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

        // Body: MessageBody{1: RichText{2: repeated Elem{1: Text{1: Str}}}}
        byte[] elem = Pb.w().message(1, Pb.w().string(1, n.text == null ? "" : n.text)).toByteArray();
        byte[] richText = Pb.w().message(2, elem).toByteArray();
        byte[] body = Pb.w().message(1, richText).toByteArray();

        return Pb.w().message(1, rh).message(2, ch).message(3, body).toByteArray();
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

    /** Parse a RecvLongMsgResp reply into the forwarded nodes (text content). */
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
        for (Object ao : actions) {
            Pb.Reader action = new Pb.Reader((byte[]) ao);
            if (!"MultiMsg".equals(action.str(1))) continue;
            Pb.Reader data = action.msg(2);                   // LongMsgContent
            if (data == null) continue;
            List<Object> bodies = data.all(1);                // MsgBody=1 (repeated PushMsgBody)
            if (bodies == null) continue;
            for (Object bo : bodies) out.add(parseNode(new Pb.Reader((byte[]) bo)));
        }
        return out;
    }

    private static Node parseNode(Pb.Reader body) {
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

        StringBuilder sb = new StringBuilder(); // Body.RichText.Elems[].Text.Str
        Pb.Reader mb = body.msg(3);
        Pb.Reader rt = mb == null ? null : mb.msg(1);
        List<Object> elems = rt == null ? null : rt.all(2);
        if (elems != null) for (Object eo : elems) {
            Pb.Reader text = new Pb.Reader((byte[]) eo).msg(1);
            if (text != null) { String s = text.str(1); if (s != null) sb.append(s); }
        }
        n.text = sb.toString();
        return n;
    }

    // ---- outgoing multimsg LightApp card ----
    public static String buildCardJson(String resId, List<Node> nodes) throws org.json.JSONException {
        String uniseq = UUID.randomUUID().toString();
        JSONArray news = new JSONArray();
        Set<String> names = new LinkedHashSet<>();
        for (Node n : nodes) {
            String name = n.senderName == null || n.senderName.isEmpty() ? String.valueOf(n.senderUin) : n.senderName;
            news.put(new JSONObject().put("text", name + ": " + (n.text == null ? "" : n.text)));
            names.add(name);
        }
        String source = nodes.isEmpty() ? "聊天记录" : (String.join("和", names) + "的聊天记录");

        JSONObject detail = new JSONObject()
                .put("news", news)
                .put("resid", resId)
                .put("source", source)
                .put("summary", "查看" + nodes.size() + "条转发消息")
                .put("uniseq", uniseq);
        JSONObject config = new JSONObject()
                .put("autosize", 1).put("forward", 1).put("round", 1)
                .put("type", "normal").put("width", 300);
        String extra = new JSONObject().put("filename", uniseq).put("tsum", nodes.size()).toString();

        return new JSONObject()
                .put("app", "com.tencent.multimsg")
                .put("config", config)
                .put("desc", "[聊天记录]")
                .put("extra", extra)
                .put("meta", new JSONObject().put("detail", detail))
                .put("prompt", "[聊天记录]")
                .put("ver", "0.0.0.5")
                .put("view", "contact")
                .toString();
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
