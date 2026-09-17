package com.satori.qq.satori;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 出站批次：把「顺媒体 + 别的段落」这种 QQ 渲染不出来的拼法拆成几条。
 *
 * <p>语音、视频、群文件在 QQ 里是「顺媒体」元素：一条消息只要带了其中一种，这条消息
 * 就只能有它自己——再挂引用、@、文字、图片，客户端渲染不出与它同条的内容（那段顺媒体
 * 显示成空，引用与文字也一起乱）。2026-09-17 实测：ayjx 的成品带了一条引用，群里
 * 只看到一个「引用 + 空视频」的气泡。
 *
 * <p>客户端不知道对面是 QQ，也没法在协议层拦下这种拼法，所以在实现端统一兼容：按原
 * 顺序拆开——其余段落合成一条先发，每个顺媒体各成一条；拆出来只剩引用那种空壳就不发
 * （发出去是一个只有引用的空气泡）。顺序不变，内容不丢。
 *
 * <p>纯逻辑，不碰 QQ 内核，用例见 {@code tests/BatchingTest.java}。
 */
public final class Batching {
    private Batching() {}

    /** 一条消息里只能单独出现的元素。 */
    private static boolean isChunkMedia(Elements.El el) {
        if (el == null) return false;
        switch (el.type) {
            case "video":
            case "audio":
            case "record":
            case "file":
                return true;
            default:
                return false;
        }
    }

    /** 顶层顺媒体与「里面装着顺媒体」的容器（`<p><video/></p>` 这种）都算。 */
    private static boolean holdsChunkMedia(Elements.El el) {
        if (isChunkMedia(el)) return true;
        for (Elements.El child : el.children) {
            if (holdsChunkMedia(child)) return true;
        }
        return false;
    }

    /**
     * 拆一条批次：没有顺媒体就原样返回（仍是那一条），有就按位置拆成几条。
     *
     * <p>空批次返回空列表——调用方按「没有要发的」处理，与从前一样。
     */
    public static List<List<Elements.El>> splitChunkMedia(List<Elements.El> batch) {
        if (batch == null || batch.isEmpty()) return Collections.emptyList();
        boolean split = false;
        for (Elements.El el : batch) {
            if (holdsChunkMedia(el)) {
                split = true;
                break;
            }
        }
        if (!split) return Collections.singletonList(batch);

        List<List<Elements.El>> out = new ArrayList<>();
        List<Elements.El> rest = new ArrayList<>();
        for (Elements.El el : batch) {
            if (!holdsChunkMedia(el)) {
                rest.add(el);
                continue;
            }
            flushRest(out, rest);
            rest = new ArrayList<>();
            List<Elements.El> one = new ArrayList<>();
            one.add(el);
            out.add(one);
        }
        flushRest(out, rest);
        return out;
    }

    /** 顺媒体前后攒下的那些段落：只有引用的空壳丢掉，别的照发。 */
    private static void flushRest(List<List<Elements.El>> out, List<Elements.El> rest) {
        if (rest.isEmpty()) return;
        for (Elements.El el : rest) {
            if (!"quote".equals(el.type)) {
                out.add(rest);
                return;
            }
        }
    }
}
