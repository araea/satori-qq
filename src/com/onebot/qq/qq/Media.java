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
    static final String PTT_ELEMENT = "com.tencent.qqnt.kernel.nativeinterface.PttElement";
    static final String FILE_ELEMENT = "com.tencent.qqnt.kernel.nativeinterface.FileElement";
    static final String VIDEO_ELEMENT = "com.tencent.qqnt.kernel.nativeinterface.VideoElement";

    // QQNT MsgConstant.KELEMTYPE*: PIC=2, FILE=3, PTT=4, VIDEO=5. RichMediaFilePathInfo's first
    // ctor arg is the element type (image passes 2), so ptt/file just swap it.

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

    /** RichMediaFilePathInfo(elementType, elementSubType, md5, fileName, downloadType, thumbSize=0, null, "", true). */
    private static String richMediaDest(Ref ref, Object msgService, int elementType, int subType,
                                        String md5, String fileName, int downloadType) {
        Class<?>[] types = new Class[]{int.class, int.class, String.class, String.class,
                int.class, int.class, byte[].class, String.class, boolean.class};
        Object info = ref.neuTyped(RM_PATH_INFO, types,
                new Object[]{elementType, subType, md5, fileName, downloadType, 0, null, "", true});
        return Ref.asStr(ref.call(msgService, "getRichMediaFilePathForMobileQQSend", info));
    }

    private static String richMediaDest(Ref ref, Object msgService, int elementType, String md5, String fileName) {
        return richMediaDest(ref, msgService, elementType, 0, md5, fileName, 1);
    }

    private static void copyToDest(Ref ref, String srcPath, String destPath, long size) {
        try {
            boolean exist = (Boolean) ref.callS(QQNT_UTIL, "fileIsExist", destPath);
            long dsize = Ref.asLong(ref.callS(QQNT_UTIL, "getFileSize", destPath));
            if (!exist || dsize != size) ref.callS(QQNT_UTIL, "copyFile", srcPath, destPath);
        } catch (Throwable t) {
            ref.callS(QQNT_UTIL, "copyFile", srcPath, destPath);
        }
    }

    /** Build a KELEMTYPEPTT(4) voice element. Input must already be SILK or AMR (QQ's voice codecs);
     *  we do not transcode. Returns null on failure. */
    public static Object buildPttElement(Ref ref, Object msgService, File file) {
        try {
            String path = file.getAbsolutePath();
            String md5 = Ref.asStr(ref.callS(QQNT_UTIL, "genFileMd5Hex", path));
            int[] fmt = pttFormat(path);            // [formatType, durationSec]
            String fileName = md5 + (fmt[0] == 0 ? ".amr" : ".silk");
            String dest = richMediaDest(ref, msgService, 4, md5, fileName);
            copyToDest(ref, path, dest, file.length());

            Object ptt = ref.neu(PTT_ELEMENT);
            ref.set(ptt, "md5HexStr", md5);
            ref.set(ptt, "filePath", dest);
            ref.set(ptt, "fileName", fileName);
            ref.set(ptt, "fileSize", file.length());
            ref.set(ptt, "duration", fmt[1]);
            ref.set(ptt, "formatType", fmt[0]);     // 1=silk, 0=amr
            ref.set(ptt, "voiceType", 1);
            ref.set(ptt, "voiceChangeType", 0);
            ref.set(ptt, "canConvert2Text", true);
            ref.set(ptt, "fileUuid", "");
            ref.set(ptt, "fileSubId", "");
            ref.set(ptt, "text", "");

            Object elem = ref.neu(QQClient.MSG_ELEMENT);
            ref.set(elem, "elementType", 4);
            ref.set(elem, "pttElement", ptt);
            return elem;
        } catch (Throwable t) {
            L.e("buildPttElement", t);
            return null;
        }
    }

    /** Build a KELEMTYPEFILE(3) file element (QQ uploads on sendMsg). Returns null on failure. */
    public static Object buildFileElement(Ref ref, Object msgService, File file, String displayName) {
        try {
            String path = file.getAbsolutePath();
            String md5 = Ref.asStr(ref.callS(QQNT_UTIL, "genFileMd5Hex", path));
            String fileName = (displayName == null || displayName.isEmpty()) ? file.getName() : displayName;
            String dest = richMediaDest(ref, msgService, 3, md5, fileName);
            copyToDest(ref, path, dest, file.length());

            Object fe = ref.neu(FILE_ELEMENT);
            ref.set(fe, "fileMd5", md5);
            ref.set(fe, "filePath", dest);
            ref.set(fe, "fileName", fileName);
            ref.set(fe, "fileSize", file.length());
            ref.set(fe, "fileUuid", "");
            ref.set(fe, "fileSubId", "");

            Object elem = ref.neu(QQClient.MSG_ELEMENT);
            ref.set(elem, "elementType", 3);
            ref.set(elem, "fileElement", fe);
            return elem;
        } catch (Throwable t) {
            L.e("buildFileElement", t);
            return null;
        }
    }

    /** Build a KELEMTYPEVIDEO(5) element (thumbnail extracted locally; QQ uploads on sendMsg).
     *  Recipe per OpenShamrock/QQ NT: orig path (elemType 5, subType 2, dl 1) + thumb path
     *  (elemType 5, subType 1, dl 2). Returns null on failure. */
    public static Object buildVideoElement(Ref ref, Object msgService, File file) {
        android.media.MediaMetadataRetriever mmr = null;
        try {
            String path = file.getAbsolutePath();
            String md5 = Ref.asStr(ref.callS(QQNT_UTIL, "genFileMd5Hex", path));
            String fileName = md5 + ".mp4";
            String origPath = richMediaDest(ref, msgService, 5, 2, md5, fileName, 1);
            String thumbPath = richMediaDest(ref, msgService, 5, 1, md5, fileName, 2);

            mmr = new android.media.MediaMetadataRetriever();
            mmr.setDataSource(path);
            int durSec = (int) Math.max(1, parseLong(
                    mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)) / 1000);
            int vw = (int) parseLong(mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            int vh = (int) parseLong(mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            android.graphics.Bitmap frame = mmr.getFrameAtTime(0);

            boolean exist = (Boolean) ref.callS(QQNT_UTIL, "fileIsExist", origPath);
            long osize = Ref.asLong(ref.callS(QQNT_UTIL, "getFileSize", origPath));
            if (!exist || osize != file.length()) {
                ref.callS(QQNT_UTIL, "copyFile", path, origPath);
                saveJpeg(frame, thumbPath);
            }
            String thumbMd5 = Ref.asStr(ref.callS(QQNT_UTIL, "genFileMd5Hex", thumbPath));
            int thumbSize = (int) Ref.asLong(ref.callS(QQNT_UTIL, "getFileSize", thumbPath));
            int tw = frame != null ? frame.getWidth() : vw;
            int th = frame != null ? frame.getHeight() : vh;
            if (frame != null) frame.recycle();

            Object v = ref.neu(VIDEO_ELEMENT);
            ref.set(v, "videoMd5", md5);
            ref.set(v, "fileName", fileName);
            ref.set(v, "filePath", origPath);
            ref.set(v, "fileSize", file.length());  // long in QQ 9.3.50
            ref.set(v, "fileTime", durSec);
            ref.set(v, "fileFormat", 2);            // NTVideoType mp4
            ref.set(v, "thumbMd5", thumbMd5);
            ref.set(v, "thumbSize", thumbSize);
            ref.set(v, "thumbWidth", tw);
            ref.set(v, "thumbHeight", th);
            java.util.HashMap<Integer, String> tp = new java.util.HashMap<>();
            tp.put(0, thumbPath);
            ref.set(v, "thumbPath", tp);
            ref.set(v, "fileUuid", "");

            Object elem = ref.neu(QQClient.MSG_ELEMENT);
            ref.set(elem, "elementType", 5);
            ref.set(elem, "videoElement", v);
            return elem;
        } catch (Throwable t) {
            L.e("buildVideoElement", t);
            return null;
        } finally {
            if (mmr != null) try { mmr.release(); } catch (Throwable ignore) {}
        }
    }

    private static void saveJpeg(android.graphics.Bitmap bmp, String destPath) {
        if (bmp == null) return;
        try (FileOutputStream o = new FileOutputStream(destPath)) {
            bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, o);
        } catch (Throwable t) {
            L.e("saveJpeg", t);
        }
    }

    private static long parseLong(String s) {
        try { return s == null ? 0 : Long.parseLong(s.trim()); } catch (Throwable t) { return 0; }
    }

    /** Detect voice format + estimate duration (seconds). [0]=formatType(1 silk / 0 amr), [1]=durationSec. */
    private static int[] pttFormat(String path) {
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(path, "r")) {
            long len = raf.length();
            byte[] head = new byte[Math.min(16, (int) len)];
            raf.readFully(head);
            String h = new String(head, "ISO-8859-1");
            if (h.contains("#!AMR")) {
                // AMR-NB ~ rough estimate; frames are 20ms, average ~13 bytes/frame.
                int dur = (int) Math.max(1, Math.round((len - 6) / 1600.0));
                return new int[]{0, dur};
            }
            if (h.contains("#!SILK")) {
                return new int[]{1, silkDurationSec(raf, h.indexOf("#!SILK") == 1 ? 10 : 9)};
            }
            // Unknown container: assume silk-ish, best-effort duration from size.
            return new int[]{1, (int) Math.max(1, Math.round(len / 2000.0))};
        } catch (Throwable t) {
            return new int[]{1, 1};
        }
    }

    /** SILK v3: after the header, frames are [int16-LE blockLen][payload], 20ms each. */
    private static int silkDurationSec(java.io.RandomAccessFile raf, int headerLen) {
        try {
            long len = raf.length();
            raf.seek(headerLen);
            int frames = 0;
            long pos = headerLen;
            while (pos + 2 <= len) {
                int lo = raf.read(), hi = raf.read();
                if (lo < 0 || hi < 0) break;
                int blk = lo | (hi << 8);
                if (blk <= 0 || blk == 0xFFFF) break;
                pos += 2 + blk;
                if (pos > len) break;
                raf.seek(pos);
                frames++;
            }
            return Math.max(1, (int) Math.round(frames * 20 / 1000.0));
        } catch (Throwable t) {
            return 1;
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
