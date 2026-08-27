package com.onebot.qq.packet;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Minimal protobuf (proto2/proto3 wire) encoder/decoder — enough for OIDB / trpc packets.
 *  No external dependency. Fields are addressed by tag number. */
public final class Pb {

    // ---------------- Writer ----------------
    public static final class Writer {
        private final ByteArrayOutputStream o = new ByteArrayOutputStream();

        public Writer varint(int field, long value) {
            tag(field, 0); writeVarint(value); return this;
        }
        public Writer bool(int field, boolean v) { return varint(field, v ? 1 : 0); }
        public Writer fixed32(int field, int v) {
            tag(field, 5);
            o.write(v & 0xFF); o.write((v >>> 8) & 0xFF); o.write((v >>> 16) & 0xFF); o.write((v >>> 24) & 0xFF);
            return this;
        }
        public Writer fixed64(int field, long v) {
            tag(field, 1);
            for (int i = 0; i < 8; i++) o.write((int) ((v >>> (8 * i)) & 0xFF));
            return this;
        }
        public Writer bytes(int field, byte[] v) {
            tag(field, 2); writeVarint(v.length); o.write(v, 0, v.length); return this;
        }
        public Writer string(int field, String v) {
            try { return bytes(field, v.getBytes("UTF-8")); } catch (Exception e) { return this; }
        }
        public Writer message(int field, Writer sub) { return bytes(field, sub.toByteArray()); }
        public Writer message(int field, byte[] sub) { return bytes(field, sub); }

        private void tag(int field, int wire) { writeVarint(((long) field << 3) | wire); }
        private void writeVarint(long v) {
            while (true) {
                int b = (int) (v & 0x7F);
                v >>>= 7;
                if (v != 0) o.write(b | 0x80); else { o.write(b); break; }
            }
        }
        public byte[] toByteArray() { return o.toByteArray(); }
    }

    public static Writer w() { return new Writer(); }

    // ---------------- Reader ----------------
    /** Parsed message: field number -> list of raw values (Long for varint/fixed, byte[] for len-delimited). */
    public static final class Reader {
        public final Map<Integer, List<Object>> fields = new HashMap<>();

        public Reader(byte[] data) { parse(data, 0, data.length); }
        public Reader(byte[] data, int off, int len) { parse(data, off, off + len); }

        private void parse(byte[] d, int p, int end) {
            while (p < end) {
                long[] t = varint(d, p); long tag = t[0]; p = (int) t[1];
                int field = (int) (tag >>> 3), wire = (int) (tag & 7);
                Object val; 
                switch (wire) {
                    case 0: { long[] v = varint(d, p); val = v[0]; p = (int) v[1]; break; }
                    case 1: { long v = 0; for (int i = 0; i < 8; i++) v |= (long) (d[p+i] & 0xFF) << (8*i); p += 8; val = v; break; }
                    case 5: { int v = 0; for (int i = 0; i < 4; i++) v |= (d[p+i] & 0xFF) << (8*i); p += 4; val = (long) v; break; }
                    case 2: { long[] l = varint(d, p); int ln = (int) l[0]; p = (int) l[1];
                              byte[] b = new byte[ln]; System.arraycopy(d, p, b, 0, ln); p += ln; val = b; break; }
                    default: return; // unknown wire, stop
                }
                fields.computeIfAbsent(field, k -> new ArrayList<>()).add(val);
            }
        }
        public byte[] bytes(int field) { List<Object> l = fields.get(field); return l == null ? null : (byte[]) l.get(0); }
        public String str(int field) { byte[] b = bytes(field); try { return b == null ? null : new String(b, "UTF-8"); } catch (Exception e) { return null; } }
        public long num(int field) { List<Object> l = fields.get(field); return l == null ? 0 : (Long) l.get(0); }
        public Reader msg(int field) { byte[] b = bytes(field); return b == null ? null : new Reader(b); }
        public List<Object> all(int field) { return fields.get(field); }
    }

    private static long[] varint(byte[] d, int p) {
        long v = 0; int shift = 0;
        while (true) { int b = d[p++] & 0xFF; v |= (long) (b & 0x7F) << shift; if ((b & 0x80) == 0) break; shift += 7; }
        return new long[]{v, p};
    }

    // ---------------- OIDB wrapper (oidb_sso.OIDBSSOPkg, from QQ.hap oidb.proto) ----------------
    /** Build an OIDBSSOPkg: cmd=0xXXXX, serviceType, body. */
    public static byte[] oidb(int command, int serviceType, byte[] body) {
        return w().varint(1, command).varint(2, serviceType).bytes(4, body).string(6, "9.3.50").toByteArray();
    }
    /** Extract bytes_bodybuffer (field 4) + result (field 3) from an OIDBSSOPkg reply. */
    public static byte[] oidbBody(byte[] pkg) { return new Reader(pkg).bytes(4); }
    public static long oidbResult(byte[] pkg) { return new Reader(pkg).num(3); }
}
