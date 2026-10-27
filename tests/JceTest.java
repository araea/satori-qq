import com.satori.qq.packet.Jce;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** 离线检查 JCE 编解码与 WUP 包形状（点赞走的 VisitorSvc.ReqFavorite 靠它）。 */
public final class JceTest {
    public static void main(String[] args) throws Exception {
        numbers();
        uniPacketRoundTrip();
        knownHead();
        System.out.println("JceTest OK");
    }

    private static void numbers() {
        byte[] b = Jce.w().num(0, 0).num(1, 7).num(2, 300).num(3, 70000).num(4, 3_000_000_000L)
                .num(20, -1).str(5, "VisitorSvc").toByteArray();
        Map<Integer, Object> m = Jce.read(b);
        eq(0L, m.get(0), "zero");
        eq(7L, m.get(1), "int1");
        eq(300L, m.get(2), "int2");
        eq(70000L, m.get(3), "int4");
        eq(3_000_000_000L, m.get(4), "int8");
        eq(-1L, m.get(20), "tag>=15 负数");
        eq("VisitorSvc", m.get(5), "string1");
    }

    @SuppressWarnings("unchecked")
    private static void uniPacketRoundTrip() {
        Jce.Writer head = Jce.w().num(0, 10001).num(1, 1).num(2, 5).num(3, 1).num(4, 0)
                .bytes(5, new byte[]{1, 2, 3});
        Jce.Writer body = Jce.w().struct(0, head).num(1, 20002).num(2, 0).num(3, 66).num(4, 10);
        byte[] pkt = Jce.uniPacket(5, "VisitorSvc", "ReqFavorite", "ReqFavorite", body);
        int len = ((pkt[0] & 0xFF) << 24) | ((pkt[1] & 0xFF) << 16) | ((pkt[2] & 0xFF) << 8) | (pkt[3] & 0xFF);
        eq(pkt.length, len, "长度头含自身 4 字节");
        Map<Integer, Object> outer = Jce.read(Arrays.copyOfRange(pkt, 4, pkt.length));
        eq(3L, outer.get(1), "iVersion=3");
        eq("VisitorSvc", outer.get(5), "servant");
        eq("ReqFavorite", outer.get(6), "func");
        // 同一个解包函数既能拆请求也能拆回包：sBuffer 形状一样。
        Map<Integer, Object> req = Jce.uniResponse(pkt, "ReqFavorite");
        eq(20002L, req.get(1), "lMID");
        eq(66L, req.get(3), "emSource");
        eq(10L, req.get(4), "iCount");
        Map<Integer, Object> h = (Map<Integer, Object>) req.get(0);
        eq(10001L, h.get(0), "ReqHead.lUIN");
        eq(3, ((byte[]) h.get(5)).length, "vCookies");
    }

    /** RespFavorite 的头：iReplyCode 在 RespHead 的 3 号字段、strResult 在 4 号。 */
    @SuppressWarnings("unchecked")
    private static void knownHead() {
        Jce.Writer rh = Jce.w().num(0, 1).num(1, 5).num(2, 0).num(3, 10003).str(4, "由于对方权限设置，点赞失败");
        Jce.Writer body = Jce.w().struct(0, rh).num(1, 20002);
        byte[] pkt = Jce.uniPacket(5, "VisitorSvc", "RespFavorite", "RespFavorite", body);
        Map<Integer, Object> resp = Jce.uniResponse(pkt, "RespFavorite");
        Map<Integer, Object> h = (Map<Integer, Object>) resp.get(0);
        eq(10003L, h.get(3), "iReplyCode");
        eq("由于对方权限设置，点赞失败", h.get(4), "strResult");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("k", "v");
        Map<Integer, Object> mm = Jce.read(Jce.w().map(0, m).toByteArray());
        eq("v", ((Map<Object, Object>) mm.get(0)).get("k"), "map<string,string>");
    }

    private static void eq(Object want, Object got, String label) {
        if (want == null ? got != null : !want.equals(got))
            throw new AssertionError(label + ": want " + want + " got " + got);
    }
}
