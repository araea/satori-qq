import java.io.File;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Contract of the shipped manifest and module registration.
 *
 * <p>0.22.0 起不再是 Xposed 模块：模块由 Magisk/KernelSU 模块（{@code build.sh} 里打包的
 * {@code module/}）经 Zygisk Next 注入，APK 里不能有 {@code META-INF/xposed/} 注册文件——
 * 留着会让 Vector 把它按 Xposed 模块注入 QQ，而「进程里有 LSPosed 模块」正是人脸验证失败的
 * 原因。管理 Activity 与设置 Provider 仍在同一个 APK 上。版本必须与 SatoriHub.APP_VERSION
 * 一致，那是模块报给 /healthz 与 READY 的值；两者不同时「装上的到底是哪一版」就没有可信来源。
 */
public final class ManifestTest {
    public static void main(String[] args) throws Exception {
        File root = repoRoot();
        Document manifest = parse(new File(root, "AndroidManifest.xml"));

        eq("com.satori.qq", manifestAttr(manifest, "package"), "package");

        // 第三处版本号：模块报给 /healthz 与 READY 的那个。清单与它不同步时，
        // 「装上的到底是哪一版」这件事就没有可信来源了（排查时先看 healthz）。
        String reported = appVersion(new File(root, "src/com/satori/qq/core/SatoriHub.java"));
        eq(manifestAttr(manifest, "versionName"), reported, "APP_VERSION sync");

        check(isSemver(manifestAttr(manifest, "versionName")),
                "versionName is semver-ish: " + manifestAttr(manifest, "versionName"));
        int versionCode = 0;
        try {
            versionCode = Integer.parseInt(manifestAttr(manifest, "versionCode"));
        } catch (NumberFormatException e) {
            throw new AssertionError("versionCode is an integer: " + manifestAttr(manifest, "versionCode"));
        }
        check(versionCode > 0, "versionCode positive");

        eq(0, countXposedMeta(manifest), "no legacy xposed meta-data");
        // 0.22.0 起不是 Xposed 模块了：APK 里不能再留注册文件，否则 Vector/LSPosed 会把它当模块
        // 注入进 QQ —— 那正是人脸验证失败的原因（实测连零钩子的模块也失败）。
        check(!new File(root, "resources/META-INF/xposed").exists(),
                "no META-INF/xposed registration left");
        // 注入改由 Magisk 模块承担：Zygisk Next 只认模块目录里的 zn_modules.txt。
        String buildScript = read(new File(root, "build.sh"));
        check(buildScript.contains("name=com.tencent.mobileqq zygisk/arm64-v8a.so"),
                "module zn_modules.txt targets the QQ package");
        check(buildScript.contains("zygisk/arm64-v8a.so"),
                "module ships the zygisk entry at the expected path");
        check(buildScript.contains("id=satori_qq"), "module id");

        NodeList apps = manifest.getElementsByTagName("application");
        eq(1, apps.getLength(), "application node");
        Element app = (Element) apps.item(0);
        check("@string/app_name".equals(app.getAttribute("android:label")), "short brand label");
        eq(1, manifest.getElementsByTagName("activity").getLength(), "management activity");
        eq(1, manifest.getElementsByTagName("provider").getLength(), "settings bridge");
        check(!"false".equals(app.getAttribute("android:hasCode")), "hasCode");
        eq("com.satori.qq.control",
                ((Element) manifest.getElementsByTagName("provider").item(0)).getAttribute("android:authorities"),
                "provider authority");

        System.out.println("ManifestTest passed");
    }

    /** 从 SatoriHub.java 里读出 {@code APP_VERSION = "x.y.z"}。 */
    private static String appVersion(File source) throws Exception {
        if (!source.isFile()) throw new AssertionError("missing " + source);
        String text = new String(java.nio.file.Files.readAllBytes(source.toPath()), "UTF-8");
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("APP_VERSION\\s*=\\s*\"([^\"]+)\"").matcher(text);
        if (!m.find()) throw new AssertionError("APP_VERSION not found in " + source);
        return m.group(1);
    }

    /**
     * 语义化版本：{@code 主.次.补丁}，可选的第四段留给「同一天发两次」的构建号。
     * 前三段必须是数字，且只能是这四段——写成 {@code 0.9} 或 {@code 0.9.0.1.2} 都不算。
     */
    private static boolean isSemver(String name) {
        return name != null && name.matches("\\d+\\.\\d+\\.\\d+(\\.\\d+)?");
    }

    private static int countXposedMeta(Document doc) {
        int n = 0;
        NodeList metas = doc.getElementsByTagName("meta-data");
        for (int i = 0; i < metas.getLength(); i++) {
            Element meta = (Element) metas.item(i);
            String name = meta.getAttribute("android:name");
            if (name.startsWith("xposed")) n++;
        }
        return n;
    }

    private static String read(File file) throws Exception {
        if (!file.isFile()) throw new AssertionError("missing " + file);
        return new String(java.nio.file.Files.readAllBytes(file.toPath()), "UTF-8");
    }

    /**
     * 取 manifest 根节点上的属性。
     *
     * <p>android 命名空间那一串必须用限定名取：原先只写 {@code getAttribute("versionCode")}，
     * 取到的一直是空串，于是「versionCode/versionName 存在」这类断言是**空比对**，
     * 永远通过。现在先按命名空间取，再退回带前缀的限定名，并且要求结果非空。
     */
    private static String manifestAttr(Document doc, String attr) {
        Element root = doc.getDocumentElement();
        for (String name : new String[]{attr, "android:" + attr}) {
            String v = root.getAttribute(name);
            if (v != null && !v.isEmpty()) return v;
        }
        throw new AssertionError("manifest has no " + attr);
    }

    private static Document parse(File xml) throws Exception {
        return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml);
    }

    /** Walk up from the working directory until the manifest is visible. */
    private static File repoRoot() {
        for (File d = new File(System.getProperty("user.dir")); d != null; d = d.getParentFile()) {
            if (new File(d, "AndroidManifest.xml").isFile()) {
                return d;
            }
        }
        throw new IllegalStateException("run from the satori-qq tree (cannot locate the manifest)");
    }

    private static void check(boolean cond, String what) {
        if (!cond) throw new AssertionError(what);
    }

    private static void eq(Object expect, Object got, String what) {
        if (!expect.equals(got)) {
            throw new AssertionError(what + ": expect <" + expect + "> got <" + got + ">");
        }
    }
}
