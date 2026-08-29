import com.satori.qq.packet.LongMsg;
import com.satori.qq.packet.Pb;

import java.util.List;

/** Offline encode/parse checks for merge-forward Elems (text/at/face/reply/image/file). */
public final class LongMsgTest {
    public static void main(String[] args) throws Exception {
        testAt();
        testImage();
        testFile();
        testNodeRoundTrip();
        testCardXml();
        testCardJson();
        System.out.println("LongMsgTest OK");
    }

    private static void testAt() {
        byte[] all = LongMsg.elemAt("@全体成员", true, 0, "");
        LongMsg.Seg s = LongMsg.parseElems(java.util.Collections.singletonList(all)).get(0);
        eq("at", s.type, "at-all type");
        eq("all", s.qq, "at-all qq");

        byte[] one = LongMsg.elemAt("@bob", false, 20002L, "u_bob");
        s = LongMsg.parseElems(java.util.Collections.singletonList(one)).get(0);
        eq("at", s.type, "at-one type");
        eq("20002", s.qq, "at-one uin");

        s = LongMsg.parseElems(java.util.Collections.singletonList(LongMsg.elemText("@12345"))).get(0);
        eq("at", s.type, "at-fallback type");
        eq("12345", s.qq, "at-fallback qq");
    }

    private static void testImage() {
        LongMsg.Pic pic = new LongMsg.Pic();
        pic.md5 = LongMsg.md5Hex("0123456789abcdef0123456789abcdef");
        pic.width = 11;
        pic.height = 22;
        pic.size = 33;
        pic.group = true;
        pic.fileName = "0123456789abcdef0123456789abcdef.jpg";
        LongMsg.Seg s = LongMsg.parseElems(java.util.Collections.singletonList(LongMsg.elemImage(pic))).get(0);
        eq("image", s.type, "image type");
        check(s.file.contains("0123456789abcdef0123456789abcdef"), "image md5 file");
        check(s.url.contains("gchat.qpic.cn"), "image group url");
        eq(11, s.width, "image width");
    }

    private static void testFile() {
        LongMsg.FileRef f = new LongMsg.FileRef();
        f.fileId = "/abc-id";
        f.name = "note.txt";
        f.size = 9;
        f.busId = 102;
        LongMsg.Seg s = LongMsg.parseElems(java.util.Collections.singletonList(LongMsg.elemFile(f))).get(0);
        eq("file", s.type, "file type");
        eq("/abc-id", s.file, "file id");
        eq("note.txt", s.name, "file name");
        eq(9, s.size, "file size");
        eq(102, s.busid, "file busid");
    }

    private static void testNodeRoundTrip() {
        LongMsg.Node n = new LongMsg.Node();
        n.senderUin = 10001;
        n.senderName = "alice";
        n.time = 1700000000;
        n.elems.add(LongMsg.elemText("hello"));
        n.elems.add(LongMsg.elemAt("@bob", false, 20002L, "u_bob"));
        n.elems.add(LongMsg.elemFace(14));
        n.elems.add(LongMsg.elemReply(55, 20002L, 1700000000, 99, "u_bob", "[回复]"));
        LongMsg.Pic pic = new LongMsg.Pic();
        pic.md5 = LongMsg.md5Hex("ffffffffffffffffffffffffffffffff");
        pic.group = true;
        pic.size = 4;
        n.elems.add(LongMsg.elemImage(pic));
        LongMsg.FileRef f = new LongMsg.FileRef();
        f.fileId = "/fid";
        f.name = "a.txt";
        f.size = 1;
        n.elems.add(LongMsg.elemFile(f));

        byte[] body = LongMsg.buildFakeNode(280183116L, "", n);
        LongMsg.Node parsed = LongMsg.parseNode(new Pb.Reader(body));
        eq(10001, parsed.senderUin, "node uin");
        eq("alice", parsed.senderName, "node name");
        String types = "";
        for (LongMsg.Seg s : parsed.segs) types += s.type + ",";
        check(types.contains("text,"), "has text");
        check(types.contains("at,"), "has at");
        check(types.contains("face,"), "has face");
        check(types.contains("reply,"), "has reply");
        check(types.contains("image,"), "has image");
        check(types.contains("file,"), "has file");
        eq("hello", parsed.segs.get(0).text, "text body");
        eq("20002", parsed.segs.get(1).qq, "at uin");
        eq("14", parsed.segs.get(2).id, "face id");
        eq("/fid", parsed.segs.get(5).file, "file id roundtrip");
    }

    private static void testCardXml() {
        LongMsg.Node n = new LongMsg.Node();
        n.senderUin = 10001;
        n.senderName = "alice&bob";
        n.text = "hello <world>";
        java.util.List<LongMsg.Node> nodes = java.util.Collections.singletonList(n);
        String xml = LongMsg.buildXmlContent("RESID<>", "fn-1", nodes, true);
        check(xml.contains("serviceID=\"35\""), "xml serviceID");
        check(xml.contains("action=\"viewMultiMsg\""), "xml action");
        check(xml.contains("m_resid=\"RESID&lt;&gt;\""), "xml resid escaped");
        check(xml.contains("m_fileName=\"fn-1\""), "xml fileName");
        check(xml.contains("alice&amp;bob: hello &lt;world&gt;"), "xml preview escaped");
        check(xml.contains("群聊的聊天记录"), "xml group source");
        check(xml.contains("查看1条转发消息"), "xml summary");
    }

    private static void testCardJson() throws Exception {
        LongMsg.Node n = new LongMsg.Node();
        n.senderUin = 10001;
        n.senderName = "alice";
        n.text = "hello";
        java.util.List<LongMsg.Node> nodes = java.util.Collections.singletonList(n);
        LongMsg.Card card = LongMsg.buildCard("RID", nodes, true, "uuid-1");
        org.json.JSONObject j = new org.json.JSONObject(card.json);
        check(j.optJSONObject("extra") != null, "extra is object");
        eq("uuid-1", j.getJSONObject("extra").getString("filename"), "extra filename");
        eq("RID", j.getJSONObject("meta").getJSONObject("detail").getString("resid"), "resid");
        eq("uuid-1", j.getJSONObject("meta").getJSONObject("detail").getString("uniseq"), "uniseq");
        byte[] req = LongMsg.buildUploadReq(280183116L, "u_self", nodes, "uuid-1");
        check(req.length > 20, "upload req");
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
