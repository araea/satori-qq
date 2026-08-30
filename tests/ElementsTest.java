import com.satori.qq.satori.Codec;
import com.satori.qq.satori.Elements;
import org.json.JSONArray;
import org.json.JSONObject;

public final class ElementsTest {
    public static void main(String[] args) throws Exception {
        parseRoundtrip();
        malformedAndEntities();
        atAndQuote();
        standardFallbacks();
        forwardNodes();
        channelIds();
        eventMapping();
        System.out.println("ElementsTest OK");
    }

    private static void malformedAndEntities() {
        String mismatched = Elements.stringify(Elements.parse("<tag><foo> bar</tag>"));
        eq("<tag>&lt;foo&gt; bar</tag>", mismatched, "unmatched nested tag becomes text");
        eq("&lt;foo&gt;bar", Elements.stringify(Elements.parse("<foo>bar")),
                "unclosed root becomes text");
        eq("<tag>&lt;foo&gt; bar</tag>", Elements.stringify(Elements.parse(
                "<tag>\n  <foo> bar\n  <!-- ignored -->\n</tag>")),
                "formatting whitespace and comments are ignored");
        eq("🙂", Elements.joinText(Elements.parse("&#x1F642;")), "unicode code point entity");
        eq("<Bad>", Elements.joinText(Elements.parse("<Bad>")), "invalid uppercase tag is text");
    }

    private static void parseRoundtrip() {
        String src = "hello <at id=\"123\" name=\"bob\"/> world";
        String out = Elements.stringify(Elements.parse(src));
        check(out.contains("<at"), "at tag present");
        check(out.contains("hello"), "text kept");
        check(out.contains("world"), "trailing text");
        check(out.contains("&lt;") == false || src.contains("<at"), "no stray escape");
    }

    private static void atAndQuote() throws Exception {
        JSONArray segs = Codec.toSegments("hi <at id=\"1\"/><quote id=\"42\"/><img src=\"https://a/b.png\"/>");
        check(segs.length() >= 4, "segment count");
        eq("text", segs.getJSONObject(0).getString("type"), "text first");
        eq("at", segs.getJSONObject(1).getString("type"), "at");
        eq("1", segs.getJSONObject(1).getJSONObject("data").getString("qq"), "at qq");
        eq("reply", segs.getJSONObject(2).getString("type"), "quote->reply");
        eq("image", segs.getJSONObject(3).getString("type"), "img->image");
        String xml = Codec.fromSegments(segs, "http://127.0.0.1:3001/v1/assets/");
        check(xml.contains("<at"), "serialize at");
        check(xml.contains("<quote"), "serialize quote");
        check(xml.contains("<img"), "serialize img");
        JSONArray dataUri = Codec.toSegments("<img src=\"data:image/png;base64,iVBOR\"/>");
        eq("image", dataUri.getJSONObject(0).getString("type"), "data uri img");
        String file = dataUri.getJSONObject(0).getJSONObject("data").optString("file", "");
        check(file.startsWith("data:image/png;base64,"), "data uri kept as file spec");
        JSONObject user = Codec.user(12345L, "n", "");
        check(user.optString("avatar").contains("12345"), "user avatar");
        JSONArray fileImg = Codec.toSegments("<img src=\"file:///storage/emulated/0/DCIM/Camera/x.jpg\"/>");
        eq("image", fileImg.getJSONObject(0).getString("type"), "file img type");
        eq("file:///storage/emulated/0/DCIM/Camera/x.jpg",
                fileImg.getJSONObject(0).getJSONObject("data").optString("file"), "file img src");
        JSONArray onlyAt = Codec.toSegments("<at id=\"1\"/>");
        eq("at", onlyAt.getJSONObject(0).getString("type"), "solo at tag");
        String nativeForward = Codec.fromSegments(new JSONArray().put(new JSONObject()
                .put("type", "forward").put("data", new JSONObject()
                        .put("id", "native:7753807298269865192"))), "");
        check(nativeForward.contains("forward")
                        && nativeForward.contains("native:7753807298269865192"),
                "native forward id survives Satori encoding");
        JSONArray here = Codec.toSegments("<at type=\"here\"/>");
        eq("@在线成员", here.getJSONObject(0).getJSONObject("data").getString("text"),
                "unsupported here mention falls back to text");
    }

