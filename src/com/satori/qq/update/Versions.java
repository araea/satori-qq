package com.satori.qq.update;

/**
 * 版本号比较：点分数字，忽略开头的 v 和 {@code -} 之后的后缀；缺的位当 0。
 * 只在没有 versionCode 可用时（拿不到 root、只知道服务报上来的版本名）兜底。
 */
public final class Versions {
    private Versions() {}

    public static int compare(String a, String b) {
        long[] x = parse(a);
        long[] y = parse(b);
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            long left = i < x.length ? x[i] : 0;
            long right = i < y.length ? y[i] : 0;
            if (left != right) return left < right ? -1 : 1;
        }
        return 0;
    }

    private static long[] parse(String value) {
        String text = value == null ? "" : value.trim();
        if (text.startsWith("v") || text.startsWith("V")) text = text.substring(1);
        int dash = text.indexOf('-');
        if (dash >= 0) text = text.substring(0, dash);
        String[] parts = text.isEmpty() ? new String[0] : text.split("\\.");
        long[] numbers = new long[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                numbers[i] = Long.parseLong(parts[i].trim());
            } catch (NumberFormatException ignored) {
                numbers[i] = 0;
            }
        }
        return numbers;
    }
}
