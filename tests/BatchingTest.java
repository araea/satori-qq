import com.satori.qq.satori.Batching;
import com.satori.qq.satori.Elements;

import java.util.List;

/**
 * 拆批次：语音、视频、群文件在 QQ 里只能单独成一条消息，与引用、@、文字放在同一条里
 * 客户端渲染不出来（2026-09-17 实测：带引用的成品在群里只显示一个空气泡）。
 */
public final class BatchingTest {
    public static void main(String[] args) {
        nothingToSplit();
        mediaGoesOutAlone();
        bareQuoteIsDropped();
        nestedMediaCounts();
        System.out.println("BatchingTest OK");
    }

    private static void nothingToSplit() {
        eq(1, split("<at id=\"1\"/>喏<quote id=\"9\"/>").size(), "纯文本一条照旧");
        eq(1, split("<quote id=\"9\"/><img src=\"a\"/>").size(), "图片可以和引用同条");
        eq(0, split("").size(), "空批次仍是空的");
    }

    private static void mediaGoesOutAlone() {
        List<List<Elements.El>> video = split("<quote id=\"9\"/><video src=\"a\"/>");
        eq(1, video.size(), "单独的视频不必拆（拆出来只剩空引用）");
        eq("video", video.get(0).get(0).type, "留下的就是那段视频");

        List<List<Elements.El>> mixed = split("<quote id=\"9\"/>喏<video src=\"a\"/>");
        eq(2, mixed.size(), "文字与视频拆成两条");
        eq("quote", mixed.get(0).get(0).type, "引用跟着文字走");
        eq("text", mixed.get(0).get(1).type, "先发的是没有顺媒体的那条");
        eq("video", mixed.get(1).get(0).type, "视频单独一条");

        List<List<Elements.El>> audio = split("<at id=\"1\"/>听这个<audio src=\"a\"/>");
        eq(2, audio.size(), "语音同样单独一条");
        eq("audio", audio.get(1).get(0).type, "语音那一条只有语音");

        List<List<Elements.El>> file = split("文件给你<file src=\"a\"/>");
        eq(2, file.size(), "群文件也要拆");
        eq("file", file.get(1).get(0).type, "文件那一条只有文件");

        // 顺序不变：顺媒体在前就还是在前，后面那段另起一条。
        List<List<Elements.El>> tail = split("<video src=\"a\"/>喏");
        eq(2, tail.size(), "视频后面的文字另起一条");
        eq("video", tail.get(0).get(0).type, "视频保持原来的位置");
        eq("text", tail.get(1).get(0).type, "文字跟在后面");

        // 两个顺媒体各成一条。
        List<List<Elements.El>> both = split("<video src=\"a\"/><file src=\"b\"/>");
        eq(2, both.size(), "视频与文件各一条");
        eq("file", both.get(1).get(0).type, "第二条是文件");
    }

    /** 拆出来只剩引用就是空气泡，直接不发。 */
    private static void bareQuoteIsDropped() {
        List<List<Elements.El>> before = split("<quote id=\"9\"/><video src=\"a\"/>");
        eq(1, before.size(), "引用 + 视频不产生一条空引用");
        List<List<Elements.El>> between = split("<video src=\"a\"/><quote id=\"9\"/><file src=\"b\"/>");
        eq(2, between.size(), "夹在两个顺媒体之间的引用也丢掉");
        eq("file", between.get(1).get(0).type, "剩下的仍是两条顺媒体");
        List<List<Elements.El>> onlyQuote = split("<quote id=\"9\"/>");
        eq(1, onlyQuote.size(), "本来就只有引用的消息不动（不是这次兼容的事）");
    }

    /** `<p><video/></p>` 这种把顺媒体包在容器里的拼法同样要拆出来。 */
    private static void nestedMediaCounts() {
        List<List<Elements.El>> nested = split("喏<p><video src=\"a\"/></p>");
        eq(2, nested.size(), "容器里的顺媒体也算");
        eq("text", nested.get(0).get(0).type, "文字先发");
        eq("p", nested.get(1).get(0).type, "装着视频的那一段单独一条");
    }

    private static List<List<Elements.El>> split(String content) {
        return Batching.splitChunkMedia(Elements.parse(content));
    }

    /** 计数用等号的写法统一成这一处：`1` 会被装成 Integer，equals 比值。 */
    private static void eq(Object expected, Object actual, String what) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(what + ": expected " + expected + " but was " + actual);
        }
    }
}
