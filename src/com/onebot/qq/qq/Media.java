package com.onebot.qq.qq;

import android.graphics.BitmapFactory;
import com.onebot.qq.L;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Base64;

/** Media handling: resolve a OneBot `file` spec to a local file, and build a QQ NT PicElement
 *  (QQ uploads the picture automatically on sendMsg once it is copied into the rich-media path). */
public final class Media {
    static final String QQNT_UTIL = "com.tencent.qqnt.kernel.nativeinterface.QQNTWrapperUtil$CppProxy";
    static final String RM_PATH_INFO = "com.tencent.qqnt.kernel.nativeinterface.RichMediaFilePathInfo";
    static final String PIC_ELEMENT = "com.tencent.qqnt.kernel.nativeinterface.PicElement";

    private static final String TMP_DIR = "/sdcard/Android/data/com.tencent.mobileqq/files/onebot-tmp";

    /** Resolve file / url (path, file://, http(s)://, base64://, or raw base64) to a local file. */
    public static File resolve(String file, String url) {
        try {
            File dir = new File(TMP_DIR);
            dir.mkdirs();
            if (file != null && !file.isEmpty()) {
                if (file.startsWith("base64://")) return writeTmp(dir, Base64.getDecoder().decode(file.substring(9)));
                if (file.startsWith("file://")) { File f = new File(file.substring(7)); if (f.isFile()) return f; }
                if (file.startsWith("http://") || file.startsWith("https://")) return download(dir, file);
                File direct = new File(file);
                if (direct.isFile()) return direct;
                // maybe a bare base64 blob
                if (file.length() > 256 && file.matches("[A-Za-z0-9+/=\\r\\n]+")) {
                    try { return writeTmp(dir, Base64.getDecoder().decode(file.replaceAll("\\s", ""))); } catch (Throwable ignore) {}
                }
            }
            if (url != null && !url.isEmpty()) {
                if (url.startsWith("http")) return download(dir, url);
                File f = new File(url.startsWith("file://") ? url.substring(7) : url);
                if (f.isFile()) return f;
            }
        } catch (Throwable t) {
            L.e("Media.resolve", t);
        }
        return null;
    }

    private static File writeTmp(File dir, byte[] data) throws Exception {
        File f = File.createTempFile("onebot", ".dat", dir);
        try (FileOutputStream o = new FileOutputStream(f)) { o.write(data); }
        return f;
    }

    private static File download(File dir, String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000); c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", "QQ/9.3.50");
        c.connect();
        File f = File.createTempFile("onebot", ".dat", dir);
        try (InputStream in = c.getInputStream(); FileOutputStream o = new FileOutputStream(f)) {
            byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
        }
        c.disconnect();
        return f;
    }

    /** Build a KELEMTYPEPIC MsgElement for the given local file. Returns null on failure. */
    public static Object buildPicElement(Ref ref, Object msgService, File file) {
        try {
            String path = file.getAbsolutePath();
            String md5 = Ref.asStr(ref.callS(QQNT_UTIL, "genFileMd5Hex", path));
            String ext = extOf(file.getName());
            String fileName = md5 + "." + ext;

            Class<?>[] types = new Class[]{int.class, int.class, String.class, String.class,
                    int.class, int.class, byte[].class, String.class, boolean.class};
            Object infoOrig = ref.neuTyped(RM_PATH_INFO, types,
                    new Object[]{2, 0, md5, fileName, 1, 0, null, "", true});
            String origPath = Ref.asStr(ref.call(msgService, "getRichMediaFilePathForMobileQQSend", infoOrig));

            boolean exist = (Boolean) ref.callS(QQNT_UTIL, "fileIsExist", origPath);
            long osize = Ref.asLong(ref.callS(QQNT_UTIL, "getFileSize", origPath));
            if (!exist || osize != file.length()) {
                Object infoThumb = ref.neuTyped(RM_PATH_INFO, types,
                        new Object[]{2, 0, md5, fileName, 2, 720, null, "", true});
                String thumbPath = Ref.asStr(ref.call(msgService, "getRichMediaFilePathForMobileQQSend", infoThumb));
                ref.callS(QQNT_UTIL, "copyFile", path, origPath);
                ref.callS(QQNT_UTIL, "copyFile", path, thumbPath);
            }

            int[] wh = bounds(path);
            Object pic = ref.neu(PIC_ELEMENT);
            ref.set(pic, "md5HexStr", md5);
            ref.set(pic, "sourcePath", path);
            ref.set(pic, "fileSize", file.length());
            ref.set(pic, "picWidth", wh[0]);
            ref.set(pic, "picHeight", wh[1]);
            ref.set(pic, "fileName", fileName);
            ref.set(pic, "picType", Integer.valueOf(picType(ext)));
            ref.set(pic, "picSubType", 0);
            ref.set(pic, "original", true);

            Object elem = ref.neu(QQClient.MSG_ELEMENT);
            ref.set(elem, "elementType", 2);
            ref.set(elem, "picElement", pic);
            return elem;
        } catch (Throwable t) {
            L.e("buildPicElement", t);
            return null;
        }
    }

    private static int[] bounds(String path) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, o);
            int w = o.outWidth > 0 ? o.outWidth : 100;
            int h = o.outHeight > 0 ? o.outHeight : 100;
            return new int[]{w, h};
        } catch (Throwable t) { return new int[]{100, 100}; }
    }

    private static String extOf(String name) {
        int i = name.lastIndexOf('.');
        String e = i >= 0 ? name.substring(i + 1).toLowerCase() : "jpg";
        if (e.isEmpty() || e.length() > 5) e = "jpg";
        return e;
    }
    private static int picType(String ext) {
        switch (ext) {
            case "png": return 1001;
            case "gif": return 2000;
            case "webp": return 1002;
            default: return 1000; // jpg
        }
    }
}
