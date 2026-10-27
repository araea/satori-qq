package com.satori.qq.packet;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 最小的 JCE（TARS）编解码，够发手机 QQ 还在用的老式 WUP 请求（{@code VisitorSvc.*} 这类）。
 *
 * <p>只认用得到的那几种类型：整数、字符串、map、字节串（SIMPLE_LIST）与结构体；读的时候把
 * 结构体解成「tag → 值」的表，调用方按字段号取。
 */
public final class Jce {
    private Jce() {}

    static final int INT1 = 0, INT2 = 1, INT4 = 2, INT8 = 3, FLOAT = 4, DOUBLE = 5;
    static final int STRING1 = 6, STRING4 = 7, MAP = 8, LIST = 9;
    static final int STRUCT_BEGIN = 10, STRUCT_END = 11, ZERO = 12, SIMPLE_LIST = 13;

    // ---------------------------------------------------------------- Writer

    public static final class Writer {
        private final ByteArrayOutputStream o = new ByteArrayOutputStream();

        private void head(int type, int tag) {
            if (tag < 15) o.write((tag << 4) | type);
            else { o.write(0xF0 | type); o.write(tag); }
        }

        public Writer num(int tag, long v) {
            if (v == 0) head(ZERO, tag);
            else if (v >= Byte.MIN_VALUE && v <= Byte.MAX_VALUE) { head(INT1, tag); o.write((int) v); }
            else if (v >= Short.MIN_VALUE && v <= Short.MAX_VALUE) { head(INT2, tag); be(v, 2); }
            else if (v >= Integer.MIN_VALUE && v <= Integer.MAX_VALUE) { head(INT4, tag); be(v, 4); }
            else { head(INT8, tag); be(v, 8); }
            return this;
        }

        public Writer str(int tag, String s) {
            byte[] b = (s == null ? "" : s).getBytes(StandardCharsets.UTF_8);
            if (b.length <= 255) { head(STRING1, tag); o.write(b.length); }
            else { head(STRING4, tag); be(b.length, 4); }
            o.write(b, 0, b.length);
            return this;
        }

        public Writer bytes(int tag, byte[] b) {
            head(SIMPLE_LIST, tag);
            head(INT1, 0);
            num(0, b.length);
            o.write(b, 0, b.length);
            return this;
        }

        public Writer struct(int tag, Writer inner) {
            head(STRUCT_BEGIN, tag);
            byte[] b = inner.toByteArray();
            o.write(b, 0, b.length);
            head(STRUCT_END, 0);
            return this;
        }

        /** map&lt;string, string&gt; 或 map&lt;string, bytes&gt;（值是 byte[] 时按字节串写）。 */
        public Writer map(int tag, Map<String, ?> m) {
            head(MAP, tag);
            num(0, m.size());
            for (Map.Entry<String, ?> e : m.entrySet()) {
                str(0, e.getKey());
                Object v = e.getValue();
                if (v instanceof byte[] b) bytes(1, b);
                else str(1, String.valueOf(v));
            }
            return this;
        }

        private void be(long v, int n) {
            for (int i = n - 1; i >= 0; i--) o.write((int) (v >>> (8 * i)) & 0xFF);
        }

        public byte[] toByteArray() { return o.toByteArray(); }
    }

    public static Writer w() { return new Writer(); }

    // ---------------------------------------------------------------- Reader

    /** 解一段平铺的字段（结构体内部或整个包），返回 tag → 值。结构体是嵌套的 Map。 */
    public static Map<Integer, Object> read(byte[] b) {
        int[] pos = {0};
        return fields(b, pos, false);
    }

    private static Map<Integer, Object> fields(byte[] b, int[] pos, boolean inStruct) {
        Map<Integer, Object> out = new LinkedHashMap<>();
        while (pos[0] < b.length) {
            int h = b[pos[0]++] & 0xFF;
            int type = h & 0x0F, tag = h >> 4;
            if (tag == 15) tag = b[pos[0]++] & 0xFF;
            if (type == STRUCT_END) {
                if (inStruct) return out;
                continue;
            }
            out.put(tag, value(b, pos, type));
        }
        return out;
    }

