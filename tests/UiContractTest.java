import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 管理页的跨文件契约：实例状态的键、以及真机用例要用反射摸的那些名字。
 *
 * <p>为什么值得单独钉：这两个契约都在**编译期看不出来**的地方。
 * <ul>
 *   <li>{@code onSaveInstanceState} 写的键与 {@code onCreate} 读的键分散在同一个文件的两头，
 *       改一处漏一处不会编译失败，表现是"重建后草稿没了"；</li>
 *   <li>真机设计冒烟（{@code tests/ui/DesignSmoke.java}）靠反射按名字取字段与方法，
 *       重命名 {@code MainActivity} 的字段或方法不会让 {@code ./test.sh} 变红，
 *       只会在装机跑 {@code tests/ui/run.sh} 时报一个难读的 NoSuchFieldException。
 *       这里把那份名单固定下来，改名时两边一起改。</li>
 * </ul>
 */
public final class UiContractTest {

    /** 真机用例反射摸的主页字段。改名要同时改 tests/ui/DesignSmoke.java。 */
    private static final String[] FIELDS_INSPECTED_BY_DEVICE_TEST = {
            "ui", "layout", "pages", "navigation", "navLabels", "navIcons", "selected",
            "resumed", "probing", "saving", "revealing", "health", "checkedAt",
            "hero", "stateTitle", "stateDetail", "clients", "uptime", "endpoint", "updated",
            "configurationStatus", "configurationCard", "versionStatus", "diagnostics",
            "diagnosticHint", "diagnosticRows", "dirtyLabel", "dirtyCard", "sourceLabel",
            "guardState", "refreshButton", "saveButton", "revealButton", "reportButton",
            "shareButton", "portInput", "tokenInput", "toggles", "progress",
    };

    /** 真机用例反射调用的私有方法：名字 + 形参个数。 */
    private static final String[][] METHODS_CALLED_BY_DEVICE_TEST = {
            {"switchTab", "2"}, {"save", "1"}, {"reveal", "1"}, {"updateState", "0"},
    };

    public static void main(String[] args) throws Exception {
        String source = read(new File(repoRoot(), "src/com/satori/qq/ui/MainActivity.java"));

        // 1. 实例状态的键必须两头都在：写的每个键都要被读回来，读的每个键都要被写出去。
        Set<String> written = keys(source, "out\\.put(?:String|Int|Boolean|Long|Bundle)\\s*\\(\\s*\"([A-Za-z0-9_]+)\"");
        Set<String> read = new TreeSet<>();
        Matcher reader = Pattern.compile("state\\.(?:get|opt)(?:String|Int|Boolean|Long)\\s*\\(\\s*\"([A-Za-z0-9_]+)\"")
                .matcher(source);
        while (reader.find()) read.add(reader.group(1));
        // 两个页签之外的滚动位置是按 pages 长度循环写/读的，键名是拼出来的
        // （"scroll" + i），字面量正则抓不到，两边一起放进来。
        written.add("scroll:");
        read.add("scroll:");
        check(!written.isEmpty(), "没解析到 onSaveInstanceState 写入的键，先看正则是否还匹配");
        Set<String> writtenOnly = new TreeSet<>(written);
        writtenOnly.removeAll(read);
        Set<String> readOnly = new TreeSet<>(read);
        readOnly.removeAll(written);
        check(writtenOnly.isEmpty() && readOnly.isEmpty(),
                "实例状态的键对不上：只写不读 " + writtenOnly + "，只读不写 " + readOnly);

        // 2. 真机用例按名字摸的字段都要在。
        Set<String> declared = declaredFields(source);
        for (String name : FIELDS_INSPECTED_BY_DEVICE_TEST) {
            check(declared.contains(name),
                    "真机用例要读字段 " + name + "，MainActivity 里没有——改名时两边一起改");
        }

        // 3. 真机用例反射调用的方法都要在，且形参个数一致。
        for (String[] method : METHODS_CALLED_BY_DEVICE_TEST) {
            check(declaresMethod(source, method[0], Integer.parseInt(method[1])),
                    "真机用例要调 " + method[0] + "(" + method[1] + " 个参数)，签名对不上");
        }

        System.out.println("UiContractTest passed (" + written.size() + " 个实例状态键，"
                + FIELDS_INSPECTED_BY_DEVICE_TEST.length + " 个字段，"
                + METHODS_CALLED_BY_DEVICE_TEST.length + " 个方法)");
    }

    /** 只取形如 {@code private TextView stateTitle, stateDetail;} / {@code private int selected;} 的声明。 */
    private static Set<String> declaredFields(String source) {
        Set<String> names = new LinkedHashSet<>();
        Matcher matcher = Pattern.compile(
                "^\\s*(?:private|protected|public)\\s+(?:final\\s+)?[A-Za-z0-9_$.<>\\[\\], ]+?\\s+"
                        + "([A-Za-z0-9_$]+(?:\\s*,\\s*[A-Za-z0-9_$]+)*)\\s*(?:=[^;]*)?;",
                Pattern.MULTILINE).matcher(source);
        while (matcher.find()) {
            for (String name : matcher.group(1).split(",")) {
                names.add(name.trim());
            }
        }
        return names;
    }

    private static boolean declaresMethod(String source, String name, int parameters) {
        Matcher matcher = Pattern.compile("(?:private|protected|public|static|final|\\s)+"
                + Pattern.quote(name) + "\\s*\\(([^)]*)\\)").matcher(source);
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
        List<File> candidates = new ArrayList<>();
        for (File directory = new File(System.getProperty("user.dir")); directory != null;
             directory = directory.getParentFile()) {
            candidates.add(directory);
            if (new File(directory, "AndroidManifest.xml").isFile()) return directory;
        }
        throw new IllegalStateException("run from the satori-qq tree");
    }
}
