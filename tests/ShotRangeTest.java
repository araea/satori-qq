import com.satori.qq.core.ShotRange;
import org.json.JSONArray;
import org.json.JSONObject;

public final class ShotRangeTest {
    private static JSONArray page(int from, int to) throws Exception {
        JSONArray rows = new JSONArray();
        for (int i = from; i <= to; i++) rows.put(new JSONObject().put("id", String.valueOf(i)).put("message_seq", i));
        return rows;
    }
    private static void fail(Runnable r) {
        try { r.run(); throw new AssertionError("accepted invalid range"); }
        catch (IllegalArgumentException | IllegalStateException expected) { }
    }
    public static void main(String[] args) throws Exception {
        ShotRange single = new ShotRange("3", "3");
        single.add(page(3, 7));
        if (single.messages().size() != 1) throw new AssertionError("single endpoint");
        ShotRange multiple = new ShotRange("3", "5");
        multiple.add(page(3, 4));
        fail(() -> multiple.messages());
        multiple.add(page(5, 6));
        if (multiple.messages().size() != 3) throw new AssertionError("inclusive range");
        JSONArray wrongStart = page(4, 5);
        fail(() -> new ShotRange("3", "5").add(wrongStart));
        ShotRange missing = new ShotRange("3", "5");
        missing.add(page(3, 4));
        fail(() -> missing.add(wrongStart));
        JSONArray tooMany = page(1, 41);
        fail(() -> new ShotRange("1", "50").add(tooMany));
        String display = ShotRange.displayText("A&amp;B<img src=\"http://x\"/><audio src=\"x\"/> <b>hi</b>");
        if (!"A&B[图片][语音] hi".equals(display)) throw new AssertionError(display);
        if (!"[空消息]".equals(ShotRange.displayText(""))) throw new AssertionError("empty");
        System.out.println("ShotRangeTest OK");
    }
}
