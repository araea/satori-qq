package com.satori.qq.qq;

import org.json.JSONObject;

/**
 * 只读探针：把 QQ 自己那套安全 SDK（QSec）算出来的环境结论抓出来看。
 *
 * <p>服务端收到的环境证据就是 {@code QSec.getXpsInfo()} 这批字节（外加 Turing/Beacon 那几条）。
 * 与其猜「进程里还有什么痕迹被抓到」，不如直接读 QQ 自己的结论：里面如果写着 root/xposed/hook
 * 之类的标记，就知道该动哪一项；如果写的是干净的而我们仍然失败，那就说明判据在客户端之外。</p>
 *
 * <p>它只调用 QQ 自己的公开方法、不改任何返回值、不挂钩子——和旧实现刻意相反。</p>
 */
public final class EnvProbe {

    private static volatile ClassLoader holder;

    private EnvProbe() {
    }

    /** Main 在宿主进程里存一份，供只读探针使用。 */
    public static void hold(ClassLoader cl) {
        holder = cl;
    }

    public static JSONObject snapshot() throws Exception {
        ClassLoader cl = holder;
        JSONObject out = new JSONObject();
        if (cl == null) { out.put("error", "no classloader"); return out; }
        Object inst = null;
        Class<?> qsec = null;
        try {
            qsec = Class.forName("com.tencent.mobileqq.qsec.qsecurity.QSec", false, cl);
            inst = qsec.getMethod("getInstance").invoke(null);
            out.put("qsec_present", inst != null);
        } catch (Throwable t) {
            out.put("qsec_error", String.valueOf(t));
            return out;
        }
        try {
            Object est = qsec.getMethod("getEstInfo").invoke(inst);
            out.put("est_info", shorten(String.valueOf(est), 400));
        } catch (Throwable t) {
            out.put("est_info_error", String.valueOf(t));
        }
        try {
            Object raw = qsec.getMethod("getXpsInfo").invoke(inst);
            if (raw instanceof byte[]) {
                byte[] b = (byte[]) raw;
                out.put("xps_len", b.length);
                String text = asText(b);
                if (text != null) {
                    out.put("xps_text", shorten(text, 1200));
                } else {
                    out.put("xps_hex_head", hex(b, 96));
                }
            } else {
                out.put("xps_type", raw == null ? "null" : raw.getClass().getName());
            }
        } catch (Throwable t) {
            out.put("xps_error", String.valueOf(t));
        }
        return out;
    }

    /** 全是可打印 ASCII 才当文本，否则返回 null（避免把二进制塞进 JSON）。 */
    private static String asText(byte[] b) {
        int printable = 0;
        for (byte x : b) {
            int c = x & 0xff;
            if (c == 9 || c == 10 || c == 13 || (c >= 32 && c < 127)) printable++;
        }
        if (printable < b.length * 95 / 100) return null;
        try {
            return new String(b, "UTF-8");
        } catch (Throwable t) {
            return null;
        }
    }

    private static String hex(byte[] b, int max) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < b.length && i < max; i++) sb.append(String.format("%02x", b[i]));
        if (b.length > max) sb.append("...");
        return sb.toString();
    }

    private static String shorten(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
