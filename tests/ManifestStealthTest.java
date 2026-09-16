import java.io.File;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Contract between the two shipped manifests (DuckDetector-driven).
 *
 * DuckDetector's LSPosedPackageProbe reads getInstalledApplications(GET_META_DATA)
 * and flags any app whose meta-data contains a key starting with "xposed"
 * (xposedmodule / xposedminversion / xposeddescription / xposedscope /
 * xposedsharedprefs). The stealth manifest must therefore declare none of them,
 * while the bootstrap manifest keeps them so vector/LSPosed can list and
 * register the module. The two manifests must stay the same package, version and
 * label so the stealth APK installs over the enabled bootstrap install with
 * `pm install -r` (same signature comes from the shared keystore in build.sh).
 */
public final class ManifestStealthTest {
    public static void main(String[] args) throws Exception {
        File root = repoRoot();
        Document normal = parse(new File(root, "AndroidManifest.xml"));
        Document stealth = parse(new File(root, "AndroidManifest.stealth.xml"));

        eq("com.satori.qq", manifestAttr(normal, "package"), "package");
        eq(manifestAttr(normal, "package"), manifestAttr(stealth, "package"), "package sync");
        eq(manifestAttr(normal, "versionCode"), manifestAttr(stealth, "versionCode"), "versionCode sync");
        eq(manifestAttr(normal, "versionName"), manifestAttr(stealth, "versionName"), "versionName sync");

        // 第三处版本号：模块报给 /healthz 与 READY 的那个。清单与它不同步时，
        // 「装上的到底是哪一版」这件事就没有可信来源了（排查时先看 healthz）。
        String reported = appVersion(new File(root, "src/com/satori/qq/core/SatoriHub.java"));
        eq(manifestAttr(normal, "versionName"), reported, "APP_VERSION sync");

        check(isSemver(manifestAttr(normal, "versionName")),
                "versionName is semver-ish: " + manifestAttr(normal, "versionName"));
        int versionCode = 0;
        try {
            versionCode = Integer.parseInt(manifestAttr(normal, "versionCode"));
        } catch (NumberFormatException e) {
            throw new AssertionError("versionCode is an integer: " + manifestAttr(normal, "versionCode"));
        }
        check(versionCode > 0, "versionCode positive");

        int normalXposed = countXposedMeta(normal);
        check(normalXposed >= 4, "bootstrap keeps >=4 xposed meta-data entries, got " + normalXposed);
        eq(0, countXposedMeta(stealth), "stealth xposed meta-data entries");

        NodeList apps = stealth.getElementsByTagName("application");
        eq(1, apps.getLength(), "stealth application node");
        Element app = (Element) apps.item(0);
        check("@string/app_name".equals(app.getAttribute("android:label")), "shared short brand label");
        Element normalApp = (Element) normal.getElementsByTagName("application").item(0);
        eq(normalApp.getAttribute("android:label"), app.getAttribute("android:label"), "label sync");
        eq(1, normal.getElementsByTagName("activity").getLength(), "normal has management activity");
        eq(0, stealth.getElementsByTagName("activity").getLength(), "stealth has no activity");
        eq(1, stealth.getElementsByTagName("provider").getLength(), "stealth retains settings bridge");
        check(!"false".equals(app.getAttribute("android:hasCode")), "stealth hasCode");

        System.out.println("ManifestStealthTest passed");
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

    private static int countXposedMeta(Document doc) {        int n = 0;
        NodeList metas = doc.getElementsByTagName("meta-data");
        for (int i = 0; i < metas.getLength(); i++) {
            Element meta = (Element) metas.item(i);
            String name = meta.getAttribute("android:name");
            if (name.startsWith("xposed")) n++;
        }
        return n;
    }

    /**
     * 取 manifest 根节点上的属性。
     *
     * <p>android 命名空间那一串必须用限定名取：原先只写 {@code getAttribute("versionCode")}，
     * 取到的一直是空串，于是「两个清单的 versionCode/versionName 一致」这两条断言是**空比对**，
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

    /** Walk up from the working directory until both manifests are visible. */
    private static File repoRoot() {
        for (File d = new File(System.getProperty("user.dir")); d != null; d = d.getParentFile()) {
            if (new File(d, "AndroidManifest.xml").isFile()
                    && new File(d, "AndroidManifest.stealth.xml").isFile()) {
                return d;
            }
        }
        throw new IllegalStateException("run from the satori-qq tree (cannot locate manifests)");
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
