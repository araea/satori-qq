import com.satori.qq.satori.Multipart;

import java.nio.charset.StandardCharsets;
import java.util.List;

public final class MultipartTest {
    public static void main(String[] args) {
        parsesBinaryParts();
        rejectsMissingBoundary();
        System.out.println("MultipartTest OK");
    }

    private static void parsesBinaryParts() {
        String boundary = "satori-test-boundary";
        byte[] binary = new byte[]{0, 1, 2, 13, 10, 3, (byte) 255};
        byte[] head = ("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"image\"; filename=\"x.png\"\r\n"
                + "Content-Type: image/png\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1);
        byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.ISO_8859_1);
        byte[] body = new byte[head.length + binary.length + tail.length];
        System.arraycopy(head, 0, body, 0, head.length);
        System.arraycopy(binary, 0, body, head.length, binary.length);
        System.arraycopy(tail, 0, body, head.length + binary.length, tail.length);
        List<Multipart.Part> parts = Multipart.parse(body,
                "multipart/form-data; boundary=\"" + boundary + "\"");
        eq(1, parts.size(), "part count");
        Multipart.Part part = parts.get(0);
        eq("image", part.name, "name");
        eq("x.png", part.filename, "filename");
        eq("image/png", part.contentType, "content type");
        eq(binary.length, part.data.length, "binary size");
        for (int i = 0; i < binary.length; i++) eq(binary[i], part.data[i], "binary byte " + i);
    }

    private static void rejectsMissingBoundary() {
        boolean failed = false;
        try { Multipart.parse(new byte[0], "multipart/form-data"); }
        catch (IllegalArgumentException expected) { failed = true; }
        if (!failed) throw new AssertionError("missing boundary accepted");
    }

    private static void eq(Object expected, Object actual, String label) {
        if (expected == null ? actual != null : !String.valueOf(expected).equals(String.valueOf(actual)))
            throw new AssertionError(label + ": expected " + expected + " got " + actual);
    }
}
