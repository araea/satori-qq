import com.satori.qq.qq.Media;

public final class MediaTest {
    public static void main(String[] args) {
        png();
        jpeg();
        gif();
        webpNotWav();
        wav();
        System.out.println("MediaTest OK");
    }

    private static void png() {
        byte[] d = new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a};
        eq(".png", Media.guessExt(d), "png magic");
    }

    private static void jpeg() {
        byte[] d = new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xe0};
        eq(".jpg", Media.guessExt(d), "jpeg magic");
    }

    private static void gif() {
        byte[] d = "GIF89a........".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        eq(".gif", Media.guessExt(d), "gif magic");
    }

    private static void webpNotWav() {
        byte[] d = new byte[12];
        d[0] = 'R'; d[1] = 'I'; d[2] = 'F'; d[3] = 'F';
        d[8] = 'W'; d[9] = 'E'; d[10] = 'B'; d[11] = 'P';
        eq(".webp", Media.guessExt(d), "webp is not wav");
    }

    private static void wav() {
        byte[] d = new byte[12];
        d[0] = 'R'; d[1] = 'I'; d[2] = 'F'; d[3] = 'F';
        d[8] = 'W'; d[9] = 'A'; d[10] = 'V'; d[11] = 'E';
        eq(".wav", Media.guessExt(d), "wav magic");
    }

    private static void eq(String expect, String got, String name) {
        if (!expect.equals(got)) throw new AssertionError(name + ": expect " + expect + " got " + got);
    }
}
