import com.satori.qq.satori.Multipart;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

public final class MultipartTest {
    private static final String BOUNDARY = "satori-test-boundary";
    private static final String TYPE = "multipart/form-data; boundary=\"" + BOUNDARY + "\"";

    public static void main(String[] args) throws Exception {
        File dir = Files.createTempDirectory("multipart-test").toFile();
        parsesBinaryParts(dir);
        survivesAnyReadSize(dir);
        stopsAtTheClosingDelimiter(dir);
        rejectsMissingBoundary(dir);
        rejectsUnterminatedAndTooLarge(dir);
        String[] left = dir.list();
        if (left == null || left.length != 0) throw new AssertionError("failed parses left files behind: " + Arrays.toString(left));
        System.out.println("MultipartTest OK");
    }

    private static byte[] body(byte[]... datas) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < datas.length; i++) {
            out.write(("--" + BOUNDARY + "\r\n"
                    + "Content-Disposition: form-data; name=\"f" + i + "\"; filename=\"x" + i + ".bin\"\r\n"
                    + "Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            out.write(datas[i]);
            out.write("\r\n".getBytes(StandardCharsets.ISO_8859_1));
        }
        out.write(("--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.ISO_8859_1));
        return out.toByteArray();
    }

    private static void parsesBinaryParts(File dir) throws Exception {
        // CR LF and boundary-lookalike bytes inside the data must not end the part early.
        byte[] tricky = ("\u0000\u0001\u0002\r\n\u0003\r\n--" + BOUNDARY.substring(0, 8) + "\r\n--").getBytes(StandardCharsets.ISO_8859_1);
        List<Multipart.Part> parts = Multipart.parseStream(new ByteArrayInputStream(body(tricky, new byte[0])), TYPE, dir, 1 << 20);
        eq(2, parts.size(), "part count");
        Multipart.Part part = parts.get(0);
        eq("f0", part.name, "name");
        eq("x0.bin", part.filename, "filename");
        eq("application/octet-stream", part.contentType, "content type");
        eq(tricky.length, part.size, "size");
        eq(true, Arrays.equals(tricky, Files.readAllBytes(part.file.toPath())), "bytes round-trip");
        eq(0L, parts.get(1).size, "empty part");
        for (Multipart.Part p : parts) p.file.delete();
    }

    /** The delimiter can be split across reads at any offset, and a part can be bigger than the window. */
    private static void survivesAnyReadSize(File dir) throws Exception {
        byte[] big = new byte[300_000];
        new Random(7).nextBytes(big);
        byte[] raw = body(big, "tail".getBytes(StandardCharsets.ISO_8859_1));
        for (int chunk : new int[]{1, 2, 7, 4096, 70_001}) {
            List<Multipart.Part> parts = Multipart.parseStream(new Trickle(raw, chunk), TYPE, dir, 1 << 20);
            eq(2, parts.size(), "parts at read size " + chunk);
            eq(true, Arrays.equals(big, Files.readAllBytes(parts.get(0).file.toPath())), "big part at read size " + chunk);
            eq("tail", new String(Files.readAllBytes(parts.get(1).file.toPath()), StandardCharsets.ISO_8859_1), "tail at read size " + chunk);
            for (Multipart.Part p : parts) p.file.delete();
        }
    }

    /** Whatever follows the closing delimiter is not consumed: the server drains it itself. */
    private static void stopsAtTheClosingDelimiter(File dir) throws Exception {
        byte[] raw = body("a".getBytes(StandardCharsets.ISO_8859_1));
        ByteArrayOutputStream withEpilogue = new ByteArrayOutputStream();
        withEpilogue.write(raw);
        withEpilogue.write("epilogue".getBytes(StandardCharsets.ISO_8859_1));
        List<Multipart.Part> parts = Multipart.parseStream(new ByteArrayInputStream(withEpilogue.toByteArray()), TYPE, dir, 1 << 20);
        eq(1, parts.size(), "epilogue ignored");
        parts.get(0).file.delete();
    }

    private static void rejectsMissingBoundary(File dir) throws Exception {
        expect(IllegalArgumentException.class, () -> Multipart.parseStream(new ByteArrayInputStream(new byte[0]),
                "multipart/form-data", dir, 10), "missing boundary accepted");
    }

    private static void rejectsUnterminatedAndTooLarge(File dir) throws Exception {
        byte[] raw = body(new byte[1000]);
        byte[] cut = Arrays.copyOf(raw, raw.length - 40);
        expect(IllegalArgumentException.class, () -> Multipart.parseStream(new ByteArrayInputStream(cut), TYPE, dir, 1 << 20),
                "unterminated part accepted");
        expect(Multipart.TooLarge.class, () -> Multipart.parseStream(new ByteArrayInputStream(raw), TYPE, dir, 999),
                "oversized part accepted");
    }

    private interface Call { void run() throws Exception; }

    private static void expect(Class<? extends Throwable> type, Call call, String label) throws Exception {
        try { call.run(); }
        catch (Throwable t) { if (type.isInstance(t)) return; throw new AssertionError(label + ": got " + t); }
        throw new AssertionError(label);
    }

    /** An input stream that hands out at most {@code chunk} bytes per read. */
    private static final class Trickle extends InputStream {
        private final byte[] data;
        private final int chunk;
        private int at;
        Trickle(byte[] data, int chunk) { this.data = data; this.chunk = chunk; }
        @Override public int read() { return at < data.length ? data[at++] & 0xff : -1; }
        @Override public int read(byte[] b, int off, int len) {
            if (at >= data.length) return -1;
            int n = Math.min(Math.min(len, chunk), data.length - at);
            System.arraycopy(data, at, b, off, n);
            at += n;
            return n;
        }
    }

    private static void eq(Object expected, Object actual, String label) {
        if (expected == null ? actual != null : !String.valueOf(expected).equals(String.valueOf(actual)))
            throw new AssertionError(label + ": expected " + expected + " got " + actual);
    }
}
