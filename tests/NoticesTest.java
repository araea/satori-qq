import com.onebot.qq.core.MsgStore;
import com.onebot.qq.core.Notices;
import org.json.JSONObject;

/** Offline checks for OneBot notice/request JSON builders. */
public final class NoticesTest {
    public static void main(String[] args) throws Exception {
        testRecall();
        testPoke();
        testGroupMember();
        testRequests();
        testMsgStoreLookup();
        System.out.println("NoticesTest OK");
    }

    private static void testRecall() throws Exception {
        JSONObject g = Notices.recall(1, 100, true, 280183116L, 2, 3, 42);
        eq("notice", g.getString("post_type"), "recall post");
        eq("group_recall", g.getString("notice_type"), "recall type");
        eq(280183116L, g.getLong("group_id"), "recall group");
        eq(2, g.getLong("user_id"), "recall user");
        eq(3, g.getLong("operator_id"), "recall operator");
        eq(42, g.getInt("message_id"), "recall mid");

        JSONObject f = Notices.recall(1, 100, false, 0, 9, 9, 7);
        eq("friend_recall", f.getString("notice_type"), "friend recall");
        check(!f.has("group_id"), "friend has no group_id");
    }

    private static void testPoke() throws Exception {
        String json = "{\"items\":[{\"uid\":\"u_a\"},{\"txt\":\"poke\"},{\"uid\":\"u_b\"}]}";
        JSONObject p = Notices.pokeFromJson(json, 1, 50, true, 280183116L);
        check(p != null, "poke parsed");
        eq("notify", p.getString("notice_type"), "poke notice");
        eq("poke", p.getString("sub_type"), "poke sub");
        eq("u_a", p.getString("sender_uid"), "poke sender uid");
        eq("u_b", p.getString("target_uid"), "poke target uid");
        eq(280183116L, p.getLong("group_id"), "poke group");

        String xml = "<gtip align=\"center\"> <qq uin=\"u_a\" col=\"1\" nm=\"\" />"
                + " <img src=\"http://tianquan.gtimg.cn/nudgeaction/item/0/action.png\" />"
                + " <qq uin=\"u_b\" col=\"1\" nm=\"\" /></gtip>";
        JSONObject x = Notices.pokeFromXml(xml, 1, 50, true, 280183116L);
        check(x != null, "xml poke parsed");
        eq("u_a", x.getString("sender_uid"), "xml poke sender");
        eq("u_b", x.getString("target_uid"), "xml poke target");

        String banXml = "<gtip align=\"center\"><qq uin=\"u_op\" col=\"1\" nm=\"\"/>"
                + "<nor txt=\"将\"/><qq uin=\"u_mb\" col=\"1\" nm=\"\"/>"
                + "<nor txt=\"禁言10秒\"/></gtip>";
        JSONObject b = Notices.banFromXml(banXml, 1, 50, 280183116L);
        check(b != null, "xml ban parsed");
        eq("ban", b.getString("sub_type"), "xml ban sub");
        eq(10, b.getLong("duration"), "xml ban duration");
        eq("u_op", b.getString("admin_uid"), "xml ban admin");
        eq("u_mb", b.getString("member_uid"), "xml ban member");
        JSONObject lift = Notices.banFromXml(
                "<gtip><qq uin=\"u_op\"/><nor txt=\"解除禁言\"/><qq uin=\"u_mb\"/></gtip>",
                1, 50, 280183116L);
        check(lift != null, "xml lift parsed");
        eq("lift_ban", lift.getString("sub_type"), "xml lift sub");
    }

    private static void testGroupMember() throws Exception {
        JSONObject inc = Notices.groupIncrease(1, 1, 280183116L, 2, 3);
        eq("group_increase", inc.getString("notice_type"), "increase");
        JSONObject dec = Notices.groupDecrease(1, 1, 280183116L, 2, 3, true);
        eq("kick", dec.getString("sub_type"), "kick");
        JSONObject kickXml = Notices.memberChangeFromXml(
                "<gtip><qq uin=\"u_op\"/><nor txt=\"将\"/><qq uin=\"u_mb\"/><nor txt=\"移出群聊\"/></gtip>",
                1, 1, 280183116L);
        check(kickXml != null, "xml kick parsed");
        eq("group_decrease", kickXml.getString("notice_type"), "xml kick type");
        eq("kick", kickXml.getString("sub_type"), "xml kick sub");
        JSONObject joinXml = Notices.memberChangeFromXml(
                "<gtip><qq uin=\"u_mb\"/><nor txt=\"加入了群聊\"/></gtip>",
                1, 1, 280183116L);
        check(joinXml != null, "xml join parsed");
        eq("group_increase", joinXml.getString("notice_type"), "xml join type");
        JSONObject ban = Notices.groupBan(1, 1, 280183116L, 2, 3, 600);
        eq("ban", ban.getString("sub_type"), "ban");
        eq(600, ban.getLong("duration"), "duration");
    }

    private static void testRequests() throws Exception {
        JSONObject f = Notices.friendRequest(1, 10, 20002, "hi", "123");
        eq("request", f.getString("post_type"), "friend post");
        eq("friend", f.getString("request_type"), "friend type");
        eq("123", f.getString("flag"), "friend flag");

        JSONObject g = Notices.groupRequest(1, 10, 280183116L, 20002, "add", "please", "99");
        eq("group", g.getString("request_type"), "group type");
        eq("add", g.getString("sub_type"), "group sub");
        eq(280183116L, g.getLong("group_id"), "group id");
    }

    private static void testMsgStoreLookup() {
        MsgStore store = new MsgStore();
        MsgStore.Rec r = new MsgStore.Rec();
        r.msgId = 99;
        r.senderUin = 5;
        int id = store.put(r);
        check(store.getByMsgId(99) != null, "getByMsgId");
        eq(id, store.idOfMsgId(99), "idOfMsgId");
        eq(0, store.idOfMsgId(1), "missing id");
    }

    private static void eq(long a, long b, String label) {
        if (a != b) throw new AssertionError(label + ": " + a + " != " + b);
    }

    private static void eq(String a, String b, String label) {
        if (a == null ? b != null : !a.equals(b)) throw new AssertionError(label + ": " + a + " != " + b);
    }

    private static void check(boolean v, String label) {
        if (!v) throw new AssertionError(label);
    }
}
