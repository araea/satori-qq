package com.satori.qq.qq;

import com.satori.qq.core.MsgStore;
import java.util.List;
import org.json.JSONObject;

/** QQ 9.3.80 的 1022 JSON 灰条必须直接转换成入群通知。 */
public final class MemberJoinNoticeTest {
    public static final class JsonGrayTip {
        public long busiId = 1022;
        public String jsonStr = "{\"align\":\"center\",\"items\":["
                + "{\"type\":\"qq\",\"uid\":\"u_new\",\"txt\":\"新成员\"},"
                + "{\"type\":\"nor\",\"txt\":\" 加入了群聊\"}]}";
    }

    public static final class GrayTip {
        public int subElementType = 17;
        public Object revokeElement;
        public JsonGrayTip jsonGrayTipElement = new JsonGrayTip();
    }

    public static final class Element {
        public int elementType = 8;
        public GrayTip grayTipElement = new GrayTip();
    }

    public static final class Record {
        public int chatType = 2;
        public long msgId = 7002L;
        public long msgSeq = 43L;
        public long peerUin = 280183116L;
        public String peerUid = "280183116";
        public long senderUin = 0;
        public String senderUid = "";
        public String sendNickName = "";
        public String sendMemberName = "";
        public long msgTime = 1_760_000_000L;
        public List<Object> elements = List.of(new Element());
    }

    public static void main(String[] args) throws Exception {
        MsgStore store = new MsgStore();
        store.learnUid(123456L, "u_new");
        Convert convert = new Convert(new QQClient(MemberJoinNoticeTest.class.getClassLoader(), false), store);
        JSONObject event = convert.recordToEvent(new Record(), 1L);
        check(event != null, "1022 becomes a notice instead of unparsed gray tip");
        eq("group_increase", event.getString("notice_type"), "notice type");
        eq("approve", event.getString("sub_type"), "join subtype");
        eq(280183116L, event.getLong("group_id"), "group id");
        eq(123456L, event.getLong("user_id"), "uid resolved through local cache");
        eq("新成员", event.getString("member_name"), "nickname comes from gray tip");
        eq(7002L, event.getLong("qq_msg_id"), "gray tip record id kept for diagnostics");
        check(!event.has("member_uid"), "internal uid is removed after uin resolution");
        System.out.println("MemberJoinNoticeTest OK");
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }

    private static void eq(Object expected, Object actual, String what) {
        if (!String.valueOf(expected).equals(String.valueOf(actual))) {
            throw new AssertionError(what + ": expected " + expected + " got " + actual);
        }
    }
}
