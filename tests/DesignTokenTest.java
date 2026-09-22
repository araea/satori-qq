import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Design Tokens 与组件规范的机器可验部分。
 *
 * <p>界面的"对不对"由真机测试（tests/ui）看，这里的都是能在 JVM 上确定判定的合约：
 * 颜色对比度、角色齐整、刻度单调、可触达下限，以及"界面代码里不许出现字面颜色"。
 *
 * <p>为什么值得钉住：对比度是 WCAG 2.2 AA 的硬指标（1.4.3 文字 4.5:1、1.4.11 非文字 3:1），
 * 靠肉眼在深浅两套主题里逐对看是不可靠的；而"界面里出现 #RRGGBB"这种拼贴正是设计体系
 * 失守的第一步。改令牌或加角色时，先看这个测试要求哪些配对。
 */
public final class DesignTokenTest {

    /** 文字角色：两两组合必须 ≥ 4.5:1（WCAG 1.4.3 正文，且我们按最严的"普通字号"算）。 */
    private static final String[][] TEXT_PAIRS = {
            {"md_on_primary", "md_primary"},
            {"md_on_primary_container", "md_primary_container"},
            {"md_on_secondary_container", "md_secondary_container"},
            {"md_on_tertiary_container", "md_tertiary_container"},
            {"md_on_error", "md_error"},
            {"md_on_error_container", "md_error_container"},
            {"md_on_success", "md_success"},
            {"md_on_success_container", "md_success_container"},
            {"md_on_warning", "md_warning"},
            {"md_on_warning_container", "md_warning_container"},
            {"md_on_surface", "md_surface"},
            {"md_on_surface", "md_surface_container"},
            {"md_on_surface", "md_surface_container_high"},
            {"md_on_surface_variant", "md_surface"},
            {"md_on_surface_variant", "md_surface_container"},
            {"md_on_surface_variant", "md_surface_container_high"},
            {"md_on_surface_variant", "md_surface_container_low"},
            {"md_primary", "md_surface"},
            {"md_primary", "md_surface_container"},
            {"md_primary", "md_surface_container_high"},
            {"md_error", "md_surface"},
            {"md_error", "md_surface_container"},
            {"md_success", "md_surface"},
            {"md_warning", "md_surface"},
            {"md_inverse_on_surface", "md_inverse_surface"},
    };

    /**
     * 非文字角色：承担"识别控件边界/状态"的必须 ≥ 3:1（WCAG 1.4.11）。
     * outline 是输入框描边与开关轨描边，primary 是焦点环，on_primary 是实心按钮上的焦点环。
     */
    private static final String[][] NON_TEXT_PAIRS = {
            {"md_outline", "md_surface"},
            {"md_outline", "md_surface_container"},
            {"md_outline", "md_surface_container_highest"},
            {"md_primary", "md_surface_container_highest"},
            {"md_on_primary", "md_primary"},
            {"md_on_secondary_container", "md_secondary_container"},
    };

    /** 必须在两套主题里都存在且同名的角色。 */
    private static final String[] REQUIRED_ROLES = {
            "md_primary", "md_on_primary", "md_primary_container", "md_on_primary_container",
            "md_inverse_primary", "md_secondary", "md_on_secondary", "md_secondary_container",
            "md_on_secondary_container", "md_tertiary", "md_on_tertiary", "md_tertiary_container",
            "md_on_tertiary_container", "md_error", "md_on_error", "md_error_container",
            "md_on_error_container", "md_success", "md_on_success", "md_success_container",
            "md_on_success_container", "md_warning", "md_on_warning", "md_warning_container",
            "md_on_warning_container", "md_surface", "md_surface_container_lowest",
            "md_surface_container_low", "md_surface_container", "md_surface_container_high",
            "md_surface_container_highest", "md_on_surface", "md_surface_variant",
            "md_on_surface_variant", "md_inverse_surface", "md_inverse_on_surface",
            "md_outline", "md_outline_variant", "md_scrim", "md_shadow",
    };

    /** 字级角色：字号与行高都要存在，行高不小于字号。 */
    private static final String[] TYPE_ROLES = {
            "display_small", "headline_large", "headline_medium", "headline_small",
            "title_large", "title_medium", "title_small",
            "body_large", "body_medium", "body_small",
            "label_large", "label_medium", "label_small",
    };

