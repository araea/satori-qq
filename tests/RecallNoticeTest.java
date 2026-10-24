package com.satori.qq.qq;

import com.satori.qq.core.MsgStore;
import java.util.List;
import org.json.JSONObject;

/**
 * 快速撤回时，灰条记录自己的 msgId 不是被撤消息的 id。
 *
 * <p>这里只测纯转换：一条灰条记录（elementType 8、subElementType 1）走
 * {@link Convert#recordToEvent} 时，撤回通知必须保留原消息的号（或空着等
 * Inbound 去恢复），绝不能把灰条自己的 msgId 当成被撤消息。
 */
public final class RecallNoticeTest {
    public static final class Revoke {
        public String operatorUid = "";
        public String origMsgSenderUid = "";
        public long recallMsgId = 0;
    }

    public static final class GrayTip {
        public int subElementType = 1;
        public Revoke revokeElement = new Revoke();
    }

    public static final class Element {
        public int elementType = 8;
        public GrayTip grayTipElement = new GrayTip();
    }

    public static final class Record {
        public int chatType = 1;
        public long msgId = 7001L;
        public long msgSeq = 42L;
        public long peerUin = 9001L;
        public String peerUid = "u_peer";
        public long senderUin = 9001L;
        public String senderUid = "u_peer";
        public String sendNickName = "老张";
        public String sendMemberName = "";
        public long msgTime = 1_760_000_000L;
        public List<Object> elements = List.of(new Element());
    }

    public static void main(String[] args) throws Exception {
        Convert convert = new Convert(
                new QQClient(RecallNoticeTest.class.getClassLoader(), false),
                new MsgStore());

        // 1. 灰条没有带原消息号：不能拿灰条自己的 msgId 冒充，序号要留给恢复流程。
        JSONObject unknown = convert.recordToEvent(new Record(), 1L);
        check(unknown != null, "gray tip becomes a notice");
        eq("friend_recall", unknown.getString("notice_type"), "private recall");
        check(!unknown.has("qq_msg_id"), "unknown original keeps no qq_msg_id");
        eq("0", unknown.optString("message_id", "0"), "unknown original is not the gray tip id");
        eq(42L, unknown.getLong("message_seq"), "sequence kept for recovery");
        eq(9001L, unknown.getLong("peer_id"), "private peer kept");

        // 2. 灰条带着原消息号：直接用它，仍然是公开的 19 位号。
        Record known = new Record();
        long original = 7753807298269865192L;
        Revoke rev = new Revoke();
        rev.origMsgSenderUid = "u_peer";
        GrayTip gray = new GrayTip();
        gray.revokeElement = rev;
        Element element = new Element();
        element.grayTipElement = gray;
        rev.recallMsgId = original;
        known.elements = List.of(element);
        JSONObject found = convert.recordToEvent(known, 1L);
        check(found != null, "known recall parses");
        eq(String.valueOf(original), found.getString("message_id"), "public original id");
        eq(original, found.getLong("qq_msg_id"), "qq_msg_id is the original");
        check(found.getLong("message_id") != known.msgId, "never the gray tip's own id");
        System.out.println("RecallNoticeTest OK");
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