    private static void forwardNodes() throws Exception {
        String src = "<message forward>"
                + "<message><author id=\"2\" name=\"n\"/>hello</message>"
                + "<message><author id=\"3\" name=\"m\"/>there</message>"
                + "</message>";
        JSONArray segs = Codec.toSegments(src);
        check(segs.length() == 2, "two nodes");
        eq("node", segs.getJSONObject(0).getString("type"), "node type");
        JSONObject data = segs.getJSONObject(0).getJSONObject("data");
        eq("2", String.valueOf(data.get("user_id")), "node user");
        JSONArray content = data.getJSONArray("content");
        eq("text", content.getJSONObject(0).getString("type"), "node text");
    }

    private static void standardFallbacks() throws Exception {
        JSONArray segs = Codec.toSegments("a<br/>b<a href=\"https://example.com\">site</a><p>end</p>");
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < segs.length(); i++) {
            JSONObject seg = segs.optJSONObject(i);
            if (seg != null && "text".equals(seg.optString("type")))
                text.append(seg.optJSONObject("data").optString("text"));
        }
        eq("a\nbsite (https://example.com)end\n", text.toString(),
                "standard layout/link fallbacks");
        String emoji = Codec.fromSegments(new JSONArray().put(new JSONObject()
                .put("type", "face").put("data", new JSONObject().put("id", "14"))), "");
        check(emoji.startsWith("<emoji"), "face serializes as standard emoji");
    }

    private static void channelIds() throws Exception {
        check(Codec.isPrivateChannel("private:1001"), "private prefix");
        check(!Codec.isPrivateChannel("280183116"), "group id");
        eq(1001L, Codec.channelPeer("private:1001"), "private peer");
        eq(280183116L, Codec.channelPeer("280183116"), "group peer");
    }

    private static void eventMapping() throws Exception {
        JSONObject login = new JSONObject().put("platform", "red").put("adapter", "satori-qq");
        JSONObject history = new JSONObject()
                .put("post_type", "message")
                .put("message_type", "group")
                .put("group_id", 928613831L)
                .put("group_name", "BOT测试群")
                .put("message_id", "7753807298269865192")
                .put("qq_msg_id", 7753807298269865192L)
                .put("time", 1000)
                .put("sender", new JSONObject()
                        .put("user_id", 3373167460L)
                        .put("nickname", "nawyjx")
                        .put("card", "群名片")
                        .put("role", "admin"))
                .put("message", new JSONArray().put(new JSONObject()
                        .put("type", "text")
                        .put("data", new JSONObject().put("text", "hi"))));
        JSONObject ev = Codec.toSatoriEvent(history, login, 1, "");
        check(ev != null, "history event");
        eq("3373167460", ev.getJSONObject("user").getString("id"), "user from sender");
        eq("nawyjx", ev.getJSONObject("user").getString("name"), "user.name is QQ nick");
        eq("群名片", ev.getJSONObject("member").getString("name"), "member.name is card");
        eq("群名片", ev.getJSONObject("member").getString("nick"), "member.nick is card");
        eq("admin", ev.getJSONObject("member").getJSONArray("roles")
                .getJSONObject(0).getString("id"), "role from sender");
        eq("7753807298269865192", ev.getJSONObject("message").getString("id"),
                "public qq msgId");
        eq("BOT测试群", ev.getJSONObject("channel").getString("name"), "channel name");
        eq("BOT测试群", ev.getJSONObject("guild").getString("name"), "guild name");

        JSONObject empty = new JSONObject()
                .put("post_type", "message")
                .put("message_type", "group")
                .put("group_id", 1)
                .put("user_id", 0)
                .put("message", new JSONArray());
        check(Codec.toSatoriEvent(empty, login, 2, "") == null, "skip empty user 0");

        JSONObject recall = new JSONObject()
                .put("post_type", "notice")
                .put("notice_type", "group_recall")
                .put("group_id", 1)
                .put("user_id", 2)
                .put("operator_id", 3)
                .put("message_id", 42)
                .put("qq_msg_id", 7753807298269865192L);
        JSONObject del = Codec.toSatoriEvent(recall, login, 3, "");
        eq("7753807298269865192", del.getJSONObject("message").getString("id"),
                "recall prefers qq_msg_id");
        eq("2", del.getJSONObject("user").getString("id"), "recall user");
    }

    private static void check(boolean v, String label) {
        if (!v) throw new AssertionError(label);
    }

    private static void eq(Object expected, Object actual, String label) {
        if (expected == null ? actual != null : !String.valueOf(expected).equals(String.valueOf(actual))) {
            throw new AssertionError(label + ": expected " + expected + " got " + actual);
        }
    }
}