    /** 同族字号必须从大到小；数字是允许的最小值（sp），只用来防止"标题比正文还小"。 */
    private static final String[][] TYPE_ORDER = {
            {"display_small", "headline_large"},
            {"headline_large", "headline_medium"},
            {"headline_medium", "headline_small"},
            {"headline_small", "title_large"},
            {"title_large", "title_medium"},
            {"title_medium", "title_small"},
            {"body_large", "body_medium"},
            {"body_medium", "body_small"},
            {"label_large", "label_medium"},
            {"label_medium", "label_small"},
    };

    private static final String[] SPACING = {
            "md_space_xs", "md_space_sm", "md_space_md", "md_space_lg",
            "md_space_xl", "md_space_xxl", "md_space_xxxl",
    };

    private static final String[] SHAPES = {
            "md_shape_none", "md_shape_xs", "md_shape_sm", "md_shape_md",
            "md_shape_lg", "md_shape_xl", "md_shape_xxl",
    };

    /** 可触达面积下限：Android 平台与 M3 都要求 48dp，WCAG 2.5.8 的 24px 是更低的下限。 */
    private static final String[] MIN_TOUCH_TARGETS = {
            "md_size_touch_target", "md_size_button_min_height",
            "md_size_field_min_height", "md_size_nav_item_min_height",
    };

    private static final String[] EASINGS = {
            "md_easing_emphasized", "md_easing_emphasized_decelerate",
            "md_easing_emphasized_accelerate", "md_easing_standard",
    };

