package com.satori.qq;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

/**
 * 模块在 QQ 私有目录里落盘的位置。
 *
 * <p>QQ 随时能看见自己的 `files/`：文件名里带模块名，等于把模块写在宿主的目录上——按
 * {@code satori} / {@code xposed} / {@code zygisk} 这类名字扫一遍就能命中。所以开关、日志、
 * 群名缓存全部收进一个不以模块命名的隐藏目录（{@code files/.svc}，0700）。
 *
 * <p>升级时 {@link #prepare()} 先把旧开关的值搬进来，再删掉 {@code files/} 下所有旧条目
 * （{@code satori-qq/enabled}、{@code satori-native.log}、{@code qk_sso.log} …）。
 */
public final class Paths {

    private static final File FILES = new File("/data/data/com.tencent.mobileqq/files");
    private static final File DIR = new File(FILES, ".svc");
    /** files/ 下这些名字都是我们留下的（旧版本的开关、日志与审计文件）。 */
    private static final String[] LEGACY = {"satori-", "qk_sso.log"};

    private Paths() {}

    public static File dir() { return DIR; }

    public static File of(String name) { return new File(DIR, name); }

    public static String str(String name) { return of(name).getAbsolutePath(); }

    public static void ensure() {
        if (DIR.isDirectory()) return;
        DIR.mkdirs();
        DIR.setReadable(true, true);
        DIR.setWritable(true, true);
        DIR.setExecutable(true, true);
    }

    /** 只在主进程启动时调一次，早于任何会写文件的部件。 */
    public static void prepare() {
        ensure();
        migrateFlag();
        dropLegacy();
    }

    /** 旧开关的值搬到新位置；新位置已经有值时以新的为准（用户可能在管理页改过）。 */
    private static void migrateFlag() {
        File flag = of("enabled");
        if (flag.exists()) return;
        File legacy = new File(new File(FILES, "satori-qq"), "enabled");
        if (!legacy.isFile()) return;
        try (FileInputStream in = new FileInputStream(legacy)) {
            int value = in.read();
            if (value != '0' && value != '1') return;
            try (FileOutputStream out = new FileOutputStream(flag)) {
                out.write(value);
            }
        } catch (Throwable ignore) {
            // 迁不过来就按「没有开关文件 = 开启」处理，不阻塞启动。
        }
    }

    private static void dropLegacy() {
        File[] entries = FILES.listFiles();
        if (entries == null) return;
        for (File entry : entries) {
            String name = entry.getName();
            boolean ours = false;
            for (String prefix : LEGACY) {
                if (name.startsWith(prefix)) {
                    ours = true;
                    break;
                }
            }
            if (ours) deleteTree(entry);
        }
    }

    private static void deleteTree(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteTree(child);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
