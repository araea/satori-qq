package com.satori.qq.update;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 把安装包放进共享的「下载」文件夹，让用户能在 root 管理器或系统安装器里手动安装。
 *
 * <p>Android 10 起经 MediaStore 写入，不需要任何存储权限，文件归本应用所有，之后删得掉。
 * 更早的系统没有这条路，只能让 root 复制过去；那时拿不到可以直接打开的链接，界面只说放在哪。
 */
final class Exports {
    private Exports() {}

    static final String MODULE_MIME = "application/zip";
    static final String APP_MIME = "application/vnd.android.package-archive";

    /** @return 可以交给其他应用打开的链接；旧系统没有则为空串 */
    static String save(Context context, File source, String name, String mime) throws IOException {
        if (Build.VERSION.SDK_INT < 29) {
            File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            Shell.Result copied = Shell.run("mkdir -p '" + dir.getPath() + "' && cp -f '" + source.getPath() + "' '"
                    + dir.getPath() + "/" + name + "' && chmod 0644 '" + dir.getPath() + "/" + name + "'", 20_000);
            if (!copied.ok()) throw new IOException("无法写入「下载」文件夹");
            return "";
        }
        ContentResolver resolver = context.getContentResolver();
        Uri collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
        removeNamed(resolver, collection, name);
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, name);
        values.put(MediaStore.Downloads.MIME_TYPE, mime);
        values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
        values.put(MediaStore.Downloads.IS_PENDING, 1);
        Uri uri = resolver.insert(collection, values);
        if (uri == null) throw new IOException("无法写入「下载」文件夹");
        try (InputStream in = new FileInputStream(source); OutputStream out = resolver.openOutputStream(uri)) {
            if (out == null) throw new IOException("无法写入「下载」文件夹");
            byte[] chunk = new byte[16384];
            int count;
            while ((count = in.read(chunk)) != -1) out.write(chunk, 0, count);
        } catch (IOException error) {
            resolver.delete(uri, null, null);
            throw error;
        }
        ContentValues done = new ContentValues();
        done.put(MediaStore.Downloads.IS_PENDING, 0);
        resolver.update(uri, done, null, null);
        return uri.toString();
    }

    /** 不再需要时删掉（只删得了自己放进去的）。 */
    static void remove(Context context, String uri) {
        if (uri.isEmpty() || Build.VERSION.SDK_INT < 29) return;
        try {
            context.getContentResolver().delete(Uri.parse(uri), null, null);
        } catch (RuntimeException ignored) {
            // 用户已经删了，或系统不让动：留着也无碍。
        }
    }

    /** 同名的旧文件：重复保存时系统会改成「名字 (1)」，先清掉自己上一次放的。 */
    private static void removeNamed(ContentResolver resolver, Uri collection, String name) {
        try (Cursor cursor = resolver.query(collection, new String[] {MediaStore.Downloads._ID},
                MediaStore.Downloads.DISPLAY_NAME + "=?", new String[] {name}, null)) {
            if (cursor == null) return;
            while (cursor.moveToNext()) {
                resolver.delete(Uri.withAppendedPath(collection, String.valueOf(cursor.getLong(0))), null, null);
            }
        } catch (RuntimeException ignored) {
            // 查不到就当没有；真有同名文件，系统会自己改名。
        }
    }
}