    public static void main(String[] args) throws Exception {
        File root = repoRoot();
        Map<String, Integer> light = colors(new File(root, "res/values/tokens.xml"));
        Map<String, Integer> night = colors(new File(root, "res/values-night/tokens.xml"));
        Map<String, String> dimens = dimens(new File(root, "res/values/dimens.xml"));
        Map<String, Integer> integers = integers(new File(root, "res/values/dimens.xml"));
        Map<String, String[]> arrays = arrays(new File(root, "res/values/dimens.xml"));

        // 1. 两套主题的角色集合必须完全一致，否则切到深色会缺角色（表现为某处突然变成黑色）。
        TreeSet<String> lightNames = new TreeSet<>(light.keySet());
        TreeSet<String> nightNames = new TreeSet<>(night.keySet());
        if (!lightNames.equals(nightNames)) {
            TreeSet<String> missing = new TreeSet<>(lightNames);
            missing.removeAll(nightNames);
            TreeSet<String> extra = new TreeSet<>(nightNames);
            extra.removeAll(lightNames);
            throw new AssertionError("深浅两套颜色角色不一致，深色缺 " + missing + "，多 " + extra);
        }
        for (String role : REQUIRED_ROLES) {
            check(light.containsKey(role), "缺少必需角色 " + role);
        }

        // 2. 对比度：深浅两套、逐对算。
        for (String theme : new String[]{"浅色", "深色"}) {
            for (String[] pair : TEXT_PAIRS) {
                double ratio = contrast(lookup(theme, light, night, pair[0]),
                        lookup(theme, light, night, pair[1]));
                check(ratio >= 4.5, String.format(Locale.ROOT,
                        "%s：文字 %s / %s 只有 %.2f:1，低于 4.5:1", theme, pair[0], pair[1], ratio));
            }
            for (String[] pair : NON_TEXT_PAIRS) {
                double ratio = contrast(lookup(theme, light, night, pair[0]),
                        lookup(theme, light, night, pair[1]));
                check(ratio >= 3.0, String.format(Locale.ROOT,
                        "%s：非文字 %s / %s 只有 %.2f:1，低于 3:1", theme, pair[0], pair[1], ratio));
            }
        }

        // 3. 刻度：间距与形状单调不减，字级同族从大到小，行高不小于字号。
        int previous = -1;
        for (String name : SPACING) {
            int value = sp(dimens, name);
            check(value > previous, "间距刻度必须递增：" + name);
            previous = value;
        }
        previous = -1;
        for (String name : SHAPES) {
            int value = sp(dimens, name);
            check(value > previous, "形状刻度必须递增：" + name);
            previous = value;
        }
        for (String role : TYPE_ROLES) {
            String size = dimens.get("md_type_" + role + "_size");
            String line = dimens.get("md_type_" + role + "_line");
            check(size != null, "缺少字号 " + role);
            check(line != null, "缺少行高 " + role);
            check(value(line) >= value(size), role + " 的行高小于字号，放大字号会挤在一起");
            check(integers.containsKey("md_tracking_" + role), "缺字距 md_tracking_" + role);
        }
        for (String[] order : TYPE_ORDER) {
            int a = sp(dimens, "md_type_" + order[0] + "_size");
            int b = sp(dimens, "md_type_" + order[1] + "_size");
            check(a > b, "字级顺序反了：" + order[0] + " 应大于 " + order[1]);
        }

        // 4. 可触达下限（平台 48dp）。
        for (String name : MIN_TOUCH_TARGETS) {
            check(sp(dimens, name) >= 48, name + " 小于 48dp，不满足平台最小可触达面积");
        }

        // 5. 动效与断点。
        for (String name : new String[]{"md_motion_short3", "md_motion_medium1", "md_motion_medium3",
                "md_motion_overshoot_pct", "md_state_pressed_pct", "md_state_focus_pct"}) {
            check(integers.containsKey(name), "缺整数令牌 " + name);
        }
        int trackingScale = integers.containsKey("md_tracking_scale_pct")
                ? integers.get("md_tracking_scale_pct") : -1;
        check(trackingScale >= 0 && trackingScale <= 100,
                "字距缩放 md_tracking_scale_pct 必须在 0–100 之间，实际 " + trackingScale);
        for (String name : EASINGS) {
            String[] points = arrays.get(name);
            check(points != null && points.length == 4, name + " 必须是四个控制点");
            for (String point : points) Float.parseFloat(point);
        }
        check(integers.get("md_bp_medium") < integers.get("md_bp_expanded"),
                "断点顺序反了：medium 应小于 expanded");

        // 6. 动效时长必须递增，否则"短/中/长"的语义就没了。
        previous = 0;
        for (String name : new String[]{"md_motion_short1", "md_motion_short2", "md_motion_short3",
                "md_motion_short4", "md_motion_medium1", "md_motion_medium2", "md_motion_medium3",
                "md_motion_medium4", "md_motion_long1", "md_motion_long2", "md_motion_long4"}) {
            int value = integers.get(name);
            check(value > previous, "动效时长必须递增：" + name);
            previous = value;
        }

        // 7. 界面代码里引用的每个令牌都必须存在（拼错名字会在这里挂，而不是在真机上崩）。
        File uiDir = new File(root, "src/com/satori/qq/ui");
        for (File source : list(uiDir)) {
            String text = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);
            for (String name : matches(text, "color\\(\"([a-z0-9_]+)\"\\)")) {
                check(light.containsKey(name), source.getName() + " 引用了不存在的颜色 " + name);
            }
            for (String name : matches(text, "dimen\\(\"([a-z0-9_]+)\"\\)")) {
                String key = name.startsWith("md_") ? name : "md_" + name;
                check(dimens.containsKey(key), source.getName() + " 引用了不存在的尺寸 " + key);
            }
            for (String name : matches(text, "integer\\(\"([a-z0-9_]+)\"\\)")) {
                String key = name.startsWith("md_") ? name : "md_" + name;
                check(integers.containsKey(key), source.getName() + " 引用了不存在的整数令牌 " + key);
            }
            for (String name : matches(text, "easing\\(\"([a-z0-9_]+)\"\\)")) {
                check(arrays.containsKey("md_easing_" + name),
                        source.getName() + " 引用了不存在的缓动 " + name);
            }
            // 8. 界面代码里不许出现字面颜色——拼贴往往就是从"这里先写死一个色"开始的。
            for (String literal : matches(text, "\"#([0-9a-fA-F]{6,8})\"")) {
                throw new AssertionError(source.getName() + " 里出现字面颜色 #" + literal
                        + "；界面只能按语义角色取色，令牌在 res/values/tokens.xml");
            }
        }

        // 9. 应用图标必须与主色同源：0.24.0 之前界面用 #5D438B、图标用 #6750A4，视觉上分了叉。
        String icon = new String(Files.readAllBytes(
                new File(root, "artwork/icon.svg").toPath()), StandardCharsets.UTF_8);
        String primary = String.format("#%06X", light.get("md_primary") & 0xFFFFFF);
        check(icon.toUpperCase(Locale.ROOT).contains(primary),
                "artwork/icon.svg 的主色与 md_primary(" + primary + ") 不一致，图标与界面会分叉");

