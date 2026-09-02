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

        int normalXposed = countXposedMeta(normal);
        check(normalXposed >= 4, "bootstrap keeps >=4 xposed meta-data entries, got " + normalXposed);
        eq(0, countXposedMeta(stealth), "stealth xposed meta-data entries");

        NodeList apps = stealth.getElementsByTagName("application");
        eq(1, apps.getLength(), "stealth application node");
        Element app = (Element) apps.item(0);
        check("Kernel Bridge".equals(app.getAttribute("android:label")), "stealth label unchanged");
        check(!"false".equals(app.getAttribute("android:hasCode")), "stealth hasCode");

        System.out.println("ManifestStealthTest passed");
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

    private static String manifestAttr(Document doc, String attr) {
        return doc.getDocumentElement().getAttribute(attr);
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
