package com.satori.qq.qq;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import com.satori.qq.L;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.util.Base64;
import java.util.Locale;

/** Media handling: resolve a file spec to a local file, and build a QQ NT PicElement
 *  (QQ uploads the picture automatically on sendMsg once it is copied into the rich-media path). */
public final class Media {
    static final String QQNT_UTIL = "com.tencent.qqnt.kernel.nativeinterface.QQNTWrapperUtil$CppProxy";
    static final String RM_PATH_INFO = "com.tencent.qqnt.kernel.nativeinterface.RichMediaFilePathInfo";
    static final String PIC_ELEMENT = "com.tencent.qqnt.kernel.nativeinterface.PicElement";
    static final String PTT_ELEMENT = "com.tencent.qqnt.kernel.nativeinterface.PttElement";
    static final String FILE_ELEMENT = "com.tencent.qqnt.kernel.nativeinterface.FileElement";
    static final String VIDEO_ELEMENT = "com.tencent.qqnt.kernel.nativeinterface.VideoElement";
    private static final String QROUTE = "com.tencent.mobileqq.qroute.QRoute";
    private static final String MSG_UTIL_API = "com.tencent.qqnt.msg.api.IMsgUtilApi";
    private static final String PIC_COMPRESS_API = "com.tencent.qqnt.compress.api.IPicCompressApi";

    // QQNT MsgConstant.KELEMTYPE*: PIC=2, FILE=3, PTT=4, VIDEO=5. RichMediaFilePathInfo's first
    // ctor arg is the element type (image passes 2), so ptt/file just swap it.

    private static final String FALLBACK_TMP_DIR =
            "/sdcard/Android/data/com.tencent.mobileqq/cache/nt-media-tmp";

