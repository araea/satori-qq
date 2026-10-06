package com.satori.qq.satori;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Streaming, binary-safe RFC 7578 reader for {@code upload.create}.
 *
 * <p>The request body is never held in memory: every part is written straight to a file in the
 * directory the caller gives, through a fixed window. A 60 MiB video costs 128 KiB of heap here,
 * not two copies of it inside the QQ process.
 */
public final class Multipart {
    private Multipart() {}

    /** The longest header block one part may carry. */
    private static final int MAX_HEADER_BYTES = 16 * 1024;
    private static final int MAX_PREAMBLE_BYTES = 64 * 1024;
    private static final int MAX_PARTS = 64;

    public static final class Part {
        public String name = "";
        public String filename = "";
        public String contentType = "";
        /** Where the part's bytes were written. The caller owns (renames or deletes) it. */
        public File file;
        public long size;
    }

    /** A part (or the whole body) went past the size the caller allows: a 413, not a 400. */
    public static final class TooLarge extends IllegalArgumentException {
        public TooLarge(String message) { super(message); }
    }

    /**
     * Read the multipart body from {@code in} until the closing delimiter. Anything after it is left
     * unread. On any failure the files already written are deleted.
     *
     * @throws IllegalArgumentException malformed body; {@link TooLarge} when a part exceeds {@code maxPartBytes}
     * @throws IOException              the connection failed while reading
     */
    public static List<Part> parseStream(InputStream in, String contentType, File dir, long maxPartBytes)
            throws IOException {
        String boundary = boundaryOf(contentType);
        if (boundary.isEmpty()) throw new IllegalArgumentException("missing multipart boundary");
        if (boundary.length() > 200) throw new IllegalArgumentException("multipart boundary too long");
        byte[] marker = ("--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
        byte[] nextMarker = ("\r\n--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
        byte[] headerEnd = "\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);
        Window w = new Window(in);
        ArrayList<Part> out = new ArrayList<>();
        try {
            skipPreamble(w, marker);
            while (true) {
                if (w.ensure(2) < 2) throw new IllegalArgumentException("multipart not terminated");
                if (w.buf[w.pos] == '-' && w.buf[w.pos + 1] == '-') break;
                if (w.buf[w.pos] != '\r' || w.buf[w.pos + 1] != '\n')
                    throw new IllegalArgumentException("malformed multipart delimiter");
                w.pos += 2;

                int headersAt;
                while ((headersAt = indexOf(w.buf, w.pos, w.end, headerEnd)) < 0) {
                    if (w.end - w.pos >= MAX_HEADER_BYTES) throw new IllegalArgumentException("multipart headers too long");
                    if (!w.more()) throw new IllegalArgumentException("multipart headers not terminated");
                }
                Map<String, String> headers = headers(new String(w.buf, w.pos, headersAt - w.pos,
                        StandardCharsets.ISO_8859_1));
                w.pos = headersAt + headerEnd.length;

                Part part = new Part();
                String disposition = headers.get("content-disposition");
                if (disposition == null || !disposition.toLowerCase(Locale.ROOT).startsWith("form-data"))
                    throw new IllegalArgumentException("missing form-data disposition");
                part.name = parameter(disposition, "name");
                part.filename = parameter(disposition, "filename");
                part.contentType = value(headers.get("content-type"));
                part.file = File.createTempFile("ntm", ".part", dir);
                out.add(part);
                if (out.size() > MAX_PARTS) throw new IllegalArgumentException("too many multipart parts");
                part.size = copyPart(w, nextMarker, part.file, maxPartBytes);
            }
            return out;
        } catch (IOException | RuntimeException e) {
            for (Part part : out) if (part.file != null) part.file.delete();
            throw e;
        }
    }

    /** Discard bytes up to and including the first delimiter. */
    private static void skipPreamble(Window w, byte[] marker) throws IOException {
        long skipped = 0;
        while (true) {
            int at = indexOf(w.buf, w.pos, w.end, marker);
            if (at >= 0) { w.pos = at + marker.length; return; }
            int keep = marker.length - 1;
            int drop = (w.end - w.pos) - keep;
            if (drop > 0) { w.pos += drop; skipped += drop; }
            if (skipped > MAX_PREAMBLE_BYTES) throw new IllegalArgumentException("multipart boundary not found");
            if (!w.more()) throw new IllegalArgumentException("multipart boundary not found");
        }
    }

    /**
     * Write the part's data to {@code file}, stopping at the next delimiter, which is consumed.
     * Returns the data length. The last {@code nextMarker.length - 1} bytes of the window are held
     * back each round, so a delimiter split across two reads is still found.
     */
    private static long copyPart(Window w, byte[] nextMarker, File file, long max) throws IOException {
        long size = 0;
        try (OutputStream sink = new FileOutputStream(file)) {
            while (true) {
                int at = indexOf(w.buf, w.pos, w.end, nextMarker);
                if (at >= 0) {
                    sink.write(w.buf, w.pos, at - w.pos);
                    size += at - w.pos;
                    w.pos = at + nextMarker.length;
                    if (size > max) throw new TooLarge("multipart part exceeds " + max + " bytes");
                    return size;
                }
                int safe = (w.end - w.pos) - (nextMarker.length - 1);
                if (safe > 0) {
                    sink.write(w.buf, w.pos, safe);
                    size += safe;
                    w.pos += safe;
                    if (size > max) throw new TooLarge("multipart part exceeds " + max + " bytes");
                }
                if (!w.more()) throw new IllegalArgumentException("multipart part not terminated");
            }
        }
    }

    /** A sliding read buffer: bytes in {@code buf[pos, end)} are read from the stream and unconsumed. */
    private static final class Window {
        final InputStream in;
        final byte[] buf = new byte[128 * 1024];
        int pos;
        int end;
        boolean eof;

        Window(InputStream in) { this.in = in; }

        /** Read at least one more byte past what is buffered; false when the stream has ended. */
        boolean more() throws IOException {
            int have = end - pos;
            return ensure(have + 1) > have;
        }

        /** Buffer at least {@code n} unconsumed bytes if the stream has them; returns how many are buffered. */
        int ensure(int n) throws IOException {
            if (n > buf.length) n = buf.length;
            while (end - pos < n && !eof) {
                if (pos > 0 && (end == buf.length || buf.length - end < n - (end - pos))) {
                    System.arraycopy(buf, pos, buf, 0, end - pos);
                    end -= pos;
                    pos = 0;
                }
                int read = in.read(buf, end, buf.length - end);
                if (read < 0) eof = true;
                else end += read;
            }
            return end - pos;
        }
    }

    private static String boundaryOf(String contentType) {
        if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith("multipart/form-data"))
            return "";
        return parameter(contentType, "boundary");
    }

    private static Map<String, String> headers(String raw) {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        for (String line : raw.split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            out.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT),
                    line.substring(colon + 1).trim());
        }
        return out;
    }

    private static String parameter(String header, String wanted) {
        if (header == null) return "";
        String[] pieces = header.split(";");
        for (int i = 1; i < pieces.length; i++) {
            String piece = pieces[i].trim();
            int eq = piece.indexOf('=');
            if (eq <= 0 || !wanted.equalsIgnoreCase(piece.substring(0, eq).trim())) continue;
            String result = piece.substring(eq + 1).trim();
            if (result.length() >= 2 && result.charAt(0) == '"'
                    && result.charAt(result.length() - 1) == '"') {
                result = result.substring(1, result.length() - 1)
                        .replace("\\\"", "\"").replace("\\\\", "\\");
            }
            return result;
        }
        return "";
    }

    private static String value(String value) { return value == null ? "" : value.trim(); }

    private static int indexOf(byte[] source, int from, int to, byte[] wanted) {
        if (wanted.length == 0) return Math.max(0, from);
        byte first = wanted[0];
        int last = to - wanted.length;
        outer: for (int i = Math.max(0, from); i <= last; i++) {
            if (source[i] != first) continue;
            for (int j = 1; j < wanted.length; j++) {
                if (source[i + j] != wanted[j]) continue outer;
            }
            return i;
        }
        return -1;
    }
}
