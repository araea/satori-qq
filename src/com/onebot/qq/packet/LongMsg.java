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
    private static final Random RND = new Random();

    public static final class Node {
        public long senderUin;
        public String senderName = "";
        public String text = "";
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
}
