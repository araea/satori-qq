package com.satori.qq.qq;

import java.util.List;
import org.json.JSONObject;

/** 网页凭据的 bkn 口径与群文件条目的整理。 */
public final class QunWebTest {
    public static void main(String[] args) throws Exception {
        String pskey = "abcdefghijKLMNOPQRSTUVWXYZ0123456789-_*xyz";
        List<Long> noSkey = new QunWeb.Ticket("1", pskey, "").bkns();
        check(noSkey.get(0) == Qzone.gtk(pskey.substring(0, 10)), "没有 skey 时先按 p_skey 前 10 位算（网页的写法）");
        check(noSkey.contains(Qzone.gtk(pskey)), "整串 p_skey 的那个留作后备");
        List<Long> withSkey = new QunWeb.Ticket("1", pskey, "@skey").bkns();
        check(withSkey.get(0) == Qzone.gtk("@skey"), "有 skey 先用 skey");

        JSONObject f = new JSONObject().put("md5", "¯�T").put("sha", "0a1B")
                .put("uploadTime", 0).put("uploadedSize", 1776905703L).put("fileSize", 52158464L);
        ExtraSvc.tidyKernelFile(f);
        check(!f.has("md5"), "按 UTF-8 硬解的二进制摘要要丢掉");
        check("0a1B".equals(f.optString("sha")), "真的十六进制留着");
        check(f.optLong("uploadTime") == 1776905703L && !f.has("uploadedSize"), "上传时间从 uploadedSize 搬回来");
        System.out.println("QunWebTest OK");
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }
}