    /** Resolve through the host application's cache directory instead of a module-named path. */
    private static File tempDir() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getDeclaredMethod("currentApplication").invoke(null);
            if (app instanceof android.content.Context) {
                File dir = new File(((android.content.Context) app).getCacheDir(), "nt-media-tmp");
                if (dir.isDirectory() || dir.mkdirs()) return dir;
            }
        } catch (Throwable t) {
            L.e("resolve media cache", t);
        }
        File dir = new File(FALLBACK_TMP_DIR);
        dir.mkdirs();
        return dir;
    }

    /** Remove stale module-owned temporary files, never arbitrary QQ cache. */
    public static int cleanTemp() {
        int deleted = 0;
        File dir = tempDir();
        File[] files = dir.listFiles();
        if (files == null) return 0;
        long cutoff = System.currentTimeMillis() - 60L * 60L * 1000L;
        for (File file : files) {
            String name = file.getName();
            boolean owned = name.startsWith("ntm") || name.startsWith("satori") || name.startsWith("obpcm")
                    || name.startsWith("obamr") || name.startsWith("obsilk") || name.startsWith("obget")
                    || name.startsWith("obwav") || name.startsWith("obm4a");
            if (owned && file.isFile() && file.lastModified() < cutoff && file.delete()) deleted++;
        }
        return deleted;
    }

    /** Pass through Tencent SILK; everything else (wav/mp3/AMR) is transcoded with QQ's own silk encoder.
     *  MediaCodec AMR-NB is accepted by sendMsg but the NT client cannot play it (21:34 测试群). */
    public static File prepareVoice(Ref ref, File file) {
        if (file == null || !file.isFile()) return null;
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file, "r")) {
            byte[] head = new byte[(int) Math.min(16, raf.length())];
            raf.readFully(head);
            String value = new String(head, "ISO-8859-1");
            if (value.contains("#!SILK")) return file;
        } catch (Throwable t) {
            L.e("prepareVoice header", t);
        }
        File dir = tempDir();
        File silk = AudioTranscoder.toSilk(ref, file, dir);
        if (silk != null) return silk;
        L.w("silk encode failed, not sending unplayable AMR");
        return null;
    }

    /** Convert a local record file to get_record.out_format. */
    public static File convertRecord(Ref ref, File file, String format) {
        File dir = tempDir();
        return AudioTranscoder.convert(ref, file, format, dir);
    }

    /** Persist one Satori multipart upload in the module-owned, periodically cleaned cache. */
    public static File storeUpload(byte[] data, String filename, String contentType) throws Exception {
        if (data == null) data = new byte[0];
        String suffix = safeSuffix(filename);
        if (suffix.isEmpty()) suffix = extFromMime(contentType);
        if (suffix.isEmpty() || ".dat".equals(suffix)) suffix = guessExt(data);
        // Keep the on-disk name neutral: the anti-exposure path filter intentionally hides
        // paths containing the module name, including from this process's native open().
        File out = File.createTempFile("ntm", suffix, tempDir());
        try (FileOutputStream stream = new FileOutputStream(out)) { stream.write(data); }
        return out;
    }

    private static String safeSuffix(String filename) {
        if (filename == null) return "";
        String name = filename.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) name = name.substring(slash + 1);
        int dot = name.lastIndexOf('.');
        if (dot < 0 || name.length() - dot > 12) return "";
        String ext = name.substring(dot).toLowerCase(Locale.ROOT);
        return ext.matches("\\.[a-z0-9]{1,10}") ? ext : "";
    }

    /** Resolve file / url (path, file://, data:, http(s)://, base64://, or raw base64) to a local file. */
    public static File resolve(String file, String url) {
        try {
            File dir = tempDir();
            File got = fromSpec(dir, file);
            if (got != null) return got;
            return fromSpec(dir, url);
        } catch (Throwable t) {
            L.e("Media.resolve", t);
        }
        return null;
    }

    private static File fromSpec(File dir, String spec) throws Exception {
        if (spec == null) return null;
        spec = spec.trim();
        if (spec.isEmpty()) return null;
        if (spec.startsWith("data:")) return fromDataUri(dir, spec);
        if (spec.startsWith("base64://"))
            return writeTmp(dir, Base64.getDecoder().decode(spec.substring(9).replaceAll("\\s", "")));
        if (spec.startsWith("file:")) {
            File f = fromFileUri(spec);
            if (f != null) return f;
        }
        if (spec.startsWith("http://") || spec.startsWith("https://")) return download(dir, spec);
        File direct = new File(spec);
        if (direct.isFile()) return direct;
        if (spec.length() > 256 && spec.matches("[A-Za-z0-9+/=\\r\\n]+")) {
            try { return writeTmp(dir, Base64.getDecoder().decode(spec.replaceAll("\\s", ""))); }
            catch (Throwable ignore) {}
        }
        return null;
    }

    private static File fromFileUri(String spec) {
        String path = spec;
        if (path.startsWith("file://")) path = path.substring(7);
        else if (path.startsWith("file:")) path = path.substring(5);
        if (path.startsWith("localhost/")) path = path.substring("localhost".length());
        try { path = URLDecoder.decode(path, "UTF-8"); } catch (Exception ignore) {}
        File f = new File(path);
        return f.isFile() ? f : null;
    }

    private static File fromDataUri(File dir, String spec) throws Exception {
        int comma = spec.indexOf(',');
        if (comma < 5) return null;
        String meta = spec.substring(5, comma);
        String payload = spec.substring(comma + 1);
        byte[] data;
        if (meta.toLowerCase(Locale.ROOT).contains(";base64")) {
            data = Base64.getDecoder().decode(payload.replaceAll("\\s", ""));
        } else {
            data = URLDecoder.decode(payload, "UTF-8").getBytes("UTF-8");
        }
        String ext = extFromMime(meta);
        if (".dat".equals(ext)) ext = guessExt(data);
        File f = File.createTempFile("ntm", ext, dir);
        try (FileOutputStream o = new FileOutputStream(f)) { o.write(data); }
        return f;
    }

    private static String extFromMime(String meta) {
        String m = meta == null ? "" : meta.toLowerCase(Locale.ROOT);
        if (m.contains("png")) return ".png";
        if (m.contains("jpeg") || m.contains("jpg")) return ".jpg";
        if (m.contains("gif")) return ".gif";
        if (m.contains("webp")) return ".webp";
        if (m.contains("bmp")) return ".bmp";
        return ".dat";
    }

    private static File writeTmp(File dir, byte[] data) throws Exception {
        File f = File.createTempFile("ntm", guessExt(data), dir);
        try (FileOutputStream o = new FileOutputStream(f)) { o.write(data); }
        return f;
    }

    /** Magic-byte extension. WebP is RIFF/WEBP; WAV is RIFF/WAVE — do not treat all RIFF as wav. */
    public static String guessExt(byte[] data) {
        if (data == null || data.length < 4) return ".dat";
        int b0 = data[0] & 0xff, b1 = data[1] & 0xff, b2 = data[2] & 0xff, b3 = data[3] & 0xff;
        if (b0 == 0x89 && b1 == 'P' && b2 == 'N' && b3 == 'G') return ".png";
        if (b0 == 0xff && b1 == 0xd8) return ".jpg";
        if (b0 == 'G' && b1 == 'I' && b2 == 'F') return ".gif";
        if (data.length >= 12 && b0 == 'R' && b1 == 'I' && b2 == 'F' && b3 == 'F') {
            if (data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P') return ".webp";
            if (data[8] == 'W' && data[9] == 'A' && data[10] == 'V' && data[11] == 'E') return ".wav";
        }
        String head = new String(data, 0, Math.min(16, data.length), java.nio.charset.StandardCharsets.ISO_8859_1);
        if (head.contains("#!SILK")) return ".silk";
        if (head.contains("#!AMR")) return ".amr";
        if (data.length >= 3 && data[0] == 'I' && data[1] == 'D' && data[2] == '3') return ".mp3";
        if (data.length >= 2 && (data[0] & 0xff) == 0xff && (data[1] & 0xe0) == 0xe0) return ".mp3";
        if (data.length >= 8 && data[4] == 'f' && data[5] == 't' && data[6] == 'y' && data[7] == 'p') return ".mp4";
        return ".dat";
    }

    private static File download(File dir, String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);
        boolean local = url.startsWith("http://127.0.0.1") || url.startsWith("http://localhost");
        c.setRequestProperty("User-Agent", local
                ? "satori-qq"
                : "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36");
        c.connect();
        int code = c.getResponseCode();
        if (code >= 400) {
            c.disconnect();
            throw new IllegalStateException("download HTTP " + code + " " + url);
        }
        String contentType = c.getContentType();
        File tmp = File.createTempFile("ntm", ".bin", dir);
        try (InputStream in = c.getInputStream(); FileOutputStream o = new FileOutputStream(tmp)) {
            byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
        }
        c.disconnect();
        String ext = guessExt(headBytes(tmp));
        if (".dat".equals(ext) || ".bin".equals(ext)) {
            if (contentType != null) ext = extFromMime(contentType);
        }
        if (!".bin".equals(ext) && !tmp.getName().endsWith(ext)) {
            File named = File.createTempFile("ntm", ext, dir);
            if (!tmp.renameTo(named)) {
                copyFile(tmp, named);
                tmp.delete();
            }
            return named;
        }
        return tmp;
    }

    private static byte[] headBytes(File f) {
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "r")) {
            byte[] head = new byte[(int) Math.min(16, raf.length())];
            raf.readFully(head);
            return head;
        } catch (Throwable t) {
            return new byte[0];
        }
    }

    private static void copyFile(File src, File dst) throws Exception {
        try (InputStream in = new java.io.FileInputStream(src); FileOutputStream o = new FileOutputStream(dst)) {
            byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
        }
    }

    /**
     * Build a KELEMTYPEPIC MsgElement the way NapCat/LLOneBot do: copy the original into
     * the NT rich-media path, then point {@code sourcePath} at that copy.
     *
     * Do not send QQ 9.3's 8-arg {@code createPicElement} result as-is. That builder
     * leaves {@code sourcePath} on the album/DCIM file, copies the JPEG thumb under the
     * <em>thumb file's</em> MD5, and never sets {@code thumbPath}. AIO then cannot find a
     * preview and shows an empty unopenable bubble. The 3-arg overload is worse: it copies
     * the original into the 720px thumb slot (PNG/WebP there → 该消息类型不支持查看).
     *
     * Thumb slot (downloadType=2, 720, keyed by the <em>original</em> MD5) is always a
     * real JPEG, never the original file.
     */
    public static Object buildPicElement(Ref ref, Object msgService, File file) {
        try {
            File sendable = prepareSendableImage(file);
            Object elem = buildNtPicElement(ref, msgService, sendable);
            if (!validPicElement(ref, elem)) return null;
            return elem;
        } catch (Throwable t) {
            L.e("buildPicElement", t);
            return null;
        }
    }

    /** QQ's own builder so the MsgElement is a real kernel object; we then fix paths. */
    private static Object officialPicElement(Ref ref, String path) {
        try {
            Object api = ref.callS(QROUTE, "api", ref.cls(MSG_UTIL_API));
            if (api == null) return null;
            Class<?> fn = ref.cls("kotlin.jvm.functions.Function1");
            Class<?> biz = ref.clsOrNull("com.tencent.qqnt.msg.data.g");
            Object unit = ref.getStatic("kotlin.Unit", "INSTANCE");
            Object ext = java.lang.reflect.Proxy.newProxyInstance(ref.cl, new Class[]{fn}, (pxy, m, a) -> {
                String n = m.getName();
                if ("invoke".equals(n)) return unit;
                if ("equals".equals(n)) return Boolean.valueOf(pxy == a[0]);
                if ("hashCode".equals(n)) return 0;
                if ("toString".equals(n)) return "satori-pic-ext";
                return null;
            });
            Object elem;
            if (biz != null) {
                elem = ref.callTyped(api, "createPicElement",
                        new Class[]{String.class, boolean.class, int.class, String.class, float.class,
                                biz, boolean.class, fn},
                        path, Boolean.TRUE, Integer.valueOf(0), "", Float.valueOf(0f),
                        null, Boolean.FALSE, ext);
            } else {
                elem = ref.call(api, "createPicElement", path, Boolean.TRUE, Integer.valueOf(0), "",
                        Float.valueOf(0f), null, Boolean.FALSE, ext);
            }
            if (elem != null && Ref.asInt(ref.get(elem, "elementType")) == 2
                    && ref.get(elem, "picElement") != null) {
                return elem;
            }
        } catch (Throwable t) {
            L.e("official createPicElement 8-arg", t);
        }
        return null;
    }

    /** Point an official PicElement at NT orig + JPEG 720 thumb keyed by orig MD5. */
    private static void repairPicIntoNt(Ref ref, Object msgService, Object elem, File file) {
        Object pic = ref.get(elem, "picElement");
        if (pic == null) return;
        String src = Ref.asStr(ref.get(pic, "sourcePath"));
        File copyFrom = (src != null && new File(src).isFile()) ? new File(src) : file;
        String path = copyFrom.getAbsolutePath();
        String md5 = Ref.asStr(ref.get(pic, "md5HexStr"));
        if (md5 == null || md5.isEmpty())
            md5 = Ref.asStr(ref.callS(QQNT_UTIL, "genFileMd5Hex", path));
        String ext = mimeExt(path, extOf(copyFrom.getName()));
        String fileName = md5 + "." + ext;
        String origPath = richMediaDest(ref, msgService, 2, 0, md5, fileName, 1);
        if (origPath == null || origPath.isEmpty() || !copyToDest(ref, path, origPath, copyFrom.length()))
            return;
        File thumbJpeg = jpegThumb(ref, copyFrom, path);
        String thumbPath = "";
        int thumbSize = 0;
        if (thumbJpeg != null && thumbJpeg.isFile() && thumbJpeg.length() > 0) {
            Class<?>[] types = new Class[]{int.class, int.class, String.class, String.class,
                    int.class, int.class, byte[].class, String.class, boolean.class};
            Object infoThumb = ref.neuTyped(RM_PATH_INFO, types,
                    new Object[]{2, 0, md5, md5 + "_720.jpg", 2, 720, null, "", true});
            thumbPath = Ref.asStr(ref.call(msgService, "getRichMediaFilePathForMobileQQSend", infoThumb));
            if (thumbPath != null && !thumbPath.isEmpty()
                    && copyToDest(ref, thumbJpeg.getAbsolutePath(), thumbPath, thumbJpeg.length())) {
                thumbSize = (int) Math.min(Integer.MAX_VALUE, thumbJpeg.length());
            } else thumbPath = "";
        }
        int[] wh = boundsExact(origPath);
        if (wh[0] <= 0 || wh[1] <= 0) wh = boundsExact(path);
        ref.set(pic, "md5HexStr", md5);
        ref.set(pic, "sourcePath", origPath);
        ref.set(pic, "fileSize", new File(origPath).length());
        if (wh[0] > 0) ref.set(pic, "picWidth", Integer.valueOf(wh[0]));
        if (wh[1] > 0) ref.set(pic, "picHeight", Integer.valueOf(wh[1]));
        ref.set(pic, "fileName", fileName);
        if (ref.get(pic, "picType") == null || Ref.asInt(ref.get(pic, "picType")) == 0)
            ref.set(pic, "picType", Integer.valueOf(picType(ext)));
        ref.set(pic, "original", Boolean.TRUE);
        ref.set(pic, "isInApplicationDataPath", Boolean.FALSE);
        ref.set(pic, "isFlashPic", Boolean.FALSE);
        ref.set(pic, "thumbFileSize", Integer.valueOf(thumbSize));
        if (!thumbPath.isEmpty()) {
            java.util.HashMap<Integer, String> thumbs = new java.util.HashMap<>();
            thumbs.put(0, thumbPath);
            thumbs.put(720, thumbPath);
            ref.set(pic, "thumbPath", thumbs);
        }
        L.e("pic repaired " + wh[0] + "x" + wh[1] + " orig=" + new File(origPath).length()
                + " thumb=" + thumbSize + " name=" + fileName, null);
    }

    /**
     * NT copy of original (downloadType=1) plus a real JPEG 720 thumb (downloadType=2).
     * Never copy the original into the thumb slot. Thumb path uses the original MD5 so
     * AIO's lookup matches the 3-arg native convention.
     */
    private static Object buildNtPicElement(Ref ref, Object msgService, File file) {
        String path = file.getAbsolutePath();
        String md5 = Ref.asStr(ref.callS(QQNT_UTIL, "genFileMd5Hex", path));
        if (md5 == null || md5.isEmpty()) return null;
        String ext = mimeExt(path, extOf(file.getName()));
        String fileName = md5 + "." + ext;
        String origPath = richMediaDest(ref, msgService, 2, 0, md5, fileName, 1);
        if (origPath == null || origPath.isEmpty()) return null;
        if (!copyToDest(ref, path, origPath, file.length())) return null;

        File thumbJpeg = jpegThumb(ref, file, path);
        String thumbPath = "";
        int thumbSize = 0;
        if (thumbJpeg != null && thumbJpeg.isFile() && thumbJpeg.length() > 0) {
            Class<?>[] types = new Class[]{int.class, int.class, String.class, String.class,
                    int.class, int.class, byte[].class, String.class, boolean.class};
            Object infoThumb = ref.neuTyped(RM_PATH_INFO, types,
                    new Object[]{2, 0, md5, md5 + "_720.jpg", 2, 720, null, "", true});
            thumbPath = Ref.asStr(ref.call(msgService, "getRichMediaFilePathForMobileQQSend", infoThumb));
            if (thumbPath != null && !thumbPath.isEmpty()
                    && copyToDest(ref, thumbJpeg.getAbsolutePath(), thumbPath, thumbJpeg.length())) {
                thumbSize = (int) Math.min(Integer.MAX_VALUE, thumbJpeg.length());
            } else {
                thumbPath = "";
            }
        }

        int[] wh = boundsExact(origPath);
        if (wh[0] <= 0 || wh[1] <= 0) wh = boundsExact(path);
        if (wh[0] <= 0 || wh[1] <= 0) {
            L.e("pic bounds failed name=" + file.getName(), null);
            return null;
        }
        long origSize = new File(origPath).length();
        Object pic = ref.neu(PIC_ELEMENT);
        ref.set(pic, "md5HexStr", md5);
        ref.set(pic, "sourcePath", origPath);
        ref.set(pic, "fileSize", origSize);
        ref.set(pic, "picWidth", Integer.valueOf(wh[0]));
        ref.set(pic, "picHeight", Integer.valueOf(wh[1]));
        ref.set(pic, "fileName", fileName);
        ref.set(pic, "picType", Integer.valueOf(picType(ext)));
        ref.set(pic, "picSubType", Integer.valueOf(0));
        ref.set(pic, "original", Boolean.TRUE);
        ref.set(pic, "fileUuid", "");
        ref.set(pic, "fileSubId", "");
        ref.set(pic, "thumbFileSize", Integer.valueOf(thumbSize));
        ref.set(pic, "summary", "");
        ref.set(pic, "isInApplicationDataPath", Boolean.FALSE);
        ref.set(pic, "isFlashPic", Boolean.FALSE);
        if (thumbPath != null && !thumbPath.isEmpty()) {
            java.util.HashMap<Integer, String> thumbs = new java.util.HashMap<>();
            thumbs.put(0, thumbPath);
            thumbs.put(720, thumbPath);
            ref.set(pic, "thumbPath", thumbs);
        }

        Object elem = ref.neu(QQClient.MSG_ELEMENT);
        ref.set(elem, "elementType", 2);
        ref.set(elem, "picElement", pic);
        L.e("pic nt " + wh[0] + "x" + wh[1] + " orig=" + origSize
                + " thumb=" + thumbSize + " jpegThumb=" + (thumbSize > 0)
                + " name=" + fileName, null);
        return elem;
    }

    private static File jpegThumb(Ref ref, File file, String path) {
        String thumbSrc = officialThumbPath(ref, path);
        if (thumbSrc != null) {
            File generated = new File(thumbSrc);
            if (isJpegFile(generated)) return generated;
        }
        File written = writeThumbJpeg(file, 720);
        return isJpegFile(written) ? written : null;
    }

    private static boolean isJpegFile(File f) {
        if (f == null || !f.isFile() || f.length() < 3) return false;
        byte[] h = headBytes(f);
        return h.length >= 3 && (h[0] & 0xff) == 0xff && (h[1] & 0xff) == 0xd8 && (h[2] & 0xff) == 0xff;
    }

    private static boolean validPicElement(Ref ref, Object elem) {
        if (elem == null || Ref.asInt(ref.get(elem, "elementType")) != 2) return false;
        Object pic = ref.get(elem, "picElement");
        if (pic == null) return false;
        String src = Ref.asStr(ref.get(pic, "sourcePath"));
        File srcF = src == null ? null : new File(src);
        int w = Ref.asInt(ref.get(pic, "picWidth"));
        int h = Ref.asInt(ref.get(pic, "picHeight"));
        if (srcF == null || !srcF.isFile() || srcF.length() <= 0 || w <= 0 || h <= 0) {
            L.e("pic invalid w=" + w + " h=" + h + " size=" + (srcF == null ? -1 : srcF.length())
                    + " name=" + (srcF == null ? "" : srcF.getName()), null);
            return false;
        }
        return true;
    }

    private static String officialThumbPath(Ref ref, String path) {
        try {
            Object api = ref.callS(QROUTE, "api", ref.cls(PIC_COMPRESS_API));
            if (api == null) return null;
            Object pair = ref.call(api, "generateThumbPic", path);
            if (pair == null) return null;
            Object first = ref.get(pair, "first");
            return first == null ? null : String.valueOf(first);
        } catch (Throwable t) {
            return null;
        }
    }

    private static File writeThumbJpeg(File src, int maxSide) {
        android.graphics.Bitmap bmp = null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(src.getAbsolutePath(), bounds);
            int w = bounds.outWidth, h = bounds.outHeight;
            if (w <= 0 || h <= 0) return null;
            int sample = 1;
            while (Math.max(w, h) / sample > maxSide * 2) sample *= 2;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            bmp = BitmapFactory.decodeFile(src.getAbsolutePath(), o);
            if (bmp == null) return null;
            int bw = bmp.getWidth(), bh = bmp.getHeight();
            int longSide = Math.max(bw, bh);
            if (longSide > maxSide) {
                float scale = maxSide / (float) longSide;
                android.graphics.Bitmap scaled = android.graphics.Bitmap.createScaledBitmap(
                        bmp, Math.max(1, Math.round(bw * scale)), Math.max(1, Math.round(bh * scale)), true);
                if (scaled != bmp) {
                    bmp.recycle();
                    bmp = scaled;
                }
            }
            File out = File.createTempFile("ntm", ".jpg", tempDir());
            try (FileOutputStream os = new FileOutputStream(out)) {
                if (!bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, os)) return null;
            }
            return out;
        } catch (Throwable t) {
            L.e("writeThumbJpeg", t);
            return null;
        } finally {
            if (bmp != null) bmp.recycle();
        }
    }

    /** GIF stays as-is. WebP/BMP/unknown and tiny bitmaps become a normal JPEG so NT can upload. */
    private static File prepareSendableImage(File file) {
        if (file == null || !file.isFile()) return file;
        String path = file.getAbsolutePath();
        String ext = mimeExt(path, extOf(file.getName()));
        if ("gif".equals(ext)) return file;
        int[] wh = boundsExact(path);
        boolean tiny = wh[0] > 0 && wh[1] > 0 && Math.min(wh[0], wh[1]) < 64;
        boolean odd = !("jpg".equals(ext) || "jpeg".equals(ext) || "png".equals(ext));
        if (!tiny && !odd) return file;
        File jpeg = reencodeJpeg(file, tiny);
        return jpeg != null ? jpeg : file;
    }

    private static File reencodeJpeg(File src, boolean upscale) {
        Bitmap bmp = null;
        try {
            bmp = BitmapFactory.decodeFile(src.getAbsolutePath());
            if (bmp == null) return null;
            int w = bmp.getWidth(), h = bmp.getHeight();
            if (w <= 0 || h <= 0) return null;
            if (upscale && Math.min(w, h) < 64) {
                float scale = 64f / Math.min(w, h);
                int nw = Math.max(64, Math.round(w * scale));
                int nh = Math.max(64, Math.round(h * scale));
                Bitmap scaled = Bitmap.createScaledBitmap(bmp, nw, nh, true);
                if (scaled != bmp) {
                    bmp.recycle();
                    bmp = scaled;
                }
            }
            File out = File.createTempFile("ntm", ".jpg", tempDir());
            try (FileOutputStream o = new FileOutputStream(out)) {
                if (!bmp.compress(Bitmap.CompressFormat.JPEG, 90, o)) return null;
            }
            return out;
        } catch (Throwable t) {
            L.e("reencodeJpeg", t);
            return null;
        } finally {
            if (bmp != null) bmp.recycle();
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

    private static boolean copyToDest(Ref ref, String srcPath, String destPath, long size) {
        if (destPath == null || destPath.isEmpty()) return false;
        try {
            File destFile = new File(destPath);
            File parent = destFile.getParentFile();
            if (parent != null && !parent.isDirectory()) parent.mkdirs();
            boolean exist = false;
            long dsize = 0;
            try {
                exist = Boolean.TRUE.equals(ref.callS(QQNT_UTIL, "fileIsExist", destPath));
                dsize = Ref.asLong(ref.callS(QQNT_UTIL, "getFileSize", destPath));
            } catch (Throwable ignore) {}
            if (!exist || dsize != size) {
                try {
                    ref.callS(QQNT_UTIL, "copyFile", srcPath, destPath);
                } catch (Throwable t) {
                    copyFile(new File(srcPath), destFile);
                }
            }
            File check = new File(destPath);
            if (check.isFile() && check.length() > 0) return true;
            copyFile(new File(srcPath), destFile);
            return destFile.isFile() && destFile.length() > 0;
        } catch (Throwable t) {
            L.e("copyToDest " + new File(destPath).getName(), t);
            return false;
        }
    }

    /**
     * Build a KELEMTYPEPTT(4) voice element. Input must already be Tencent SILK (`\x02#!SILK_V3`).
     *
     * Official {@code createPttElement} copies through an NT dest named after the temp
     * {@code obsilk*} file. After {@code sendMsg} the kernel drops that path
     * ({@code filePath=""}, {@code pathExists=false}), so AIO's click handler sees no
     * local file and never starts playback. Stage the same {@code {md5}.silk} into the
     * native recording directory first, then point {@code filePath} at a file that
     * actually exists.
     */
    public static Object buildPttElement(Ref ref, Object msgService, File file) {
        try {
            String path = file.getAbsolutePath();
            int[] fmt = pttFormat(path);            // [formatType, durationSec]
            String md5 = Ref.asStr(ref.callS(QQNT_UTIL, "genFileMd5Hex", path));
            String fileName = (md5 == null || md5.isEmpty())
                    ? file.getName()
                    : md5 + (fmt[0] == 0 ? ".amr" : ".silk");
            java.util.ArrayList<Byte> waves = pttWaves(fmt[1]);
            String classic = classicPttPath(ref, fileName);
            String ntDest = "";
            if (msgService != null && md5 != null && !md5.isEmpty()) {
                ntDest = richMediaDest(ref, msgService, 4, 3, md5, fileName, 1);
            }
            String staged = firstExistingCopy(ref, path, file.length(), ntDest, classic);
            if (staged == null || staged.isEmpty()) staged = path;

            Object elem = null;
            if (fmt[0] == 1) {
                try {
                    Object api = ref.callS(QROUTE, "api", ref.cls(MSG_UTIL_API));
                    // 3-arg overload is the native recording path (subtype 3 + waveform).
                    elem = api == null ? null
                            : ref.call(api, "createPttElement", staged, fmt[1] * 1000, waves);
                    if (elem != null && (Ref.asInt(ref.get(elem, "elementType")) != 4
                            || ref.get(elem, "pttElement") == null)) {
                        elem = null;
                    }
                } catch (Throwable t) {
                    L.e("official createPttElement", t);
                    elem = null;
                }
            }

            Object ptt;
            if (elem != null) {
                ptt = ref.get(elem, "pttElement");
            } else {
                ptt = ref.neu(PTT_ELEMENT);
                elem = ref.neu(QQClient.MSG_ELEMENT);
                ref.set(elem, "elementType", 4);
                ref.set(elem, "pttElement", ptt);
                ref.set(ptt, "fileId", Integer.valueOf(0));
                ref.set(ptt, "fileUuid", "");
                ref.set(ptt, "fileSubId", "");
                ref.set(ptt, "text", "");
            }

            String keep = playablePttPath(ref, ptt, staged, classic, ntDest);
            if (md5 != null && !md5.isEmpty()) ref.set(ptt, "md5HexStr", md5);
            if (keep != null && !keep.isEmpty()) {
                ref.set(ptt, "filePath", keep);
                ref.set(ptt, "fileName", new File(keep).getName());
                long size = new File(keep).length();
                if (size > 0) ref.set(ptt, "fileSize", size);
            }
            ref.set(ptt, "duration", fmt[1]);
            ref.set(ptt, "formatType", Integer.valueOf(fmt[0] == 0 ? 0 : 1));
            ref.set(ptt, "voiceType", 2);
            ref.set(ptt, "voiceChangeType", 0);
            ref.set(ptt, "canConvert2Text", false);
            ref.set(ptt, "waveAmplitudes", waves);
            ref.set(ptt, "playState", Integer.valueOf(1));
            return elem;
        } catch (Throwable t) {
            L.e("buildPttElement", t);
            return null;
        }
    }

    /** After sendMsg the kernel may blank filePath. Put the silk back where AIO looks. */
    public static void repairSentPtt(Ref ref, Object rec) {
        if (ref == null || rec == null) return;
        Object els = ref.get(rec, "elements");
        if (!(els instanceof java.util.List)) return;
        for (Object e : (java.util.List<?>) els) {
            if (e == null || Ref.asInt(ref.get(e, "elementType")) != 4) continue;
            Object ptt = ref.get(e, "pttElement");
            if (ptt == null) continue;
            String path = Ref.asStr(ref.get(ptt, "filePath"));
            if (new File(path).isFile()) continue;
            String md5 = Ref.asStr(ref.get(ptt, "md5HexStr"));
            String name = Ref.asStr(ref.get(ptt, "fileName"));
            if (name.isEmpty() && !md5.isEmpty()) name = md5 + ".silk";
            String classic = classicPttPath(ref, name);
            File src = new File(classic);
            if (!src.isFile() && !md5.isEmpty()) {
                String alt = classicPttPath(ref, md5 + ".silk");
                if (new File(alt).isFile()) {
                    classic = alt;
                    src = new File(classic);
                }
            }
            if (!src.isFile()) continue;
            if (path == null || path.isEmpty()) {
                ref.set(ptt, "filePath", classic);
                if (Ref.asStr(ref.get(ptt, "fileName")).isEmpty())
                    ref.set(ptt, "fileName", src.getName());
            } else {
                copyToDest(ref, classic, path, src.length());
            }
        }
    }

    private static String firstExistingCopy(Ref ref, String src, long size, String... dests) {
        String kept = null;
        for (String dest : dests) {
            if (dest == null || dest.isEmpty()) continue;
            if (copyToDest(ref, src, dest, size) && kept == null) kept = dest;
        }
        return kept;
    }

    /** Prefer a path AIO can still open after sendMsg rewrites the NT dest. */
    private static String playablePttPath(Ref ref, Object ptt, String staged, String classic, String ntDest) {
        String official = ptt == null ? "" : Ref.asStr(ref.get(ptt, "filePath"));
        if (isFile(classic)) return classic;
        if (isFile(official)) return official;
        if (isFile(staged)) return staged;
        if (isFile(ntDest)) return ntDest;
        return official != null && !official.isEmpty() ? official : staged;
    }

    private static boolean isFile(String path) {
        return path != null && !path.isEmpty() && new File(path).isFile();
    }

    /** Native recording store: {@code .../Tencent/MobileQQ/{uin}/ptt/{md5}.silk}. */
    private static String classicPttPath(Ref ref, String fileName) {
        if (fileName == null || fileName.isEmpty()) return "";
        String uin = currentUin(ref);
        if (uin.isEmpty()) return "";
        File dir = new File("/sdcard/Android/data/com.tencent.mobileqq/Tencent/MobileQQ/" + uin + "/ptt");
        if (!dir.isDirectory() && !dir.mkdirs()) return "";
        return new File(dir, fileName).getAbsolutePath();
    }

    private static String currentUin(Ref ref) {
        if (ref == null) return "";
        try {
            Object app = ref.callS("mqq.app.MobileQQ", "getMobileQQ");
            if (app == null) return "";
            Object runtime = null;
            try { runtime = ref.call(app, "peekAppRuntime"); } catch (Throwable ignore) {}
            if (runtime == null) {
                try { runtime = ref.call(app, "waitAppRuntime", new Object[]{null}); }
                catch (Throwable ignore) {}
            }
            if (runtime == null) return "";
            String uin = Ref.asStr(ref.call(runtime, "getCurrentUin"));
            if (uin.isEmpty()) uin = Ref.asStr(ref.call(runtime, "getAccount"));
            uin = uin == null ? "" : uin.trim();
            return (uin.isEmpty() || "0".equals(uin)) ? "" : uin;
        } catch (Throwable t) {
            return "";
        }
    }

    /** AIO hides the voice duration/wave controls when the outgoing PTT waveform is empty. */
    private static java.util.ArrayList<Byte> pttWaves(int durationSec) {
        int count = Math.max(12, Math.min(300, Math.max(1, durationSec) * 10));
        java.util.ArrayList<Byte> waves = new java.util.ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int amplitude = 18 + ((i * 17 + i * i * 3) % 45);
            waves.add(Byte.valueOf((byte) amplitude));
        }
        return waves;
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
        int[] wh = boundsExact(path);
        return new int[]{wh[0] > 0 ? wh[0] : 100, wh[1] > 0 ? wh[1] : 100};
    }

    private static int[] boundsExact(String path) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, o);
            return new int[]{o.outWidth, o.outHeight};
        } catch (Throwable t) { return new int[]{0, 0}; }
    }

    private static String mimeExt(String path, String fallback) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, o);
            String mime = o.outMimeType == null ? "" : o.outMimeType.toLowerCase(Locale.ROOT);
            if (mime.contains("png")) return "png";
            if (mime.contains("gif")) return "gif";
            if (mime.contains("webp")) return "webp";
            if (mime.contains("jpeg") || mime.contains("jpg")) return "jpg";
            if (mime.contains("bmp")) return "bmp";
        } catch (Throwable ignore) {}
        String sniff = guessExt(headBytes(new File(path)));
        if (sniff.length() > 1) return sniff.substring(1);
        return fallback == null || fallback.isEmpty() ? "jpg" : fallback;
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
            case "bmp": return 1005;
            default: return 1000; // jpg
        }
    }
}
