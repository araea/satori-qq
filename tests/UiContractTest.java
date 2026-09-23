import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * 管理界面的跨文件契约：实例状态的键、稳定视图 id，以及真机用例依赖的少数几个名字。
 *
 * <p>这些都在编译期看不出来的地方：
 * <ul>
 *   <li>实例状态写在 {@code onSaveInstanceState} / {@code SettingsPage.saveState}，读在
 *       {@code onCreate} / {@code restoreState}，改一处漏一处的表现是"旋转后草稿没了"；</li>
 *   <li>真机验收（{@code tests/ui/DesignSmoke.java}）按 {@code res/values/ids.xml} 里的 id 找视图，
 *       id 被删或改名只会在装机时才炸；</li>
 *   <li>注入测试数据用到的字段与方法按名字反射，这里把名单固定下来，改名时两边一起改。</li>
 * </ul>
 */
public final class UiContractTest {
    private static final String[] ACTIVITY_FIELDS = {"model", "home", "settings", "snackbar", "page", "resumed", "main", "poll", "guardAt", "twoPane"};
    private static final String[][] ACTIVITY_METHODS = {{"renderAll", "0"}, {"showPage", "2"}, {"ensureSettings", "0"}};
    private static final String[] MODEL_FIELDS = {"health", "checked", "probing", "guard", "guardBusy", "port"};

    public static void main(String[] args) throws Exception {
        File root = repoRoot();
        File ui = new File(root, "src/com/satori/qq/ui");
        String activity = read(new File(ui, "MainActivity.java"));
        String settings = read(new File(ui, "SettingsPage.java"));
        String home = read(new File(ui, "HomePage.java"));
        String smoke = read(new File(root, "tests/ui/DesignSmoke.java"));

        // 1. 实例状态：写出去的键都要读回来，读的键都要有人写。
        String both = activity + settings;
        Set<String> written = keys(both, "out\\.put(?:String|Int|Boolean|Long|BooleanArray)\\s*\\(\\s*\"([a-z_]+)\"");
        Set<String> readBack = keys(both, "state\\.(?:get(?:String|Int|Boolean|Long|BooleanArray)|containsKey)\\s*\\(\\s*\"([a-z_]+)\"");
        check(!written.isEmpty(), "没解析到写入实例状态的键，先看正则是否还匹配");
        Set<String> writeOnly = new TreeSet<>(written);
        writeOnly.removeAll(readBack);
        Set<String> readOnly = new TreeSet<>(readBack);
        readOnly.removeAll(written);
        check(writeOnly.isEmpty() && readOnly.isEmpty(), "实例状态的键对不上：只写不读 " + writeOnly + "，只读不写 " + readOnly);

        // 2. 稳定 id：ids.xml 里的每个都被界面用上，界面与真机用例用到的每个都在 ids.xml 里。
        Set<String> declared = new TreeSet<>();
        NodeList items = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new File(root, "res/values/ids.xml")).getElementsByTagName("item");
        for (int i = 0; i < items.getLength(); i++) declared.add(((Element) items.item(i)).getAttribute("name"));
        Set<String> used = new TreeSet<>();
        File[] sources = ui.listFiles();
        if (sources != null) {
            for (File source : sources) {
                if (source.getName().endsWith(".java")) used.addAll(keys(read(source), "R\\.id\\.([a-z_]+)"));
            }
        }
        Set<String> undeclared = new TreeSet<>(used);
        undeclared.removeAll(declared);
        check(undeclared.isEmpty(), "界面用了 ids.xml 里没有的 id：" + undeclared);
        Set<String> stale = new TreeSet<>(declared);
        stale.removeAll(used);
        check(stale.isEmpty(), "ids.xml 里有没人用的 id：" + stale);
        Set<String> probed = keys(smoke, "id\\(\"([a-z_]+)\"\\)");
        check(!probed.isEmpty(), "没解析到真机用例查找的 id");
        Set<String> missing = new TreeSet<>(probed);
        missing.removeAll(declared);
        check(missing.isEmpty(), "真机用例要找的 id 不存在：" + missing);

        // 3. 真机用例反射摸的名字。
        for (String field : ACTIVITY_FIELDS) {
            check(Pattern.compile("private [A-Za-z.<>\\[\\]]+[^;(]*\\b" + field + "\\b[^;(]*;").matcher(activity).find()
                            || Pattern.compile("private final [A-Za-z.<>]+ " + field + " =").matcher(activity).find(),
                    "MainActivity 缺字段 " + field + "（tests/ui/DesignSmoke 按名字读）");
        }
        for (String[] method : ACTIVITY_METHODS) {
            check(declaresMethod(activity, method[0], Integer.parseInt(method[1])),
                    "MainActivity 缺方法 " + method[0] + "(" + method[1] + " 个参数)");
        }
        for (String field : MODEL_FIELDS) {
            check(Pattern.compile("\\n\\s+[A-Za-z.]+ " + field + "\\b[^;]*;").matcher(home).find(),
                    "HomePage.Model 缺字段 " + field);
        }

        System.out.println("UiContractTest passed (" + written.size() + " 个实例状态键，"
                + declared.size() + " 个稳定 id，" + probed.size() + " 个被真机用例使用)");
    }

    private static boolean declaresMethod(String source, String name, int parameters) {
        Matcher matcher = Pattern.compile("(?:private|protected|public|static|final|\\s)+[A-Za-z<>\\[\\]]+\\s+"
                + Pattern.quote(name) + "\\s*\\(([^)]*)\\)\\s*\\{").matcher(source);
        while (matcher.find()) {
            String arguments = matcher.group(1).trim();
            int count = arguments.isEmpty() ? 0 : arguments.split(",").length;
            if (count == parameters) return true;
        }
        return false;
    }

    private static Set<String> keys(String source, String regex) {
        Set<String> found = new TreeSet<>();
        Matcher matcher = Pattern.compile(regex).matcher(source);
        while (matcher.find()) found.add(matcher.group(1));
        return found;
    }

    private static String read(File file) throws Exception {
        if (!file.isFile()) throw new AssertionError("missing " + file);
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static File repoRoot() {
        for (File directory = new File(System.getProperty("user.dir")); directory != null;
             directory = directory.getParentFile()) {
            if (new File(directory, "AndroidManifest.xml").isFile()) return directory;
        }
        throw new IllegalStateException("run from the satori-qq tree");
    }
}
