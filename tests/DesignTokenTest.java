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
 * 颜色对比度、角色齐整、刻度单调、可触达下限、弹簧参数，以及"界面代码只经 R 取令牌、
 * 不出现字面颜色"。
 *
 * <p>为什么值得钉住：对比度是 WCAG 2.2 AA 的硬指标（1.4.3 文字 4.5:1、1.4.11 非文字 3:1），
 * 靠肉眼在深浅两套主题里逐对看是不可靠的；而"界面里出现 #RRGGBB"这种拼贴正是设计体系
 * 失守的第一步。改令牌或加角色时，先看这个测试要求哪些配对。
 */
public final class DesignTokenTest {

    /** 文字角色：两两组合必须 ≥ 4.5:1（WCAG 1.4.3，按最严的普通字号算）。 */
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
            {"md_on_surface", "md_surface_container_low"},
            {"md_on_surface", "md_surface_container"},
            {"md_on_surface", "md_surface_container_high"},
            {"md_on_surface_variant", "md_surface"},
            {"md_on_surface_variant", "md_surface_container_low"},
            {"md_on_surface_variant", "md_surface_container"},
            {"md_on_surface_variant", "md_surface_container_high"},
            {"md_primary", "md_surface"},
            {"md_primary", "md_surface_container_low"},
            {"md_primary", "md_surface_container"},
            {"md_primary", "md_surface_container_high"},
            {"md_error", "md_surface"},
            {"md_error", "md_surface_container"},
            {"md_error", "md_surface_container_high"},
            {"md_inverse_on_surface", "md_inverse_surface"},
            {"md_inverse_primary", "md_inverse_surface"},
    };

    /**
     * 非文字角色：承担识别控件边界或状态的图形必须 ≥ 3:1（WCAG 1.4.11）。
     * outline 是输入框与关闭态开关的描边；primary 是焦点环与开启态开关；
     * success / warning / error 是连接链路里表达状态的图标（旁边另有文字）。
     */
    private static final String[][] NON_TEXT_PAIRS = {
            {"md_outline", "md_surface"},
            {"md_outline", "md_surface_container"},
            {"md_outline", "md_surface_container_high"},
            {"md_outline", "md_surface_container_highest"},
            {"md_primary", "md_surface_container_highest"},
            {"md_on_primary", "md_primary"},
            {"md_success", "md_surface_container"},
            {"md_warning", "md_surface_container"},
            {"md_error", "md_surface_container"},
            {"md_on_success_container", "md_success_container"},
            {"md_on_warning_container", "md_warning_container"},
            {"md_on_error_container", "md_error_container"},
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
            "md_outline", "md_outline_variant", "md_scrim", "md_state_layer",
    };

    private static final String[] TYPE_ROLES = {
            "display_small", "headline_large", "headline_small",
            "title_large", "title_medium", "title_small",
            "body_large", "body_medium", "body_small",
            "label_large", "label_medium",
    };

    /** 同族字号必须从大到小，防止"标题比正文还小"。 */
    private static final String[][] TYPE_ORDER = {
            {"display_small", "headline_large"},
            {"headline_large", "headline_small"},
            {"headline_small", "title_large"},
            {"title_large", "title_medium"},
            {"title_medium", "title_small"},
            {"body_large", "body_medium"},
            {"body_medium", "body_small"},
            {"label_large", "label_medium"},
    };

    private static final String[] SPACING = {
            "md_space_2xs", "md_space_xs", "md_space_sm", "md_space_md", "md_space_lg",
            "md_space_xl", "md_space_2xl", "md_space_3xl",
    };

    private static final String[] SHAPES = {
            "md_shape_xs", "md_shape_sm", "md_shape_md", "md_shape_lg",
            "md_shape_lg_increased", "md_shape_xl", "md_shape_xl_increased",
    };

    /** 可触达下限：Android 平台与 M3 都要求 48dp（WCAG 2.5.8 的 24px 是更低的下限）。 */
    private static final String[] MIN_TOUCH_TARGETS = {
            "md_size_touch_target", "md_size_button", "md_size_button_prominent",
            "md_size_field", "md_size_list_one_line", "md_size_list_two_line", "md_size_top_bar",
    };

    public static void main(String[] args) throws Exception {
        File root = repoRoot();
        Map<String, Integer> light = colors(new File(root, "res/values/tokens.xml"));
        Map<String, Integer> night = colors(new File(root, "res/values-night/tokens.xml"));
        Map<String, String> dimens = dimens(new File(root, "res/values/dimens.xml"));
        Map<String, Integer> integers = integers(new File(root, "res/values/dimens.xml"));

        // 1. 两套主题的角色集合必须完全一致，否则切到深色会缺角色。
        TreeSet<String> lightNames = new TreeSet<>(light.keySet());
        TreeSet<String> nightNames = new TreeSet<>(night.keySet());
        if (!lightNames.equals(nightNames)) {
            TreeSet<String> missing = new TreeSet<>(lightNames);
            missing.removeAll(nightNames);
            TreeSet<String> extra = new TreeSet<>(nightNames);
            extra.removeAll(lightNames);
            throw new AssertionError("深浅两套颜色角色不一致，深色缺 " + missing + "，多 " + extra);
        }
        for (String role : REQUIRED_ROLES) check(light.containsKey(role), "缺少必需角色 " + role);

        // 2. 对比度：深浅两套、逐对算。参与对比的颜色必须不透明。
        for (String theme : new String[]{"浅色", "深色"}) {
            for (String[] pair : TEXT_PAIRS) {
                double ratio = contrast(lookup(theme, light, night, pair[0]), lookup(theme, light, night, pair[1]));
                check(ratio >= 4.5, String.format(Locale.ROOT,
                        "%s：文字 %s / %s 只有 %.2f:1，低于 4.5:1", theme, pair[0], pair[1], ratio));
            }
            for (String[] pair : NON_TEXT_PAIRS) {
                double ratio = contrast(lookup(theme, light, night, pair[0]), lookup(theme, light, night, pair[1]));
                check(ratio >= 3.0, String.format(Locale.ROOT,
                        "%s：非文字 %s / %s 只有 %.2f:1，低于 3:1", theme, pair[0], pair[1], ratio));
            }
        }

        // 3. 刻度：间距与形状严格递增，字级同族从大到小，行高不小于字号。
        int previous = -1;
        for (String name : SPACING) {
            int value = number(dimens, name);
            check(value > previous, "间距刻度必须递增：" + name);
            previous = value;
        }
        previous = -1;
        for (String name : SHAPES) {
            int value = number(dimens, name);
            check(value > previous, "形状刻度必须递增：" + name);
            previous = value;
        }
        for (String role : TYPE_ROLES) {
            String size = dimens.get("md_type_" + role + "_size");
            String line = dimens.get("md_type_" + role + "_line");
            check(size != null && size.endsWith("sp"), "字号必须存在且用 sp：" + role);
            check(line != null && line.endsWith("sp"), "行高必须存在且用 sp：" + role);
            check(value(line) >= value(size), role + " 的行高小于字号，放大字号会挤在一起");
        }
        for (String[] order : TYPE_ORDER) {
            check(number(dimens, "md_type_" + order[0] + "_size") > number(dimens, "md_type_" + order[1] + "_size"),
                    "字级顺序反了：" + order[0] + " 应大于 " + order[1]);
        }
        for (Map.Entry<String, String> entry : dimens.entrySet()) {
            if (!entry.getKey().startsWith("md_type_")) {
                check(entry.getValue().endsWith("dp"), "非字级尺寸必须用 dp：" + entry.getKey());
            }
        }
        check(integers.get("md_weight_regular") < integers.get("md_weight_medium")
                && integers.get("md_weight_medium") < integers.get("md_weight_emphasized"), "字重必须递增");

        // 4. 可触达下限（平台 48dp）。
        for (String name : MIN_TOUCH_TARGETS) {
            check(number(dimens, name) >= 48, name + " 小于 48dp，不满足平台最小可触达面积");
        }
        check(number(dimens, "md_size_switch_handle_off") < number(dimens, "md_size_switch_handle_on")
                && number(dimens, "md_size_switch_handle_on") < number(dimens, "md_size_switch_handle_pressed")
                && number(dimens, "md_size_switch_handle_pressed") <= number(dimens, "md_size_switch_height"),
                "开关把手：关 < 开 < 按下 ≤ 轨道高度");

        // 5. 动效：弹簧阻尼比在 (0, 1]，刚度为正；效果弹簧必须临界阻尼（颜色与透明度不能回弹）。
        for (String name : new String[]{"md_spring_damping_standard", "md_spring_damping_expressive",
                "md_spring_damping_effects"}) {
            int value = integers.get(name);
            check(value > 0 && value <= 100, name + " 必须在 1–100（阻尼比 ×100）");
        }
        check(integers.get("md_spring_damping_effects") == 100, "效果弹簧必须临界阻尼");
        for (String name : new String[]{"md_spring_spatial_fast", "md_spring_spatial_default",
                "md_spring_expressive_fast", "md_spring_effects_fast", "md_spring_effects_default"}) {
            check(integers.get(name) > 0, name + " 刚度必须为正");
        }
        check(integers.get("md_spring_spatial_fast") > integers.get("md_spring_spatial_default"), "快弹簧必须更硬");
        check(integers.get("md_spring_effects_fast") > integers.get("md_spring_effects_default"), "快弹簧必须更硬");

        // 6. 状态层与断点。
        check(integers.get("md_state_hover_pct") < integers.get("md_state_pressed_pct"), "悬停层应弱于按压层");
        check(integers.get("md_bp_medium") < integers.get("md_bp_expanded"), "断点顺序反了");

        // 7. 界面代码只经 R 取令牌：不许出现字面颜色，也不许按名字查资源。
        File uiDir = new File(root, "src/com/satori/qq/ui");
        for (File source : list(uiDir)) {
            String text = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);
            for (String literal : matches(text, "\"#([0-9a-fA-F]{6,8})\"")) {
                throw new AssertionError(source.getName() + " 里出现字面颜色 #" + literal
                        + "；界面只能按语义角色取色，令牌在 res/values/tokens.xml");
            }
            for (String literal : matches(text, "(0x[0-9a-fA-F]{8})")) {
                check(literal.equalsIgnoreCase("0xFF000000") || literal.equalsIgnoreCase("0x00FFFFFF"),
                        source.getName() + " 里出现颜色字面量 " + literal + "（只允许遮罩与通道掩码）");
            }
            check(!text.contains("getIdentifier("), source.getName() + " 按名字查资源；改用生成的 R 类");
            for (String name : matches(text, "R\\.color\\.([a-z0-9_]+)")) {
                check(light.containsKey(name), source.getName() + " 引用了不存在的颜色 " + name);
            }
        }

        // 8. 应用图标必须与主色同源，否则图标与界面会分叉。
        String icon = new String(Files.readAllBytes(new File(root, "artwork/icon.svg").toPath()), StandardCharsets.UTF_8);
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
        check((value >>> 24) == 0xFF, theme + " 的 " + role + " 半透明，不能参与对比度判定");
        return value;
    }

    /** #RRGGBB 或 #AARRGGBB。 */
    private static int parse(String value) {
        check(value.matches("#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})"), "颜色令牌不是 #RRGGBB / #AARRGGBB：" + value);
        long raw = Long.parseLong(value.substring(1), 16);
        return value.length() == 7 ? (int) raw | 0xFF000000 : (int) raw;
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

    private static int number(Map<String, String> dimens, String name) {
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
