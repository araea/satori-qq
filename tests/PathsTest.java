package com.satori.qq;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 痕迹收敛的落盘迁移：只删自己的旧条目，只搬开关的值，别的文件一个都不许碰。
 *
 * <p>这段逻辑在 QQ 启动时对宿主数据目录动手，坏掉的代价是「删错东西」，所以单独测。
 */
public final class PathsTest {

    private static int failures;

    private static void check(boolean ok, String what) {
        if (!ok) {
            System.out.println("FAIL: " + what);
            failures++;
        }
    }

    private static void touch(File file, String text) throws Exception {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void makeLegacyTree(File files) throws Exception {
        File dir = new File(files, "satori-qq");
        if (!dir.mkdirs()) throw new AssertionError("mkdir " + dir);
        touch(new File(dir, "enabled"), "0");
        File tmp = new File(files, "satori-tmp");
        if (!tmp.mkdirs()) throw new AssertionError("mkdir " + tmp);
        touch(new File(tmp, "upload.bin"), "partial");
        touch(new File(files, "satori-native.log"), "x");
        touch(new File(files, "qk_sso.log"), "x");

        // 宿主的目录与文件：必须原样留着。
        File mmkv = new File(files, "mmkv");
        if (!mmkv.mkdirs()) throw new AssertionError("mkdir " + mmkv);
        touch(new File(mmkv, "cache.dat"), "keep");
        if (!new File(files, "QQfile_recv").mkdirs()) throw new AssertionError("mkdir QQfile_recv");
        touch(new File(files, "ConfigStore2.dat"), "keep");
        touch(new File(files, "not-satori.log"), "keep");
    }

    private static boolean exists(File file) { return file.exists(); }

    private static void testMigration() throws Exception {
        File root = Files.createTempDirectory("paths-test").toFile();
        File files = new File(root, "files");
        if (!files.mkdirs()) throw new AssertionError("mkdir " + files);
        makeLegacyTree(files);

        Paths.prepare(files);

        File dir = new File(files, ".svc");
        check(exists(new File(dir, "enabled")), "switch moved into .svc");
        byte[] value = Files.readAllBytes(new File(dir, "enabled").toPath());
        check(value.length == 1 && value[0] == '0', "switch keeps its value");

        String[] gone = {"satori-qq", "satori-tmp", "satori-native.log", "qk_sso.log"};
        for (String name : gone) check(!exists(new File(files, name)), name + " removed");
        String[] kept = {"mmkv", "mmkv/cache.dat", "QQfile_recv", "ConfigStore2.dat", "not-satori.log"};
        for (String name : kept) check(exists(new File(files, name)), name + " kept");
        check(files.list().length == 5, "only .svc plus the host's own entries are left");

        // 第二遍什么都不该动。
        Paths.prepare(files);
        check(exists(new File(dir, "enabled")), "second pass keeps the switch");
        check(files.list().length == 5, "second pass removes nothing");
    }

    private static void testNewSwitchWins() throws Exception {
        File root = Files.createTempDirectory("paths-test").toFile();
        File files = new File(root, "files");
        if (!files.mkdirs()) throw new AssertionError("mkdir " + files);
        makeLegacyTree(files);
        File dir = new File(files, ".svc");
        if (!dir.mkdirs()) throw new AssertionError("mkdir " + dir);
        touch(new File(dir, "enabled"), "1");

        Paths.prepare(files);
        byte[] value = Files.readAllBytes(new File(dir, "enabled").toPath());
        check(value.length == 1 && value[0] == '1', "existing switch is not overwritten");
    }

    private static void testCleanInstall() throws Exception {
        File root = Files.createTempDirectory("paths-test").toFile();
        File files = new File(root, "files");
        if (!files.mkdirs()) throw new AssertionError("mkdir " + files);
        touch(new File(files, "ConfigStore2.dat"), "keep");

        Paths.prepare(files);
        check(exists(new File(files, ".svc")), ".svc is created anyway");
        check(exists(new File(files, "ConfigStore2.dat")), "nothing else is touched");
    }

    public static void main(String[] args) throws Exception {
        testMigration();
        testNewSwitchWins();
        testCleanInstall();
        if (failures != 0) {
            System.out.println("PathsTest: " + failures + " failure(s)");
            System.exit(1);
        }
        System.out.println("PathsTest OK");
    }
}