    private static Object value(byte[] b, int[] pos, int type) {
        switch (type) {
            case ZERO: return 0L;
            case INT1: return (long) b[pos[0]++];
            case INT2: return be(b, pos, 2);
            case INT4: return be(b, pos, 4);
            case INT8: return be(b, pos, 8);
            case FLOAT: pos[0] += 4; return 0L;
            case DOUBLE: pos[0] += 8; return 0L;
            case STRING1: {
                int n = b[pos[0]++] & 0xFF;
                return text(b, pos, n);
            }
            case STRING4: return text(b, pos, (int) be(b, pos, 4));
            case STRUCT_BEGIN: return fields(b, pos, true);
            case SIMPLE_LIST: {
                pos[0]++; // 元素类型头（INT1 @ tag 0）
                int n = (int) number(b, pos);
                byte[] out = new byte[n];
                System.arraycopy(b, pos[0], out, 0, n);
                pos[0] += n;
                return out;
            }
            case MAP: {
                int n = (int) number(b, pos);
                Map<Object, Object> m = new LinkedHashMap<>();
                for (int i = 0; i < n; i++) {
                    Object k = element(b, pos);
                    Object v = element(b, pos);
                    m.put(k, v);
                }
                return m;
            }
            case LIST: {
                int n = (int) number(b, pos);
                List<Object> l = new ArrayList<>();
                for (int i = 0; i < n; i++) l.add(element(b, pos));
                return l;
            }
            default: throw new IllegalArgumentException("jce type " + type);
        }
    }

    /** map/list 里的一个元素：自带头。 */
    private static Object element(byte[] b, int[] pos) {
        int h = b[pos[0]++] & 0xFF;
        if ((h >> 4) == 15) pos[0]++;
        return value(b, pos, h & 0x0F);
    }

    private static long number(byte[] b, int[] pos) {
        Object v = element(b, pos);
        return v instanceof Long l ? l : 0;
    }

    private static long be(byte[] b, int[] pos, int n) {
        long v = 0;
        for (int i = 0; i < n; i++) v = (v << 8) | (b[pos[0]++] & 0xFF);
        // 符号扩展
        int shift = 64 - 8 * n;
        return (v << shift) >> shift;
    }

    private static String text(byte[] b, int[] pos, int n) {
        String s = new String(b, pos[0], n, StandardCharsets.UTF_8);
        pos[0] += n;
        return s;
    }

    // ---------------------------------------------------------------- UniPacket

    /**
     * WUP v3 的 RequestPacket：{@code sBuffer} 是 map&lt;函数名, 结构体字节&gt;，整包前面带 4 字节
     * 大端长度（含这 4 字节本身）。{@code body} 是结构体字段（不含外层 STRUCT_BEGIN/END）。
     */
    public static byte[] uniPacket(int requestId, String servant, String func, String key, Writer body) {
        byte[] struct = w().struct(0, body).toByteArray();
        Map<String, Object> buffer = new LinkedHashMap<>();
        buffer.put(key, struct);
        byte[] sBuffer = w().map(0, buffer).toByteArray();
        byte[] pkt = w()
                .num(1, 3)
                .num(2, 0)
                .num(3, 0)
                .num(4, requestId)
                .str(5, servant)
                .str(6, func)
                .bytes(7, sBuffer)
                .num(8, 0)
                .map(9, Map.of())
                .map(10, Map.of())
                .toByteArray();
        byte[] out = new byte[pkt.length + 4];
        int len = out.length;
        out[0] = (byte) (len >>> 24); out[1] = (byte) (len >>> 16); out[2] = (byte) (len >>> 8); out[3] = (byte) len;
        System.arraycopy(pkt, 0, out, 4, pkt.length);
        return out;
    }

    /** 拆回包：去掉长度头（有的话），取 sBuffer 里 {@code key} 那个结构体的字段；没有就取第一个。 */
    @SuppressWarnings("unchecked")
    public static Map<Integer, Object> uniResponse(byte[] raw, String key) {
        if (raw == null || raw.length < 4) throw new IllegalArgumentException("empty WUP reply");
        // 通常带 4 字节长度头；万一通道已经替我们剥掉了，就按原样解。
        int len = ((raw[0] & 0xFF) << 24) | ((raw[1] & 0xFF) << 16) | ((raw[2] & 0xFF) << 8) | (raw[3] & 0xFF);
        byte[] pkt = len == raw.length ? Arrays.copyOfRange(raw, 4, raw.length) : raw;
        Object sBuffer = read(pkt).get(7);
        if (!(sBuffer instanceof byte[] buf)) throw new IllegalArgumentException("WUP reply without sBuffer");
        Object map = read(buf).get(0);
        if (!(map instanceof Map<?, ?> m) || m.isEmpty()) throw new IllegalArgumentException("WUP reply sBuffer empty");
        Object v = m.containsKey(key) ? m.get(key) : m.values().iterator().next();
        if (!(v instanceof byte[] struct)) throw new IllegalArgumentException("WUP reply value is not bytes");
        Object top = read(struct).get(0);
        if (!(top instanceof Map)) throw new IllegalArgumentException("WUP reply struct missing");
        return (Map<Integer, Object>) top;
    }
}