        System.out.println("DesignTokenTest passed (" + REQUIRED_ROLES.length + " roles, "
                + (TEXT_PAIRS.length + NON_TEXT_PAIRS.length) * 2 + " contrast pairs, "
                + TYPE_ROLES.length + " type roles)");
    }

    // ------------------------------------------------------------------ 颜色

    private static Map<String, Integer> colors(File xml) throws Exception {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Element element : elements(xml, "color")) {
            out.put(element.getAttribute("name"), parse(element.getTextContent().trim()));
        }
        check(!out.isEmpty(), "没有解析到任何颜色令牌：" + xml);
        return out;
    }

    private static Map<String, String> dimens(File xml) throws Exception {
        Map<String, String> out = new LinkedHashMap<>();
        for (Element element : elements(xml, "dimen")) {
            out.put(element.getAttribute("name"), element.getTextContent().trim());
        }
        return out;
    }

    private static Map<String, Integer> integers(File xml) throws Exception {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Element element : elements(xml, "integer")) {
            out.put(element.getAttribute("name"), Integer.parseInt(element.getTextContent().trim()));
        }
        return out;
    }

    private static Map<String, String[]> arrays(File xml) throws Exception {
        Map<String, String[]> out = new LinkedHashMap<>();
        for (Element element : elements(xml, "string-array")) {
            NodeList items = element.getElementsByTagName("item");
            String[] values = new String[items.getLength()];
            for (int i = 0; i < items.getLength(); i++) values[i] = items.item(i).getTextContent().trim();
            out.put(element.getAttribute("name"), values);
        }
        return out;
    }

    private static Element[] elements(File xml, String tag) throws Exception {
        Document document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml);
        NodeList nodes = document.getElementsByTagName(tag);
        Element[] out = new Element[nodes.getLength()];
        for (int i = 0; i < nodes.getLength(); i++) out[i] = (Element) nodes.item(i);
        return out;
    }

    private static int lookup(String theme, Map<String, Integer> light, Map<String, Integer> night, String role) {
        Integer value = "深色".equals(theme) ? night.get(role) : light.get(role);
        check(value != null, theme + " 缺角色 " + role);
        return value;
    }

    private static int parse(String value) {
        check(value.matches("#[0-9a-fA-F]{6,8}"), "颜色令牌不是 #RRGGBB：" + value);
        return (int) Long.parseLong(value.substring(1, 7), 16) | 0xFF000000;
    }

    /** WCAG 2.x 相对亮度与对比度。 */
    private static double contrast(int foreground, int background) {
        double a = luminance(foreground);
        double b = luminance(background);
        return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
    }

    private static double luminance(int color) {
        double[] weights = {0.2126, 0.7152, 0.0722};
        double value = 0;
        for (int i = 0; i < 3; i++) {
            double channel = ((color >> (16 - i * 8)) & 255) / 255.0;
            value += weights[i] * (channel <= 0.04045 ? channel / 12.92
                    : Math.pow((channel + 0.055) / 1.055, 2.4));
        }
        return value;
    }

    // ------------------------------------------------------------------ 尺度

    /** 只取数值，忽略 dp/sp 单位；dimens.xml 全用这两种单位。 */
    private static int value(String dimension) {
        return (int) Float.parseFloat(dimension.replaceAll("(dp|sp|dip|px)$", ""));
    }

    private static int sp(Map<String, String> dimens, String name) {
        String raw = dimens.get(name);
        check(raw != null, "缺少尺寸令牌 " + name);
        return value(raw);
    }

    // ------------------------------------------------------------------ 工具

    private static List<String> matches(String text, String regex) {
        List<String> out = new ArrayList<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(regex).matcher(text);
        while (matcher.find()) out.add(matcher.group(1));
        return out;
    }

    private static List<File> list(File directory) {
        List<File> out = new ArrayList<>();
        File[] children = directory.listFiles();
        if (children == null) return out;
        for (File child : children) {
            if (child.isDirectory()) out.addAll(list(child));
            else if (child.getName().endsWith(".java")) out.add(child);
        }
        return out;
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
