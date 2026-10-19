package com.satori.qq.update;

/**
 * 更新说明：发布说明是 Markdown，弹窗里只放得下纯文本——去掉标题井号、加粗、行内代码与链接语法，
 * 列表项换成圆点。只做这些，不是 Markdown 渲染器。
 */
public final class Notes {
    private Notes() {}

    public static String plain(String markdown) {
        StringBuilder out = new StringBuilder();
        boolean blank = true;
        for (String raw : markdown.replace("\r", "").split("\n")) {
            String line = raw.trim();
            if (line.isEmpty()) {
                if (!blank) out.append('\n');
                blank = true;
                continue;
            }
            if (line.startsWith("```") || line.matches("-{3,}|\\*{3,}")) continue;
            line = line.replaceFirst("^#{1,6}\\s*", "");
            line = line.replaceFirst("^[-*+]\\s+", "• ");
            line = line.replaceAll("\\[([^\\]]+)]\\([^)]*\\)", "$1");
            line = line.replace("**", "").replace("__", "").replace("`", "");
            out.append(line).append('\n');
            blank = false;
        }
        return out.toString().trim();
    }
}
